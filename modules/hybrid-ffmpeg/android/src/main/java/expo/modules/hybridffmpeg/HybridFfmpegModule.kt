package expo.modules.hybridffmpeg

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaCodecList
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.io.FileOutputStream

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class HybridFfmpegModule : Module() {

    private fun getContext(): Context {
        return requireNotNull(appContext.reactContext)
    }

    private fun findLatestVideoUri(): Uri {
        val context = getContext()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val selection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Video.Media.RELATIVE_PATH} NOT LIKE ?"
        } else null
        val selectionArgs = if (android.os.Build.VERSION.SDK_INT >= 29) {
            arrayOf("Movies/HybridVideoEditor/%")
        } else null

        context.contentResolver.query(collection, projection, selection, selectionArgs, sort)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        throw Exception("No video was found in Android MediaStore.")
    }

    private fun resolveInputFile(inputUriString: String?): Pair<File, String> {
        val supplied = inputUriString?.trim().orEmpty()
        val uri = if (supplied.isBlank()) findLatestVideoUri() else Uri.parse(supplied)
        val file = copyUriToCache(uri.toString())
        return file to uri.toString()
    }

    private fun publishVideoToMediaStore(source: File): Uri? {
        if (!source.exists() || source.length() == 0L) return null
        val context = getContext()
        val resolver = context.contentResolver
        val name = "HVE_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HybridVideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw Exception("Cannot open MediaStore output stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun copyUriToCache(uriString: String): File {
        val context = getContext()
        val uri = Uri.parse(uriString)
        val extension = uri.path?.substringAfterLast('.', "mp4")?.takeIf { it.length in 1..8 } ?: "mp4"
        val inputFile = File(context.cacheDir, "ffmpeg_input_${System.currentTimeMillis()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(inputFile).use { output -> input.copyTo(output) }
        } ?: throw Exception("Cannot open selected media")
        return inputFile
    }

    private fun copyAssetToCache(assetPath: String): File? {
        val context = getContext()
        val cleanPath = assetPath.removePrefix("/").removePrefix("./")
        return try {
            val assetManager = context.assets
            val safeName = cleanPath.replace("/", "_")
            val outputFile = File(context.cacheDir, "asset_$safeName")
            if (!outputFile.exists() || outputFile.length() == 0L) {
                assetManager.open(cleanPath).use { input ->
                    FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                }
            }
            outputFile
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveAssetPath(assetPath: String): String {
        val context = getContext()
        val cleanPath = assetPath.removePrefix("/").removePrefix("./")
        val candidates = listOf(
            File(context.filesDir, cleanPath),
            File(context.cacheDir, cleanPath),
            File(context.getExternalFilesDir(null), cleanPath),
            File("/storage/emulated/0/Bypass", cleanPath),
            File("/storage/emulated/0", cleanPath)
        )
        candidates.firstOrNull { it.isFile && it.canRead() }?.let { return it.absolutePath }
        val packagedAsset = copyAssetToCache(cleanPath)
        if (packagedAsset != null) return packagedAsset.absolutePath
        throw Exception("Required asset not found: $assetPath")
    }

    private fun normalizeCommand(command: String): String {
        return command.trim().replace("<INPUT_VIDEO>-i", "<INPUT_VIDEO> -i")
    }

    private fun resolveCommandAssets(command: String): String {
        val pattern = Regex("(?i)(\\b(?:amovie|movie)\\s*=\\s*)(?:'([^']+)'|\"([^\"]+)\"|([^,:;\\]\\s]+))")
        return pattern.replace(command) { match ->
            val prefix = match.groupValues[1]
            val rawPath = when {
                match.groupValues[2].isNotEmpty() -> match.groupValues[2]
                match.groupValues[3].isNotEmpty() -> match.groupValues[3]
                else -> match.groupValues[4]
            }.trim()
            if (rawPath.isBlank() || rawPath.startsWith("/")) {
                match.value
            } else {
                try {
                    val absolutePath = resolveAssetPath(rawPath)
                    prefix + "'" + absolutePath + "'"
                } catch (_: Throwable) {
                    match.value
                }
            }
        }
    }

    private fun shouldPreferCpuFallback(command: String): Boolean {
        val lower = command.lowercase()
        return lower.contains("-filter_complex") ||
            Regex("\\b(sin|cos|tan|asin|acos|atan|random|if)\\s*\\(").containsMatchIn(lower) ||
            lower.contains("overlay=") || lower.contains("hwupload") ||
            lower.contains("hwdownload") || lower.contains("_opencl") || lower.contains("vulkan")
    }

    private fun prepareCpuFallbackEncoder(command: String): String {
        var result = command
        result = result.replace(Regex("(?i)(-c:v\\s+)(h264_mediacodec|h264|libx265|libx264)"), "$1libopenh264")
        result = result.replace(Regex("(?i)(-vcodec\\s+)(h264_mediacodec|h264|libx265|libx264)"), "$1libopenh264")
        result = result.replace(Regex("(?i)(-codec:v\\s+)(h264_mediacodec|h264|libx265|libx264)"), "$1libopenh264")
        val hasVideoCodec = Regex("(?i)(-c:v|-vcodec|-codec:v)\\s+\\S+").containsMatchIn(result)
        if (!hasVideoCodec) result += " -c:v libopenh264"
        result = result.replace(Regex("(?i)\\s+-preset\\s+\\S+"), "")
        result = result.replace(Regex("(?i)\\s+-crf\\s+\\S+"), "")
        result = result.replace(Regex("(?i)\\s+-tune\\s+\\S+"), "")
        result = result.replace(Regex("(?i)\\s+-threads\\s+\\S+"), "")
        return result.trim()
    }

    private fun classifyFallbackReason(command: String, media3Error: String? = null): String {
        val lower = command.lowercase()
        return when {
            media3Error?.contains("encoder", true) == true || media3Error?.contains("codec", true) == true -> "MEDIA3_HARDWARE_ENCODER_FAILURE"
            lower.contains("filter_complex") || Regex("\\b(sin|cos|tan|asin|acos|atan|random|if)\\s*\\(").containsMatchIn(lower) -> "COMPLEX_OR_DYNAMIC_GRAPH_NOT_MAPPED_TO_MEDIA3"
            lower.contains("opencl") || lower.contains("hwupload") || lower.contains("vulkan") -> "EXPLICIT_EXTERNAL_GPU_GRAPH"
            lower.contains("amovie=") || lower.contains("movie=") -> "EXTERNAL_ASSET_OR_AUDIO_GRAPH"
            else -> "MEDIA3_GRAPH_UNSUPPORTED"
        }
    }

    private fun prepareHardwareEncoder(command: String): String {
        var result = command
        result = result.replace(Regex("""(?i)(-c:v\s+)(libx264|libx265|h264)"""), "$1h264_mediacodec")
        result = result.replace(Regex("""(?i)(-vcodec\s+)(libx264|libx265|h264)"""), "$1h264_mediacodec")
        result = result.replace(Regex("""(?i)(-codec:v\s+)(libx264|libx265|h264)"""), "$1h264_mediacodec")
        val hasVideoCodec = Regex("""(?i)(-c:v|-vcodec|-codec:v)\s+\S+""").containsMatchIn(result)
        if (!hasVideoCodec) result += " -c:v h264_mediacodec"
        result = result.replace(Regex("""(?i)\s+-preset\s+\S+"""), "")
        result = result.replace(Regex("""(?i)\s+-crf\s+\S+"""), "")
        result = result.replace(Regex("""(?i)\s+-tune\s+\S+"""), "")
        result = result.replace(Regex("""(?i)\s+-threads\s+\S+"""), "")
        return result.trim()
    }

    private fun buildUniversalCommand(userCommand: String, inputFile: File, outputFile: File): String {
        var command = normalizeCommand(userCommand)
        val isFullCommand = command.contains("-i") || command.contains("<INPUT_VIDEO>")
        if (isFullCommand) {
            command = command.replace("<INPUT_VIDEO>", "\"${inputFile.absolutePath}\"")
            command = command.replace("<OUTPUT_VIDEO>", "\"${outputFile.absolutePath}\"")
            if (!command.contains(outputFile.absolutePath)) command = "$command \"${outputFile.absolutePath}\""
        } else {
            command = "-y -i \"${inputFile.absolutePath}\" $command \"${outputFile.absolutePath}\""
        }
        command = resolveCommandAssets(command)
        command = if (shouldPreferCpuFallback(command)) prepareCpuFallbackEncoder(command) else prepareHardwareEncoder(command)
        return command.trim()
    }

    private fun analyzeGpuRequest(command: String): Map<String, Any> {
        val lower = command.lowercase()
        val gpuFilters = mutableListOf<String>()
        val knownOpenClFilters = listOf("avgblur_opencl", "boxblur_opencl", "convolution_opencl", "crop_opencl", "deshake_opencl", "nlmeans_opencl", "overlay_opencl", "pad_opencl", "prewitt_opencl", "program_opencl", "remap_opencl", "scale_opencl", "tonemap_opencl", "transpose_opencl", "unsharp_opencl")
        for (filter in knownOpenClFilters) if (lower.contains(filter)) gpuFilters.add(filter)
        val openClRequested = lower.contains("opencl") || lower.contains("hwupload") || lower.contains("hwdownload")
        return mapOf(
            "gpuRequested" to openClRequested,
            "openclFilters" to gpuFilters,
            "hybridMode" to if (openClRequested) "GPU+CPU" else "CPU+HardwareEncoder"
        )
    }

    init {
        try {
            System.loadLibrary("hybrid_opencl")
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    private external fun nativeOpenClDiagnostic(): String

    override fun definition() = ModuleDefinition {
        Name("HybridFfmpeg")

        Function("getHardwareCapabilities") {
            val mediaCodecH264Available = try {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                    info.isEncoder && info.supportedTypes.any { type -> type.equals("video/avc", ignoreCase = true) }
                }
            } catch (_: Throwable) { false }
            mapOf("ffmpegAvailable" to true, "mediaCodecH264Available" to mediaCodecH264Available, "gpuDetection" to "COMMAND_DETECTION", "universalCommandEngine" to true)
        }

        fun hasAudioProcessing(command: String): Boolean {
            val lower = command.lowercase()
            val audioMarkers = listOf("amix", "amovie", "atempo", "aecho", "bass", "firequalizer", "compand", "pan=", "volume", "highpass", "lowpass", "acompressor", "afade")
            return FfmpegCommandTokenizer.hasOption(command, "-af") || audioMarkers.any { lower.contains(it) }
        }

        fun extractSimpleAudioFilter(command: String): String? {
            val filter = FfmpegCommandTokenizer.findOptionValue(command, "-af")?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val complexAudio = listOf("amix", "amovie", "amerge", "pan=", "asplit", "concat=")
            if (complexAudio.any { filter.contains(it, ignoreCase = true) }) return null
            return filter
        }

        fun runMedia3VideoBackend(inputFile: File, outputFile: File, command: String, removeAudio: Boolean = false): Map<String, Any>? {
            val plan = FfmpegVideoCommandParser.parse(command)
            if (!plan.supported) throw Exception("Parser Rejected: ${plan.reason}")
            return Media3VideoTransformer.render(getContext(), inputFile, outputFile, plan, removeAudio)
                .toMutableMap().apply {
                    this["sourceFilter"] = plan.sourceFilter
                    this["media3Plan"] = plan.effects.map { it::class.simpleName ?: "effect" }
                }
        }

        fun extractFilterComplex(command: String): String? {
            return FfmpegCommandTokenizer.findOptionValue(command, "-filter_complex")?.takeIf { it.isNotBlank() }
        }

        fun splitComplexStatements(graph: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var quote: Char? = null
            var start = 0
            for (i in graph.indices) {
                val c = graph[i]
                if (quote != null) {
                    if (c == quote && (i == 0 || graph[i - 1] != '\\')) quote = null
                } else if (c == '\'' || c == '"') quote = c
                else if (c == '(' || c == '[') depth++
                else if (c == ')' || c == ']') depth--
                else if (c == ';' && depth == 0) {
                    out += graph.substring(start, i).trim()
                    start = i + 1
                }
            }
            out += graph.substring(start).trim()
            return out.filter { it.isNotBlank() }
        }

        fun extractComplexAudioGraph(command: String): String? {
            val graph = extractFilterComplex(command) ?: return null
            val statements = splitComplexStatements(graph)
            val audioMarkers = listOf("atempo", "bass", "volume", "aecho", "firequalizer", "compand", "pan=", "highpass", "lowpass", "amix", "amerge", "amovie", "anull", "acompressor", "afade", "aresample", "aformat")
            val selected = mutableListOf<String>()
            val knownLabels = mutableSetOf("0:a")
            var changed = true
            while (changed) {
                changed = false
                for (statement in statements) {
                    if (selected.contains(statement)) continue
                    val lower = statement.lowercase()
                    val hasAudioSource = lower.contains("[0:a]") || lower.contains("[1:a]") || lower.contains("amovie=")
                    val referencesKnown = knownLabels.any { statement.contains("[$it]") }
                    val hasAudioFilter = audioMarkers.any { lower.contains(it) }
                    if (hasAudioSource || referencesKnown || hasAudioFilter) {
                        selected += statement
                        Regex("\\[([^]]+)\\]").findAll(statement).forEach { knownLabels += it.groupValues[1] }
                        changed = true
                    }
                }
            }
            if (selected.isEmpty()) return null
            if (selected.any { it.contains(Regex("\\[\\d+:v\\]")) || it.contains("overlay=", true) }) return null
            var last = selected.last().trim()
            if (!Regex("\\[[A-Za-z_][A-Za-z0-9_]*]\\s*$").containsMatchIn(last)) {
                last += "[hve_aout]"
                selected[selected.lastIndex] = last
            } else {
                val labelMatch = Regex("\\[([A-Za-z_][A-Za-z0-9_]*)]\\s*$").find(last)
                if (labelMatch != null) {
                    selected[selected.lastIndex] = last.substring(0, labelMatch.range.first) + "[hve_aout]"
                }
            }
            return selected.joinToString(";")
        }

        fun runComplexOverlayHybridBackend(inputFile: File, outputFile: File, command: String, plan: FfmpegComplexCommandParser.OverlayPlan): Map<String, Any>? {
            val hasAudio = hasAudioProcessing(command)
            val audioGraph = if (hasAudio) extractComplexAudioGraph(command) else null
            if (hasAudio && audioGraph == null) throw Exception("Parser Rejected: Complex audio extraction failed")

            val audioFile = File(getContext().cacheDir, "hve_composite_audio_${System.currentTimeMillis()}.m4a")
            try {
                if (audioGraph != null) {
                    val audioCommand = "-y -i \"${inputFile.absolutePath}\" -filter_complex \"$audioGraph\" -map \"[hve_aout]\" -vn -c:a aac -b:a 192k \"${audioFile.absolutePath}\""
                    val audioSession = FFmpegKit.execute(audioCommand)
                    if (!ReturnCode.isSuccess(audioSession.returnCode)) throw Exception(audioSession.getAllLogsAsString().ifBlank { "Complex audio processing failed" })
                }
                val media3 = Media3OverlayComposer.render(getContext(), inputFile, outputFile, plan, removeAudio = audioGraph != null, externalAudioFile = audioFile.takeIf { audioGraph != null && it.exists() })
                return media3.toMutableMap().apply {
                    this["videoBackend"] = "Media3 Composition + OpenGL ES"
                    this["audioBackend"] = if (audioGraph != null) "FFmpegKit -> Media3 audio sequence" else "Media3 passthrough"
                    this["hybridMode"] = if (audioGraph != null) "GPU_COMPOSITION+FFMPEG_AUDIO_IN_COMPOSITION" else "GPU_COMPOSITION"
                    this["muxBackend"] = "Media3 Transformer muxer"
                }
            } finally {
                audioFile.delete()
            }
        }

        fun runHybridVideoAudioBackend(inputFile: File, videoFile: File, audioFile: File, outputFile: File, command: String): Map<String, Any>? {
            val plan = FfmpegVideoCommandParser.parse(command)
            if (!plan.supported) throw Exception("Parser Rejected: ${plan.reason}")
            val audioFilter = extractSimpleAudioFilter(command) ?: throw Exception("Parser Rejected: Unsupported audio filter")

            if (audioFile.exists()) audioFile.delete()
            if (outputFile.exists()) outputFile.delete()

            val audioCommand = "-y -i \"${inputFile.absolutePath}\" -vn -sn -dn -af \"$audioFilter\" -c:a aac -b:a 192k \"${audioFile.absolutePath}\""
            val audioSession = FFmpegKit.execute(audioCommand)
            if (!ReturnCode.isSuccess(audioSession.returnCode)) throw Exception("FFmpeg audio processing failed")

            val media3 = Media3VideoTransformer.render(getContext(), inputFile, outputFile, plan, removeAudio = true, externalAudioFile = audioFile)
            return media3.toMutableMap().apply {
                this["videoBackend"] = "Media3 Transformer + OpenGL ES"
                this["audioBackend"] = "FFmpegKit (-af) -> Media3 audio sequence"
                this["hybridMode"] = "GPU_VIDEO+FFMPEG_AUDIO_IN_COMPOSITION"
            }
        }

        AsyncFunction("renderTestVideo") { inputUri: String, userCommand: String, promise: Promise ->
            try {
                val resolvedInput = resolveInputFile(inputUri)
                val inputFile = resolvedInput.first
                val resolvedInputUri = resolvedInput.second
                val outputFile = File(getContext().cacheDir, "ffmpeg_output_${System.currentTimeMillis()}.mp4")
                val finalCommand = buildUniversalCommand(userCommand, inputFile, outputFile)
                val gpuInfo = analyzeGpuRequest(finalCommand).toMutableMap()
                gpuInfo["fallbackSafetyPolicy"] = if (shouldPreferCpuFallback(finalCommand)) "CPU_SAFE_FOR_COMPLEX_OR_DYNAMIC_GRAPH" else "MEDIA_CODEC_ALLOWED"

                try {
                    val complexOverlayPlan = FfmpegComplexCommandParser.parse(finalCommand)
                    val media3Result = if (complexOverlayPlan?.supported == true) {
                        runComplexOverlayHybridBackend(inputFile, outputFile, finalCommand, complexOverlayPlan)
                    } else {
                        val hasAudioFilter = hasAudioProcessing(finalCommand)
                        if (hasAudioFilter) {
                            val videoOnlyFile = File(getContext().cacheDir, "hve_video_${System.currentTimeMillis()}.mp4")
                            val audioFile = File(getContext().cacheDir, "hve_audio_${System.currentTimeMillis()}.m4a")
                            val hybrid = runHybridVideoAudioBackend(inputFile, videoOnlyFile, audioFile, outputFile, finalCommand)
                            videoOnlyFile.delete()
                            audioFile.delete()
                            hybrid
                        } else {
                            runMedia3VideoBackend(inputFile, outputFile, finalCommand, false)
                        }
                    }
                    if (media3Result != null) {
                        val result = media3Result.toMutableMap()
                        val publishedUri = publishVideoToMediaStore(outputFile)
                        result["command"] = finalCommand
                        result["originalCommand"] = userCommand
                        result["inputUri"] = resolvedInputUri
                        result["inputName"] = inputFile.name
                        result["outputUri"] = publishedUri?.toString() ?: ""
                        result["gpuRequested"] = true
                        if (!result.containsKey("hybridMode")) result["hybridMode"] = "GPU_VIDEO"
                        if (!result.containsKey("audioBackend")) result["audioBackend"] = "Media3 passthrough"
                        promise.resolve(result)
                        return@AsyncFunction
                    }
                } catch (gpuError: Throwable) {
                    // FIXED: Extract the deeply nested root cause generated by Media3's GlUtil
                    val rootCause = generateSequence(gpuError) { it.cause }.last()
                    val errMsg = rootCause.message ?: rootCause.toString()
                    
                    gpuInfo["gpuFallback"] = true
                    gpuInfo["gpuFallbackReason"] = errMsg
                    gpuInfo["fallbackReasonCode"] = classifyFallbackReason(finalCommand, errMsg)
                    gpuInfo["videoBackend"] = "FFmpegKit Fallback (Media3 Crash: $errMsg)"
                }

                val fallbackCpuSafe = shouldPreferCpuFallback(finalCommand)

                fun resolveFallbackResult(session: com.arthenica.ffmpegkit.Session, usedCommand: String, encoderStrategy: String) {
                    val returnCode = session.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        val publishedUri = publishVideoToMediaStore(outputFile)
                        val result = mutableMapOf<String, Any>(
                            "success" to true,
                            "outputPath" to outputFile.absolutePath,
                            "outputUri" to (publishedUri?.toString() ?: ""),
                            "inputUri" to resolvedInputUri,
                            "inputName" to inputFile.name,
                            "command" to usedCommand,
                            "originalCommand" to userCommand,
                            "hardwareEncoder" to encoderStrategy,
                            "videoBackend" to (gpuInfo["videoBackend"]?.toString() ?: "FFmpegKit fallback"),
                            "fallbackEncoderStrategy" to encoderStrategy,
                            "audioBackend" to "FFmpegKit",
                            "message" to "Universal FFmpeg fallback render completed"
                        )
                        result.putAll(gpuInfo)
                        promise.resolve(result)
                    } else {
                        val diagnostic = session.getAllLogsAsString().takeLast(12000)
                        promise.reject("FFMPEG_FAILED", "Render failed. Command: $usedCommand\n\nLog: $diagnostic", null)
                    }
                }

                FFmpegKit.executeAsync(finalCommand) { session ->
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        resolveFallbackResult(session, finalCommand, if (fallbackCpuSafe) "libopenh264 (CPU_SAFE_FALLBACK)" else "h264_mediacodec")
                        return@executeAsync
                    }
                    if (!fallbackCpuSafe && finalCommand.contains("h264_mediacodec", ignoreCase = true)) {
                        val cpuRetryCommand = prepareCpuFallbackEncoder(finalCommand)
                        FFmpegKit.executeAsync(cpuRetryCommand) { retrySession ->
                            if (ReturnCode.isSuccess(retrySession.returnCode)) {
                                resolveFallbackResult(retrySession, cpuRetryCommand, "libopenh264 (AUTO_RETRY)")
                            } else {
                                promise.reject("FFMPEG_FAILED", "Fallback failed", null)
                            }
                        }
                        return@executeAsync
                    }
                    promise.reject("FFMPEG_FAILED", "Render failed", null)
                }
            } catch (error: Exception) {
                promise.reject("FFMPEG_EXCEPTION", error.message ?: "Unknown FFmpeg error", error)
            }
        }
    }
}
