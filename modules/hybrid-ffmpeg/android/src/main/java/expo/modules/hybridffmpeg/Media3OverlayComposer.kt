package expo.modules.hybridffmpeg

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.max

@OptIn(UnstableApi::class)
object Media3OverlayComposer {
    fun render(
        context: Context,
        input: File,
        output: File,
        plan: FfmpegComplexCommandParser.OverlayPlan,
        removeAudio: Boolean,
        externalAudioFile: File? = null
    ): Map<String, Any> {
        require(plan.supported)
        if (output.exists()) output.delete()

        val baseItem = buildItem(input, plan.baseStartSeconds, plan.baseEffects, removeAudio)
        val overlayItem = buildItem(input, plan.overlayStartSeconds, plan.overlayEffects, true)

        val baseSequence = if (removeAudio) {
            EditedMediaItemSequence.withVideoFrom(listOf(baseItem))
        } else {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(baseItem))
        }
        val overlaySequence = EditedMediaItemSequence.withVideoFrom(listOf(overlayItem))

        val sequences = mutableListOf<EditedMediaItemSequence>(baseSequence, overlaySequence)

        // FIXED: Added setIsLooping(true) to prevent Media3 native crash on short audio
        if (externalAudioFile != null && externalAudioFile.exists()) {
            val audioItem = MediaItem.Builder().setUri(Uri.fromFile(externalAudioFile)).build()
            val editedAudio = EditedMediaItem.Builder(audioItem).build()
            val audioSeq = EditedMediaItemSequence.withAudioFrom(listOf(editedAudio))
                .buildUpon()
                .setIsLooping(true)
                .build()
            sequences.add(audioSeq)
        }

        val compositor = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes.first()

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 0) return StaticOverlaySettings.Builder().build()
                val t = presentationTimeUs / 1_000_000.0
                val visible = when (val v = plan.enable) {
                    FfmpegComplexCommandParser.Visibility.Always -> true
                    is FfmpegComplexCommandParser.Visibility.ModGte -> {
                        val r = t % v.period
                        r >= v.start
                    }
                    is FfmpegComplexCommandParser.Visibility.ModLtAndGte -> {
                        val r = t % v.period
                        r < v.end && t >= v.minTime
                    }
                }
                return StaticOverlaySettings.Builder()
                    .setAlphaScale(if (visible) 1f else 0f)
                    .setOverlayFrameAnchor(-1f, 1f)
                    .setBackgroundFrameAnchor(-1f, 1f)
                    .build()
            }
        }

        val composition = Composition.Builder(sequences)
            .setVideoCompositorSettings(compositor)
            .build()

        val lock = Object()
        var result: Map<String, Any>? = null
        var failure: Throwable? = null

        val thread = android.os.HandlerThread("HVE-Media3-Composition").apply { start() }
        val handler = android.os.Handler(thread.looper)
        handler.post {
            try {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        synchronized(lock) {
                            result = mapOf(
                                "success" to true,
                                "outputPath" to output.absolutePath,
                                "backend" to "MEDIA3_TRANSFORMER_COMPOSITION_OPENGL",
                                "videoBackend" to "Media3 Composition + OpenGL ES",
                                "videoEncoder" to (exportResult.videoEncoderName ?: "unknown MediaCodec encoder"),
                                "videoMimeType" to (exportResult.videoMimeType ?: MimeTypes.VIDEO_H264),
                                "gpuPipeline" to "MediaCodec decoders -> OpenGL ES effects -> Media3 video compositor -> MediaCodec encoder",
                                "compositionMode" to "TWO_VIDEO_SEQUENCES_OVERLAY",
                                "overlayVisibility" to plan.enable::class.simpleName.orEmpty(),
                                "audioComposition" to if (externalAudioFile != null) "MEDIA3_COMPOSITION_AUDIO_SEQUENCE" else "NONE"
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
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(listener)
                    .build()
                    .start(composition, output.absolutePath)
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

    private fun buildItem(
        input: File,
        startSeconds: Double,
        specs: List<FfmpegVideoCommandParser.EffectSpec>,
        removeAudio: Boolean
    ): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(max(0L, (startSeconds * 1000.0).toLong()))
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(input))
            .setClippingConfiguration(clip)
            .build()
        val effects = specs.mapNotNull { specToEffect(it) }
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(androidx.media3.transformer.Effects(emptyList(), effects))
            .setRemoveAudio(removeAudio)
            .build()
    }

    private fun specToEffect(spec: FfmpegVideoCommandParser.EffectSpec): Effect? {
        return when (spec) {
            FfmpegVideoCommandParser.EffectSpec.HFlip -> androidx.media3.effect.ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
            FfmpegVideoCommandParser.EffectSpec.VFlip -> androidx.media3.effect.ScaleAndRotateTransformation.Builder().setScale(1f, -1f).build()
            FfmpegVideoCommandParser.EffectSpec.Negate -> androidx.media3.effect.RgbFilter.createInvertedFilter()
            is FfmpegVideoCommandParser.EffectSpec.ScaleFactor -> androidx.media3.effect.ScaleAndRotateTransformation.Builder().setScale(spec.x, spec.y).build()
            is FfmpegVideoCommandParser.EffectSpec.ScaleSize -> androidx.media3.effect.Presentation.createForWidthAndHeight(spec.width, spec.height, androidx.media3.effect.Presentation.LAYOUT_STRETCH_TO_FIT)
            is FfmpegVideoCommandParser.EffectSpec.Rotate -> androidx.media3.effect.ScaleAndRotateTransformation.Builder().setRotationDegrees(spec.degrees).build()
            is FfmpegVideoCommandParser.EffectSpec.GaussianBlur -> androidx.media3.effect.GaussianBlur(spec.sigma)
            is FfmpegVideoCommandParser.EffectSpec.CropFactor -> {
                val left = -1f + (2f * spec.xFactor)
                val right = left + (2f * spec.widthFactor)
                val top = 1f - (2f * spec.yFactor)
                val bottom = top - (2f * spec.heightFactor)
                androidx.media3.effect.Crop(left, right, bottom, top)
            }
            is FfmpegVideoCommandParser.EffectSpec.DynamicCrop -> {
                DynamicCropEffect(spec.widthDivisor, spec.heightDivisor, spec.xFreq, spec.yFreq)
            }
            else -> null 
        }
    }
}
