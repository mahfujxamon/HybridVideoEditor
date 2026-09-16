package expo.modules.hybridffmpeg

import android.content.Context
import android.net.Uri
import android.os.HandlerThread
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi
import java.io.File

@OptIn(UnstableApi::class)
object Media3VideoTransformer {
    
    fun render(
        context: Context,
        input: File,
        output: File,
        plan: FfmpegVideoCommandParser.VideoPlan,
        removeAudio: Boolean = false,
        externalAudioFile: File? = null,
    ): Map<String, Any> {
        val thread = HandlerThread("HVE-Media3").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val lock = Object()
        var result: Map<String, Any>? = null
        var failure: Throwable? = null

        handler.post {
            try {
                val effects = buildEffects(plan.effects) // Strict Building
                val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(input)).build()
                val edited = EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), effects)).setRemoveAudio(removeAudio).build()

                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                            synchronized(lock) {
                                result = mapOf("success" to true, "outputPath" to output.absolutePath)
                                lock.notifyAll()
                            }
                        }
                        override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
                            synchronized(lock) { failure = exportException; lock.notifyAll() }
                        }
                    })
                    .build()
                    .start(edited, output.absolutePath)
            } catch (t: Throwable) {
                synchronized(lock) { failure = t; lock.notifyAll() }
            }
        }

        synchronized(lock) { while (result == null && failure == null) lock.wait() }
        thread.quitSafely()
        failure?.let { throw it }
        return requireNotNull(result)
    }

    private fun buildEffects(specs: List<FfmpegVideoCommandParser.EffectSpec>): List<Effect> {
        val out = mutableListOf<Effect>()
        for (spec in specs) {
            when (spec) {
                FfmpegVideoCommandParser.EffectSpec.HFlip -> out += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
                FfmpegVideoCommandParser.EffectSpec.VFlip -> out += ScaleAndRotateTransformation.Builder().setScale(1f, -1f).build()
                FfmpegVideoCommandParser.EffectSpec.Negate -> out += RgbFilter.createInvertedFilter()
                is FfmpegVideoCommandParser.EffectSpec.DarEffectSpec -> out += DarEffect(spec.ratio)
                is FfmpegVideoCommandParser.EffectSpec.ScaleFactor -> out += ScaleAndRotateTransformation.Builder().setScale(spec.x, spec.y).build()
                is FfmpegVideoCommandParser.EffectSpec.GaussianBlur -> out += GaussianBlur(spec.sigma)
                is FfmpegVideoCommandParser.EffectSpec.DynamicCrop -> out += DynamicCropEffect(spec.widthDivisor, spec.heightDivisor, spec.xFreq, spec.yFreq)
                
                // TASK 5: Custom AI Shader loader with strict error throwing
                is FfmpegVideoCommandParser.EffectSpec.AiCustomShader -> {
                    try {
                        val shaderCode = File(spec.shaderPath).readText()
                        out += CustomAiShaderEffect(shaderCode)
                    } catch (e: Exception) {
                        throw Exception("Failed to read AI Shader file at ${spec.shaderPath}", e)
                    }
                }
                else -> throw Exception("Builder encountered unmapped spec: ${spec::class.simpleName}")
            }
        }
        return out
    }
}