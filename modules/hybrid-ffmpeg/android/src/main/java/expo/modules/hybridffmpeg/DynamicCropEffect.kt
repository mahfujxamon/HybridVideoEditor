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

/**
 * GPU-only dynamic crop effect for FFmpeg expressions of the form used by the
 * reference commands, e.g. sin(t*0.5) / sin(t*0.2).
 *
 * The frame remains a Media3 GL texture. presentationTimeUs is converted to
 * seconds and supplied as a uniform; no pixel data is read back to the CPU.
 */
class DynamicCropEffect(
    private val widthDivisor: Float,
    private val heightDivisor: Float,
    private val xFreq: Float,
    private val yFreq: Float,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DynamicCropShaderProgram(context, widthDivisor, heightDivisor, xFreq, yFreq)
    }
}

private class DynamicCropShaderProgram(
    context: Context,
    private val widthDivisor: Float,
    private val heightDivisor: Float,
    private val xFreq: Float,
    private val yFreq: Float,
) : BaseGlShaderProgram(
    useHighPrecisionColorComponents = false,
    texturePoolCapacity = 1,
) {

    private val glProgram: GlProgram

    init {
        // Media3's GlProgram pipeline is GLES2-oriented. Do not use #version
        // 300 es / in / out here; those can fail on devices/driver paths where
        // the Media3 shader contract is GLES2.
        val vertexShader = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = (aFramePosition.xy + vec2(1.0, 1.0)) * 0.5;
            }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform float uTime;
            uniform float uWidthDiv;
            uniform float uHeightDiv;
            uniform float uXFreq;
            uniform float uYFreq;
            varying vec2 vTexSamplingCoord;

            void main() {
                float safeW = max(uWidthDiv, 1.0001);
                float safeH = max(uHeightDiv, 1.0001);
                float cropW = 1.0 / safeW;
                float cropH = 1.0 / safeH;
                float maxOffsetX = max(0.0, 1.0 - cropW);
                float maxOffsetY = max(0.0, 1.0 - cropH);

                float offsetX = (maxOffsetX * 0.5) +
                    (maxOffsetX * 0.5) * sin(uTime * uXFreq);
                float offsetY = (maxOffsetY * 0.5) +
                    (maxOffsetY * 0.5) * sin(uTime * uYFreq);

                vec2 uv = vec2(
                    offsetX + vTexSamplingCoord.x * cropW,
                    offsetY + vTexSamplingCoord.y * cropH
                );

                uv = clamp(uv, 0.0, 1.0);
                gl_FragColor = texture2D(uTexSampler, uv);
            }
        """.trimIndent()

        glProgram = try {
            GlProgram(context, vertexShader, fragmentShader)
        } catch (t: Throwable) {
            throw VideoFrameProcessingException(t)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(texId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", texId, 0)
            glProgram.setFloatUniform("uTime", presentationTimeUs / 1_000_000f)
            glProgram.setFloatUniform("uWidthDiv", widthDivisor)
            glProgram.setFloatUniform("uHeightDiv", heightDivisor)
            glProgram.setFloatUniform("uXFreq", xFreq)
            glProgram.setFloatUniform("uYFreq", yFreq)
            glProgram.bindAttributesAndUniforms()
            GlUtil.clearOutputFrame()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (t: Throwable) {
            throw VideoFrameProcessingException(t)
        }
    }

    override fun release() {
        try {
            glProgram.delete()
        } finally {
            super.release()
        }
    }
}
