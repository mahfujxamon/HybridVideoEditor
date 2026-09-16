package expo.modules.hybridffmpeg

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaCodecList
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

@OptIn(UnstableApi::class)
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

    // TASK 1: Windows Batch Command Sanitization
    private fun sanitizeBatchCommand(command: String): String {
        var clean = command.trim()
        val ffmpegMatch = Regex("(?i)^.*?\\bffmpeg(?:\\.exe)?\\s+").find(clean)
        if (ffmpegMatch != null) {
            clean = clean.substring(ffmpegMatch.range.last + 1).trim()
        }
        return clean
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

    // TASK 1 FIX: Clean batch path and replace Windows output path properly
    private fun buildUniversalCommand(userCommand: String, inputFile: File, outputFile: File): String {
        val sanitized = sanitizeBatchCommand(userCommand)
        var command = normalizeCommand(sanitized)
        command = command.replace("%%t", "\"${inputFile.absolutePath}\"")
        command = command.replace("%~nt", inputFile.nameWithoutExtension)

        // Strip Windows batch output path like "_output\video.mp4" or "_output/video.mp4"
        command = command.replace(Regex("""(?i)"?_output[\\/][^"\s]+"?"""), "")

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

    private fun extractInputStartTimes(command: String): Pair<Double, Double> {
        val tokens = FfmpegCommandTokenizer.tokenize(command)
        var input0Start = 0.0
        var input1Start = 0.0
        var inputCount = 0
        for (i in tokens.indices) {
            if (tokens[i].equals("-i", ignoreCase = true) && i + 1 < tokens.size) {
                var ss = 0.0
                if (i >= 2 && tokens[i - 2].equals("-ss", ignoreCase = true)) {
                    ss = tokens[i - 1].toDoubleOrNull() ?: 0.0
                } else if (i >= 1 && tokens[i - 1].startsWith("-ss=", ignoreCase = true)) {
                    ss = tokens[i - 1].substringAfter('=').toDoubleOrNull() ?: 0.0
                }
                if (inputCount == 0) input0Start = ss
                else if (inputCount == 1) input1Start = ss
                inputCount++
            }
        }
        return Pair(input0Start, input1Start)
    }

    // TASK 2 & 5: Strict Parser Mapping including scale=iw:ih
    private fun parseChainToMedia3Effects(filterText: String): List<Effect> {
        val effects = mutableListOf<Effect>()
        val filters = filterText.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        for (filter in filters) {
            val name = filter.substringBefore('=').trim().lowercase()
            val args = filter.substringAfter('=', "").trim()

            when {
                name == "hflip" -> effects.add(ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build())
                name == "vflip" -> effects.add(ScaleAndRotateTransformation.Builder().setScale(1f, -1f).build())
                name == "negate" -> effects.add(RgbFilter.createInvertedFilter())
                name == "boxblur" -> effects.add(GaussianBlur(1.0f))
                name == "setdar" && args.contains("21/9") -> effects.add(DarEffect(21f / 9f))
                name == "scale" -> {
                    when {
                        // TASK 2 FIX: scale=iw:ih (Identity scale - very common in 96 files)
                        args == "iw:ih" || args.contains("scale=iw:ih") -> {
                            effects.add(ScaleAndRotateTransformation.Builder().setScale(1f, 1f).build())
                        }
                        args.contains("trunc(trunc(iw*(4/3))/2)*2:ih") -> effects.add(ScaleAndRotateTransformation.Builder().setScale(4f / 3f, 1f).build())
                        args.contains("iw*1.5:ih*1.5") || args.contains("trunc(iw*1.5):trunc(ih*1.5)") -> effects.add(ScaleAndRotateTransformation.Builder().setScale(1.5f, 1.5f).build())
                        args.contains("iw*2:ih*2") -> effects.add(ScaleAndRotateTransformation.Builder().setScale(2f, 2f).build())
                        args.contains("iw*3:ih*3") -> effects.add(ScaleAndRotateTransformation.Builder().setScale(3f, 3f).build())
                        args.contains("trunc(iw*2):trunc(ih*1.5)") -> effects.add(ScaleAndRotateTransformation.Builder().setScale(2f, 1.5f).build())
                        else -> throw Exception("Unmapped GPU filter: scale=$args")
                    }
                }
                name == "crop" -> {
                    if (args.contains("sin(")) {
                        val wDiv = Regex("in_w\\s*/\\s*([0-9.]+)").find(args)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.5f
                        val hDiv = Regex("in_h\\s*/\\s*([0-9.]+)").find(args)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.5f
                        val parts = args.split(':')
                        val xExpr = if (parts.size >= 3) parts[2] else "0.0"
                        val yExpr = if (parts.size >= 4) parts[3] else "0.0"
                        val glslX = AdvancedFfmpegMathCompiler.compileCropMathToGLSL(xExpr)
                        val glslY = AdvancedFfmpegMathCompiler.compileCropMathToGLSL(yExpr)
                        effects.add(UniversalMathCropEffect(wDiv, hDiv, glslX, glslY))
                    } else if (args.contains("iw/3:ih/3:iw/2:ih/4")) {
                        effects.add(Crop(0f, 0.6667f, -0.1667f, 0.5f))
                    } else if (args.contains("iw/2:ih/2:0:ih*0.3")) {
                        effects.add(Crop(-1f, 0f, -0.6f, 0.4f))
                    } else if (args.contains("iw/2:ih/2:0:0")) {
                        effects.add(Crop(-1f, 0f, 0f, 1f))
                    } else if (args.contains("iw/1.5:ih/1.5")) {
                        effects.add(Crop(-0.6667f, 0.6667f, -0.6667f, 0.6667f))
                    } else if (args.contains("iw/2:ih/2")) {
                        effects.add(Crop(-0.5f, 0.5f, -0.5f, 0.5f))
                    } else {
                        throw Exception("Unmapped GPU filter: crop=$args")
                    }
                }
                name == "ai_shader" -> {
                    try {
                        val shaderCode = File(args).readText()
                        effects.add(CustomAiShaderEffect(shaderCode))
                    } catch (e: Exception) {
                        throw Exception("Failed to read AI Shader file at $args", e)
                    }
                }
                else -> throw Exception("Unmapped GPU filter: $name=$args")
            }
        }
        return effects
    }

    private fun buildEditedItem(input: File, startSeconds: Double, effects: List<Effect>, removeAudio: Boolean): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(max(0L, (startSeconds * 1000.0).toLong()))
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(input))
            .setClippingConfiguration(clip)
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .setRemoveAudio(removeAudio)
            .build()
    }

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

        fun extractFilterComplex(command: String): String? {
            return FfmpegCommandTokenizer.findOptionValue(command, "-filter_complex")?.takeIf { it.isNotBlank() }
        }
        
        fun extractMetadataString(command: String): String {
            return Regex("-metadata\\s+[a-zA-Z0-9_]+=(?:\"[^\"]*\"|'[^']*'|\\S+)")
                .findAll(command)
                .map { it.value }
                .joinToString(" ")
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
            if (!Regex("\\[[A-Za-z_][A-Za-z0-9_]*\\]\\s*$").containsMatchIn(last)) {
                last += "[hve_aout]"
                selected[selected.lastIndex] = last
            } else {
                val labelMatch = Regex("\\[([A-Za-z_][A-Za-z0-9_]*)\\]\\s*$").find(last)
                if (labelMatch != null) {
                    selected[selected.lastIndex] = last.substring(0, labelMatch.range.first) + "[hve_aout]"
                }
            }
            return selected.joinToString(";")
        }

        fun tryRunAdvancedMedia3Graph(inputFile: File, outputFile: File, command: String): Map<String, Any>? {
            val graph = extractFilterComplex(command) ?: return null
            val statements = splitComplexStatements(graph)
            val overlayStatements = statements.filter { it.contains("overlay", ignoreCase = true) }
            if (overlayStatements.isEmpty()) return null

            val hasAudio = hasAudioProcessing(command)
            val audioGraph = if (hasAudio) extractComplexAudioGraph(command) else null
            val audioFile = File(getContext().cacheDir, "hve_audio_pipe_${System.currentTimeMillis()}.m4a")
            var audioProduced = false

            if (audioGraph != null) {
                val (in0Start, _) = extractInputStartTimes(command)
                val ssOption = if (in0Start > 0.0) "-ss $in0Start " else ""
                val audioCommand = "-y $ssOption-i \"${inputFile.absolutePath}\" -filter_complex \"$audioGraph\" -map \"[hve_aout]\" -vn -c:a aac -b:a 192k \"${audioFile.absolutePath}\""
                val audioSession = FFmpegKit.execute(audioCommand)
                if (ReturnCode.isSuccess(audioSession.returnCode) && audioFile.exists() && audioFile.length() > 0L) {
                    audioProduced = true
                }
            }

            try {
                val (in0Start, in1Start) = extractInputStartTimes(command)
                val labelEffects = mutableMapOf<String, List<Effect>>()
                val labelSourceInput = mutableMapOf<String, Int>()

                for (stmt in statements) {
                    if (stmt.contains("overlay", ignoreCase = true)) continue
                    val match = Regex("\\[(\\d+):v\\](.*)\\[([a-zA-Z0-9_]+)\\]").find(stmt)
                    if (match != null) {
                        val inputIdx = match.groupValues[1].toInt()
                        val filterText = match.groupValues[2]
                        val outLabel = match.groupValues[3]
                        labelEffects[outLabel] = parseChainToMedia3Effects(filterText)
                        labelSourceInput[outLabel] = inputIdx
                        continue
                    }
                    val intermediateMatch = Regex("\\[([a-zA-Z0-9_]+)\\](.*)\\[([a-zA-Z0-9_]+)\\]").find(stmt)
                    if (intermediateMatch != null) {
                        val inLabel = intermediateMatch.groupValues[1]
                        val filterText = intermediateMatch.groupValues[2]
                        val outLabel = intermediateMatch.groupValues[3]
                        val combined = (labelEffects[inLabel] ?: emptyList()) + parseChainToMedia3Effects(filterText)
                        labelEffects[outLabel] = combined
                        labelSourceInput[outLabel] = labelSourceInput[inLabel] ?: 0
                    }
                }

                val firstOverlay = overlayStatements.first()
                val firstLabels = Regex("\\[([^]]+)\\]").findAll(firstOverlay).map { it.groupValues[1] }.toList()
                val baseLabel = firstLabels.getOrNull(0) ?: "0:v"
                val baseInputIdx = when {
                    baseLabel == "0:v" -> 0
                    baseLabel == "1:v" -> 1
                    else -> labelSourceInput[baseLabel] ?: 0
                }
                val baseStartSec = if (baseInputIdx == 1) in1Start else in0Start
                val baseEffects = labelEffects[baseLabel] ?: emptyList()
                val baseItem = buildEditedItem(inputFile, baseStartSec, baseEffects, removeAudio = true)

                val overlayNodes = mutableListOf<MultiSequenceOverlayCompositor.OverlayNode>()
                for ((idx, stmt) in overlayStatements.withIndex()) {
                    val labels = Regex("\\[([^]]+)\\]").findAll(stmt).map { it.groupValues[1] }.toList()
                    val topLabel = labels.getOrNull(1) ?: continue
                    val topInputIdx = when {
                        topLabel == "0:v" -> 0
                        topLabel == "1:v" -> 1
                        else -> labelSourceInput[topLabel] ?: 1
                    }
                    val topStartSec = if (topInputIdx == 1) in1Start else in0Start
                    val topEffects = labelEffects[topLabel] ?: emptyList()
                    val topItem = buildEditedItem(inputFile, topStartSec, topEffects, removeAudio = true)

                    val enableExpr = Regex("enable='([^']+)'").find(stmt)?.groupValues?.get(1)
                        ?: Regex("enable=([a-zA-Z0-9_()*.,]+)").find(stmt)?.groupValues?.get(1)
                        ?: ""

                    overlayNodes.add(
                        MultiSequenceOverlayCompositor.OverlayNode(
                            enableExpression = enableExpr,
                            zIndex = idx + 1,
                            editedItem = topItem
                        )
                    )
                }

                return MultiSequenceOverlayCompositor.renderMultiGraph(
                    context = getContext(),
                    outputFile = outputFile,
                    baseItem = baseItem,
                    overlayNodes = overlayNodes,
                    externalAudioFile = if (audioProduced) audioFile else null
                ).toMutableMap().apply {
                    this["audioBackend"] = if (audioProduced) "FFmpegKit -> Media3 audio sequence" else "None"
                    this["overlayCount"] = overlayNodes.size
                    this["appliedGpuEffects"] = "Multi-Layer Composition (${overlayNodes.size} Overlays)"
                    this["executionStatus"] = "GPU_SUCCESS"
                    this["failedEffectsOrReason"] = "None"
                }
            } finally {
                if (audioFile.exists()) audioFile.delete()
            }
        }

        fun runMedia3VideoBackend(inputFile: File, outputFile: File, command: String, removeAudio: Boolean = false): Map<String, Any> {
            val plan = FfmpegVideoCommandParser.parse(command)
            if (!plan.supported) throw Exception("Parser Rejected: ${plan.reason ?: "Unknown"}")
            return Media3VideoTransformer.render(getContext(), inputFile, outputFile, plan, removeAudio)
                .toMutableMap().apply {
                    this["sourceFilter"] = plan.sourceFilter ?: "None" 
                    this["media3Plan"] = plan.effects.map { it::class.simpleName ?: "effect" }
                    this["appliedGpuEffects"] = plan.effects.map { it::class.simpleName ?: "effect" }.joinToString(", ")
                    this["executionStatus"] = "GPU_SUCCESS"
                    this["failedEffectsOrReason"] = "None"
                }
        }

        fun runHybridVideoAudioBackend(inputFile: File, videoFile: File, audioFile: File, outputFile: File, command: String): Map<String, Any> {
            val plan = FfmpegVideoCommandParser.parse(command)
            if (!plan.supported) throw Exception("Parser Rejected: ${plan.reason ?: "Unknown"}")
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
                this["appliedGpuEffects"] = plan.effects.map { it::class.simpleName ?: "effect" }.joinToString(", ") + ", Audio Filter"
                this["executionStatus"] = "GPU_SUCCESS"
                this["failedEffectsOrReason"] = "None"
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
                    val advancedMedia3Result = tryRunAdvancedMedia3Graph(inputFile, outputFile, finalCommand)
                    
                    val media3Result = if (advancedMedia3Result != null) {
                        advancedMedia3Result
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

                    if (media3Result != null && outputFile.exists() && outputFile.length() > 0L) {
                        var finalOutputFile = outputFile
                        val metadataStr = extractMetadataString(userCommand)
                        var metadataApplied = false

                        // TASK 4: Zero-Copy Metadata Remuxing
                        if (metadataStr.isNotBlank()) {
                            val remuxFile = File(getContext().cacheDir, "hve_metadata_${System.currentTimeMillis()}.mp4")
                            val remuxCmd = "-y -i \"${outputFile.absolutePath}\" -map 0 -c copy $metadataStr \"${remuxFile.absolutePath}\""
                            val session = FFmpegKit.execute(remuxCmd)
                            if (ReturnCode.isSuccess(session.returnCode) && remuxFile.exists() && remuxFile.length() > 0L) {
                                outputFile.delete()
                                finalOutputFile = remuxFile
                                metadataApplied = true
                            }
                        }

                        val result = media3Result.toMutableMap()
                        val publishedUri = publishVideoToMediaStore(finalOutputFile)
                        result["command"] = finalCommand
                        result["originalCommand"] = userCommand
                        result["inputUri"] = resolvedInputUri
                        result["inputName"] = inputFile.name
                        result["outputUri"] = publishedUri?.toString() ?: ""
                        result["gpuRequested"] = true
                        
                        if (metadataApplied) {
                            result["metadataBackend"] = "FFmpegKit Smart Remux (Zero-Copy)"
                        }

                        if (!result.containsKey("hybridMode")) result["hybridMode"] = "GPU_MEDIA3_PIPELINE"
                        if (!result.containsKey("audioBackend")) result["audioBackend"] = "Media3 passthrough"
                        promise.resolve(result)
                        return@AsyncFunction
                    }
                } catch (gpuError: Throwable) {
                    val rootCause = generateSequence(gpuError) { it.cause }.last()
                    val errMsg = rootCause.message ?: rootCause.toString()

                    gpuInfo["gpuFallback"] = true
                    gpuInfo["gpuFallbackReason"] = errMsg
                    gpuInfo["fallbackReasonCode"] = classifyFallbackReason(finalCommand, errMsg)
                    gpuInfo["videoBackend"] = "FFmpegKit Fallback (Media3 Crash: $errMsg)"
                    gpuInfo["executionStatus"] = "GPU_FAILED_FALLING_BACK"
                    gpuInfo["failedEffectsOrReason"] = errMsg
                }

                // Fallback Engine
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
                            "message" to "Universal FFmpeg fallback render completed",
                            "appliedGpuEffects" to "None (Processed completely by CPU/FFmpeg)",
                            "executionStatus" to (gpuInfo["executionStatus"] ?: "BYPASSED_GPU"),
                            "failedEffectsOrReason" to (gpuInfo["failedEffectsOrReason"] ?: "Graph too complex for GPU")
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