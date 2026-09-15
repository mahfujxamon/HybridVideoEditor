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

class DynamicCropEffect(
    private val widthDivisor: Float,
    private val heightDivisor: Float,
    private val xFreq: Float,
    private val yFreq: Float
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DynamicCropShaderProgram(widthDivisor, heightDivisor, xFreq, yFreq)
    }
}

private class DynamicCropShaderProgram(
    private val widthDivisor: Float,
    private val heightDivisor: Float,
    private val xFreq: Float,
    private val yFreq: Float
) : BaseGlShaderProgram(false, 1) {

    private val glProgram: GlProgram
    private val quadCoords = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)

    init {
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
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
            }
        """.trimIndent()

        glProgram = try { GlProgram(vertexShader, fragmentShader) } catch (t: Throwable) { throw VideoFrameProcessingException(t) }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(texId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", texId, 0)
            glProgram.setBufferAttribute("aFramePosition", quadCoords, 4)
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
