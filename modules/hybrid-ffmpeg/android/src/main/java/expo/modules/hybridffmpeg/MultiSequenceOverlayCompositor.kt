// START OF FILE: MultiSequenceOverlayCompositor.kt
package expo.modules.hybridffmpeg

import android.content.Context
import android.net.Uri
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
object MultiSequenceOverlayCompositor {

    data class OverlayNode(
        val enableExpression: String,
        val zIndex: Int, // Input ID order
        val editedItem: EditedMediaItem
    )

    fun renderMultiGraph(
        context: Context,
        outputFile: File,
        baseItem: EditedMediaItem,
        overlayNodes: List<OverlayNode>, // Contains 1 to N overlays!
        externalAudioFile: File?
    ): Map<String, Any> {
        
        if (outputFile.exists()) outputFile.delete()

        // 1. Base Sequence
        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence.withVideoFrom(listOf(baseItem)))

        // 2. Multi Overlay Sequences
        overlayNodes.sortedBy { it.zIndex }.forEach { node ->
            sequences.add(EditedMediaItemSequence.withVideoFrom(listOf(node.editedItem)))
        }

        // 3. Audio Background Sequence Loop (For bgX.mp4 extracted .m4a)
        if (externalAudioFile != null && externalAudioFile.exists()) {
            val audioItem = MediaItem.Builder().setUri(Uri.fromFile(externalAudioFile)).build()
            val audioSeq = EditedMediaItemSequence.withAudioFrom(listOf(EditedMediaItem.Builder(audioItem).build()))
                .buildUpon()
                .setIsLooping(true) // Extremely important for repeat/preload architectures
                .build()
            sequences.add(audioSeq)
        }

        // 4. Multi-Layer Dynamic Compositor
        val compositorSettings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes.first()

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 0) return StaticOverlaySettings.Builder().build() // Base video is always 100% visible

                // Input ID matches the index in sequences minus 1 (for video layers)
                val overlayIndex = inputId - 1
                if (overlayIndex < overlayNodes.size) {
                    val node = overlayNodes[overlayIndex]
                    val tSeconds = presentationTimeUs / 1_000_000.0
                    val isVisible = AdvancedFfmpegMathCompiler.evaluateTimeVisibility(node.enableExpression, tSeconds)
                    
                    return StaticOverlaySettings.Builder()
                        .setAlphaScale(if (isVisible) 1f else 0f)
                        .setOverlayFrameAnchor(-1f, 1f)
                        .setBackgroundFrameAnchor(-1f, 1f)
                        .build()
                }
                
                return StaticOverlaySettings.Builder().build()
            }
        }

        val composition = Composition.Builder(sequences)
            .setVideoCompositorSettings(compositorSettings)
            .build()

        return executeTransformerBlock(context, composition, outputFile, "MULTI_ZOOM_OR_COMPLEX_GRAPH")
    }

    private fun executeTransformerBlock(context: Context, composition: Composition, output: File, mode: String): Map<String, Any> {
        val lock = Object()
        var result: Map<String, Any>? = null
        var failure: Throwable? = null

        val thread = android.os.HandlerThread("HVE-MultiCompositor").apply { start() }
        val handler = android.os.Handler(thread.looper)
        
        handler.post {
            try {
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(comp: Composition, exportResult: ExportResult) {
                            synchronized(lock) {
                                result = mapOf(
                                    "success" to true,
                                    "outputPath" to output.absolutePath,
                                    "videoBackend" to "Media3 GPU Multi-Sequence Composition",
                                    "compositionMode" to mode,
                                    "hybridMode" to "GPU_MULTI_GRAPH"
                                )
                                lock.notifyAll()
                            }
                        }
                        override fun onError(comp: Composition, exportResult: ExportResult, exportException: ExportException) {
                            synchronized(lock) {
                                failure = exportException
                                lock.notifyAll()
                            }
                        }
                    })
                    .build()
                    .start(composition, output.absolutePath)
            } catch (e: Throwable) {
                synchronized(lock) { failure = e; lock.notifyAll() }
            }
        }

        synchronized(lock) { while (result == null && failure == null) lock.wait() }
        thread.quitSafely()
        failure?.let { throw Exception("MultiSequence Composition Failed: ${it.message}", it) }
        return result!!
    }
}