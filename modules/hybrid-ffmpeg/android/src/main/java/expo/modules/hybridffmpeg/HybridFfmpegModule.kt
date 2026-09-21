// File: modules/hybrid-ffmpeg/android/src/main/java/expo/modules/hybridffmpeg/HybridFfmpegModule.kt
package expo.modules.hybridffmpeg

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaCodecList
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.io.FileOutputStream

class HybridFfmpegModule : Module() {

    private fun getContext(): Context = requireNotNull(appContext.reactContext)

    private fun isMediaCodecAvailable(): Boolean {
        return try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { type -> type.equals("video/avc", ignoreCase = true) }
            }
        } catch (e: Exception) { false }
    }

    private fun publishVideoToMediaStore(source: File): String? {
        if (!source.exists() || source.length() == 0L) return null
        val resolver = getContext().contentResolver
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
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    override fun definition() = ModuleDefinition {
        Name("HybridFfmpeg")

        Events("onRenderStarted", "onRenderProgress")

        AsyncFunction("probeMedia") { uriString: String, promise: Promise ->
            try {
                val uri = Uri.parse(uriString)
                val safUrl = FFmpegKitConfig.getSafParameterForRead(getContext(), uri)
                val session = FFprobeKit.getMediaInformation(safUrl)
                val info = session.mediaInformation
                
                if (info != null) {
                    val stream = info.streams.firstOrNull { it.type == "video" }
                    promise.resolve(mapOf(
                        "format" to info.format,
                        "duration" to info.duration,
                        "bitrate" to info.bitrate,
                        "size" to info.size,
                        "width" to stream?.width,
                        "height" to stream?.height,
                        "codec" to stream?.codec,
                        "fps" to stream?.averageFrameRate
                    ))
                } else {
                    promise.reject("PROBE_FAILED", "Could not extract media information", null)
                }
            } catch (e: Exception) {
                promise.reject("PROBE_ERROR", e.message, e)
            }
        }

        AsyncFunction("cancelRender") { sessionId: Long, promise: Promise ->
            FFmpegKit.cancel(sessionId)
            promise.resolve(true)
        }

        AsyncFunction("renderVideo") { command: String, commandType: String, inputUriString: String?, promise: Promise ->
            try {
                val context = getContext()
                
                var baseCommand = command.trim()
                if (commandType == "WINDOWS") {
                    baseCommand = WindowsCommandConverter.convert(baseCommand)
                } else {
                    val ffmpegMatch = Regex("(?i)^ffmpeg(?:\\.exe)?\\s+").find(baseCommand)
                    if (ffmpegMatch != null) {
                        baseCommand = baseCommand.substring(ffmpegMatch.range.last + 1).trim()
                    }
                    baseCommand = baseCommand.replace("input.mp4", "<INPUT_VIDEO>")
                    baseCommand = baseCommand.replace("output.mp4", "<OUTPUT_VIDEO>")
                }

                var safeInputPath: String? = null
                if (!inputUriString.isNullOrBlank()) {
                    val uri = Uri.parse(inputUriString)
                    safeInputPath = FFmpegKitConfig.getSafParameterForRead(context, uri)
                }
                
                val outputFile = File(context.cacheDir, "ffmpeg_output_${System.currentTimeMillis()}.mp4")
                
                if (safeInputPath != null) {
                    baseCommand = baseCommand.replace("<INPUT_VIDEO>", "\"$safeInputPath\"")
                }
                
                if (baseCommand.contains("<OUTPUT_VIDEO>")) {
                    baseCommand = baseCommand.replace("<OUTPUT_VIDEO>", "\"${outputFile.absolutePath}\"")
                } else {
                    val tempTokens = FfmpegCommandTokenizer.tokenize(baseCommand).toMutableList()
                    if (tempTokens.isNotEmpty() && !tempTokens.last().startsWith("-") && tempTokens.last().endsWith(".mp4", true)) {
                        tempTokens.removeAt(tempTokens.size - 1)
                        baseCommand = FfmpegCommandTokenizer.buildCommand(tempTokens)
                    }
                    baseCommand = "$baseCommand \"${outputFile.absolutePath}\""
                }

                val assetResult = FfmpegAssetResolver.resolve(baseCommand, context)
                if (assetResult.missingAssets.isNotEmpty()) {
                    promise.resolve(mapOf(
                        "success" to false,
                        "errorType" to "MISSING_EXTERNAL_ASSET",
                        "errorMessage" to "Missing assets: ${assetResult.missingAssets.joinToString(", ")}"
                    ))
                    return@AsyncFunction
                }

                val tokens = FfmpegCommandTokenizer.tokenize(assetResult.command)
                val encoderResult = FfmpegEncoderSelector.applyHardwarePolicy(tokens, isMediaCodecAvailable())
                val finalCommand = FfmpegCommandTokenizer.buildCommand(encoderResult.tokens)

                val session = FFmpegKit.executeAsync(finalCommand, { completedSession ->
                    val returnCode = completedSession.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        val publishedUri = publishVideoToMediaStore(outputFile)
                        outputFile.delete()
                        promise.resolve(mapOf(
                            "success" to true,
                            "sessionId" to completedSession.sessionId,
                            "outputUri" to publishedUri,
                            "normalizedCommand" to finalCommand,
                            "requestedEncoder" to encoderResult.requestedEncoder,
                            "actualEncoder" to encoderResult.actualEncoder,
                            "hardwareEncoderUsed" to encoderResult.hardwareEncoderUsed,
                            "processingBackend" to "FFmpeg CPU",
                            "durationMs" to completedSession.duration
                        ))
                    } else if (ReturnCode.isCancel(returnCode)) {
                        outputFile.delete()
                        promise.resolve(mapOf(
                            "success" to false,
                            "errorType" to "CANCELLED",
                            "errorMessage" to "Render was cancelled by user."
                        ))
                    } else {
                        outputFile.delete()
                        promise.resolve(mapOf(
                            "success" to false,
                            "errorType" to "FFMPEG_PROCESS_FAILURE",
                            "errorMessage" to "FFmpeg execution failed.",
                            "ffmpegLog" to completedSession.allLogsAsString.takeLast(2000)
                        ))
                    }
                }, { log -> }, { stats ->
                    sendEvent("onRenderProgress", mapOf(
                        "sessionId" to stats.sessionId,
                        "frame" to stats.videoFrameNumber,
                        "fps" to stats.videoFps,
                        "speed" to stats.speed,
                        "time" to stats.time,
                        "bitrate" to stats.bitrate,
                        "size" to stats.size
                    ))
                })

                sendEvent("onRenderStarted", mapOf("sessionId" to session.sessionId))

            } catch (error: Exception) {
                promise.resolve(mapOf("success" to false, "errorType" to "UNKNOWN_EXCEPTION", "errorMessage" to error.message))
            }
        }
    }
}