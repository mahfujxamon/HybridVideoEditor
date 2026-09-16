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
import androidx.media3.transformer.Transformer
import java.io.File

@OptIn(UnstableApi::class)
object MultiSequenceOverlayCompositor {

    data class OverlayNode(val enableExpression: String, val zIndex: Int, val editedItem: EditedMediaItem)

    fun renderMultiGraph(context: Context, outputFile: File, baseItem: EditedMediaItem, overlayNodes: List<OverlayNode>, externalAudioFile: File?): Map<String, Any> {
        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence.withVideoFrom(listOf(baseItem)))

        overlayNodes.sortedBy { it.zIndex }.forEach { node ->
            sequences.add(EditedMediaItemSequence.withVideoFrom(listOf(node.editedItem)))
        }

        if (externalAudioFile != null && externalAudioFile.exists()) {
            val audioItem = MediaItem.Builder().setUri(Uri.fromFile(externalAudioFile)).build()
            sequences.add(EditedMediaItemSequence.withAudioFrom(listOf(EditedMediaItem.Builder(audioItem).build())).buildUpon().setIsLooping(true).build())
        }

        val compositorSettings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes.firstOrNull() ?: Size(1920, 1080)

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 0) return StaticOverlaySettings.Builder().build() 

                val overlayIndex = inputId - 1
                if (overlayIndex < overlayNodes.size) {
                    val node = overlayNodes[overlayIndex]
                    val tSeconds = presentationTimeUs / 1_000_000.0 // TASK 3: Dynamic Time Evaluation Per Frame Intact
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

        val composition = Composition.Builder(sequences).setVideoCompositorSettings(compositorSettings).build()
        // (Execution code remains structurally the same, omitted for brevity, ensure you keep your execute block here)
        return mapOf("success" to true) // Return mock here, map it inside your implementation block
    }
}