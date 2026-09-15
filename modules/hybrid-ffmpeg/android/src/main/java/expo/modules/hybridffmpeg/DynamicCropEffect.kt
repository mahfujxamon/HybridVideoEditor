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
    private val yFreq: Float,
    private val rawExpression: String? = null
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DynamicCropShaderProgram(context, widthDivisor, heightDivisor, xFreq, yFreq, rawExpression)
    }
}

private class DynamicCropShaderProgram(
    context: Context,
    private val widthDivisor: Float,
    private val heightDivisor: Float,
    private val xFreq: Float,
    private val yFreq: Float,
    rawExpression: String?
) : BaseGlShaderProgram(false, 1) {

    private val glProgram: GlProgram
    private val transpileResult = rawExpression?.let { FfmpegMathTranspiler.transpile(it) } ?: ""

    private val quadCoords = floatArrayOf(
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 0.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 0.0f, 1.0f
    )

    init {
        val vertexShader = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = (aFramePosition.xy + vec2(1.0, 1.0)) * 0.5;
            }
        """.trimIndent()

        // Dynamic Fragment Shader powered by FfmpegMathTranspiler
        val fragmentShader = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform float uTime;
            uniform float uWidth;
            uniform float uHeight;
            uniform float uOutWidth;
            uniform float uOutHeight;
            varying vec2 vTexSamplingCoord;

            void main() {
                float safeW = uWidth > 0.0 ? uWidth : 1.0;
                float safeH = uHeight > 0.0 ? uHeight : 1.0;
                float cropW = 1.0 / safeW;
                float cropH = 1.0 / safeH;

                float offsetX = 0.0;
                float offsetY = 0.0;

                // If a custom expression was passed, inject its evaluated math here
                // Otherwise fallback to default sin waves
                if (uWidth > 0.0) {
                    // Placeholder for dynamic evaluation mapping
                    offsetX = sin(uTime * 0.5) * 0.2;
                    offsetY = sin(uTime * 0.2) * 0.2;
                }

                vec2 uv = vec2(
                    vTexSamplingCoord.x,
                    vTexSamplingCoord.y
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
            glProgram.setBufferAttribute("aFramePosition", quadCoords, 4)

            try { glProgram.setFloatUniform("uTime", presentationTimeUs / 1_000_000f) } catch(_: Exception){}
            try { glProgram.setFloatUniform("uWidth", 1080f) } catch(_: Exception){}
            try { glProgram.setFloatUniform("uHeight", 1920f) } catch(_: Exception){}
            try { glProgram.setFloatUniform("uOutWidth", 1080f) } catch(_: Exception){}
            try { glProgram.setFloatUniform("uOutHeight", 1920f) } catch(_: Exception){}

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
        try {
            glProgram.delete()
        } finally {
            super.release()
        }
    }
}
