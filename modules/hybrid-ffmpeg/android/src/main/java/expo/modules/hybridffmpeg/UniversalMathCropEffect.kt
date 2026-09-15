// START OF FILE: UniversalMathCropEffect.kt
package expo.modules.hybridffmpeg

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

class UniversalMathCropEffect(
    private val widthDivisor: Float,
    private val heightDivisor: Float,
    private val glslXMath: String,
    private val glslYMath: String
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return UniversalMathCropShaderProgram(widthDivisor, heightDivisor, glslXMath, glslYMath)
    }
}

private class UniversalMathCropShaderProgram(
    private val widthDivisor: Float,
    private val heightDivisor: Float,
    private val glslXMath: String,
    private val glslYMath: String
) : BaseGlShaderProgram(false, 1) {

    private val glProgram: GlProgram

    init {
        val vertexShader = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = (aFramePosition.xy + vec2(1.0, 1.0)) * 0.5;
            }
        """.trimIndent()

        // ডায়নামিক গাণিতিক ইকুয়েশন ইনজেক্ট করা হচ্ছে
        val fragmentShader = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform float uTime;
            uniform float uWidthDiv;
            uniform float uHeightDiv;
            varying vec2 vTexSamplingCoord;

            void main() {
                float safeW = uWidthDiv > 0.0 ? uWidthDiv : 1.0;
                float safeH = uHeightDiv > 0.0 ? uHeightDiv : 1.0;
                float uCropW = 1.0 / safeW;
                float uCropH = 1.0 / safeH;
                
                // AdvancedFfmpegMathCompiler থেকে জেনারেট করা ডায়নামিক ম্যাথ 
                float offsetX = $glslXMath;
                float offsetY = $glslYMath;

                vec2 uv = vec2(
                    offsetX + vTexSamplingCoord.x * uCropW,
                    offsetY + vTexSamplingCoord.y * uCropH
                );

                if (uv.x < 0.0) uv.x = 0.0;
                if (uv.x > 1.0) uv.x = 1.0;
                if (uv.y < 0.0) uv.y = 0.0;
                if (uv.y > 1.0) uv.y = 1.0;

                gl_FragColor = texture2D(uTexSampler, uv);
            }
        """.trimIndent()

        glProgram = try {
            GlProgram(vertexShader, fragmentShader)
        } catch (t: Throwable) {
            throw VideoFrameProcessingException(t)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(texId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", texId, 0)
            
            // Standard Full-screen Quad
            val quadCoords = floatArrayOf(-1.0f, -1.0f, 0.0f, 1.0f, 1.0f, -1.0f, 0.0f, 1.0f, -1.0f,  1.0f, 0.0f, 1.0f, 1.0f,  1.0f, 0.0f, 1.0f)
            glProgram.setBufferAttribute("aFramePosition", quadCoords, 4)

            glProgram.setFloatUniform("uTime", presentationTimeUs / 1_000_000f)
            glProgram.setFloatUniform("uWidthDiv", widthDivisor)
            glProgram.setFloatUniform("uHeightDiv", heightDivisor)
            
            glProgram.bindAttributesAndUniforms()
            
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (t: Throwable) {
            throw VideoFrameProcessingException(t)
        }
    }

    override fun release() {
        super.release()
        try { glProgram.delete() } catch(_: Exception){}
    }
}