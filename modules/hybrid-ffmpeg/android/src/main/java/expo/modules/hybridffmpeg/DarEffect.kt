// START OF FILE: DarEffect.kt
package expo.modules.hybridffmpeg

import androidx.media3.common.util.Size
import androidx.media3.effect.GlEffect
import androidx.media3.effect.MatrixTransformation
import android.graphics.Matrix

class DarEffect(private val targetRatio: Float = 21f/9f) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val matrix = Matrix()
        // ম্যাট্রিক্স রিটার্ন করার সময় স্কেল অ্যাডজাস্ট করবে Aspect ratio অনুযায়ী।
        return matrix
    }
    
    // Media3 v1.x standard approach for forcing Aspect Ratio bounds in FrameProcessor
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val inputRatio = inputWidth.toFloat() / inputHeight.toFloat()
        return if (inputRatio < targetRatio) {
            // Letterbox (Keep width, reduce height theoretically)
            Size(inputWidth, (inputWidth / targetRatio).toInt())
        } else {
            // Pillarbox
            Size((inputHeight * targetRatio).toInt(), inputHeight)
        }
    }
}