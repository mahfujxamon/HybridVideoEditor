package expo.modules.hybridffmpeg

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.HandlerThread
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi

/**
 * Primary GPU video backend.
 *
 * Media3 Transformer owns the decode -> OpenGL effect graph -> MediaCodec encode
 * pipeline. No application-level CPU pixel readback/upload is performed here.
 */
@OptIn(UnstableApi::class)
object Media3VideoTransformer {
    private const val TAG = "HVE-Media3"

    fun render(
        context: Context,
        input: java.io.File,
        output: java.io.File,
        plan: FfmpegVideoCommandParser.VideoPlan,
        removeAudio: Boolean = false,
        externalAudioFile: java.io.File? = null,
    ): Map<String, Any> {
        require(plan.supported) { "Media3 plan is not supported" }
        if (output.exists()) output.delete()

        val thread = HandlerThread(TAG).apply { start() }
        val handler = android.os.Handler(thread.looper)
        val lock = Object()
        var result: Map<String, Any>? = null
        var failure: Throwable? = null

        handler.post {
            try {
                val effects = buildEffects(plan.effects, input)
                val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(input)).build()
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(emptyList(), effects))
                    .setRemoveAudio(removeAudio || externalAudioFile != null)
                    .build()

                val composition = if (externalAudioFile != null && externalAudioFile.exists()) {
                    val videoSequence = EditedMediaItemSequence.withVideoFrom(listOf(edited))
                    val audioItem = EditedMediaItem.Builder(
                        MediaItem.Builder().setUri(Uri.fromFile(externalAudioFile)).build()
                    ).build()
                    val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
                        .buildUpon()
                        .setIsLooping(true)
                        .build()
                    Composition.Builder(videoSequence, audioSequence).build()
                } else {
                    null
                }

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        synchronized(lock) {
                            result = mapOf(
                                "success" to true,
                                "outputPath" to output.absolutePath,
                                "backend" to "MEDIA3_TRANSFORMER_OPENGL",
                                "videoBackend" to "Media3 Transformer + OpenGL ES",
                                "videoEncoder" to (exportResult.videoEncoderName ?: "unknown MediaCodec encoder"),
                                "videoMimeType" to (exportResult.videoMimeType ?: MimeTypes.VIDEO_H264),
                                "videoConversionProcess" to exportResult.videoConversionProcess,
                                "videoFrameCount" to exportResult.videoFrameCount,
                                "width" to exportResult.width,
                                "height" to exportResult.height,
                                "fileSizeBytes" to exportResult.fileSizeBytes,
                                "gpuEffects" to plan.effects.map { it::class.simpleName ?: "effect" },
                                "gpuPipeline" to "MediaCodec decoder -> OpenGL ES effects -> MediaCodec encoder",
                                "audioComposition" to if (externalAudioFile != null) "MEDIA3_COMPOSITION_AUDIO_SEQUENCE" else "NONE",
                                "finalMux" to "Media3 Transformer muxer"
                            )
                            lock.notifyAll()
                        }
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        synchronized(lock) {
                            failure = exportException
                            lock.notifyAll()
                        }
                    }
                }

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(listener)
                    .build()
                if (composition != null) {
                    transformer.start(composition, output.absolutePath)
                } else {
                    transformer.start(edited, output.absolutePath)
                }
            } catch (t: Throwable) {
                synchronized(lock) {
                    failure = t
                    lock.notifyAll()
                }
            }
        }

        synchronized(lock) {
            while (result == null && failure == null) lock.wait()
        }
        thread.quitSafely()
        failure?.let { throw it }
        return requireNotNull(result)
    }

    private fun buildEffects(specs: List<FfmpegVideoCommandParser.EffectSpec>, input: java.io.File): List<Effect> {
        val out = mutableListOf<Effect>()
        for (spec in specs) {
            when (spec) {
                FfmpegVideoCommandParser.EffectSpec.HFlip -> out += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
                FfmpegVideoCommandParser.EffectSpec.VFlip -> out += ScaleAndRotateTransformation.Builder().setScale(1f, -1f).build()
                FfmpegVideoCommandParser.EffectSpec.Negate -> out += RgbFilter.createInvertedFilter()
                is FfmpegVideoCommandParser.EffectSpec.ScaleFactor -> out += ScaleAndRotateTransformation.Builder().setScale(spec.x, spec.y).build()
                is FfmpegVideoCommandParser.EffectSpec.ScaleSize -> out += Presentation.createForWidthAndHeight(
                    spec.width, spec.height, Presentation.LAYOUT_STRETCH_TO_FIT
                )
                is FfmpegVideoCommandParser.EffectSpec.CropFactor -> {
                    val left = -1f + (2f * spec.xFactor)
                    val right = left + (2f * spec.widthFactor)
                    val top = 1f - (2f * spec.yFactor)
                    val bottom = top - (2f * spec.heightFactor)
                    out += Crop(left, right, bottom, top)
                }
                is FfmpegVideoCommandParser.EffectSpec.CropPixels -> {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(input.absolutePath)
                    val iw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
                        ?: throw IllegalArgumentException("Cannot read input width for crop")
                    val ih = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
                        ?: throw IllegalArgumentException("Cannot read input height for crop")
                    retriever.release()
                    val left = -1f + (2f * spec.x / iw)
                    val right = -1f + (2f * (spec.x + spec.width) / iw)
                    val bottom = 1f - (2f * (spec.y + spec.height) / ih)
                    val top = 1f - (2f * spec.y / ih)
                    out += Crop(left, right, bottom, top)
                }
                is FfmpegVideoCommandParser.EffectSpec.Rotate -> out += ScaleAndRotateTransformation.Builder().setRotationDegrees(spec.degrees).build()
                is FfmpegVideoCommandParser.EffectSpec.GaussianBlur -> out += GaussianBlur(spec.sigma)
            }
        }
        return out
    }
}
