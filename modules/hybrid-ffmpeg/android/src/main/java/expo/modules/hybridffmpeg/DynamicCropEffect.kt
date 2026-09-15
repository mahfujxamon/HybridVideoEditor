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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
) : BaseGlShaderProgram(false, 1) {

    private val glProgram: GlProgram
    private val vertexBuffer: FloatBuffer

    init {
        // 4 Quad coordinates covering full screen: [-1, -1] to [1, 1]
        val quadCoords = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 0.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 0.0f, 1.0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadCoords)
                position(0)
            }

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
                float safeW = uWidthDiv > 0.0 ? uWidthDiv : 1.0;
                float safeH = uHeightDiv > 0.0 ? uHeightDiv : 1.0;
                float cropW = 1.0 / safeW;
                float cropH = 1.0 / safeH;
                
                float maxOffsetX = 1.0 - cropW;
                float maxOffsetY = 1.0 - cropH;
                if (maxOffsetX < 0.0) maxOffsetX = 0.0;
                if (maxOffsetY < 0.0) maxOffsetY = 0.0;

                float offsetX = (maxOffsetX * 0.5) + (maxOffsetX * 0.5) * sin(uTime * uXFreq);
                float offsetY = (maxOffsetY * 0.5) + (maxOffsetY * 0.5) * sin(uTime * uYFreq);

                vec2 uv = vec2(
                    offsetX + vTexSamplingCoord.x * cropW,
                    offsetY + vTexSamplingCoord.y * cropH
                );

                if (uv.x < 0.0) uv.x = 0.0;
                if (uv.x > 1.0) uv.x = 1.0;
                if (uv.y < 0.0) uv.y = 0.0;
                if (uv.y > 1.0) uv.y = 1.0;

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
            
            // BIND VERTEX BUFFER (Fixes the crash)
            glProgram.setBufferAttribute("aFramePosition", vertexBuffer, 4)

            try { glProgram.setFloatUniform("uTime", presentationTimeUs / 1_000_000f) } catch(_: Exception){}
            try { glProgram.setFloatUniform("uWidthDiv", widthDivisor) } catch(_: Exception){}
            try { glProgram.setFloatUniform("uHeightDiv", heightDivisor) } catch(_: Exception){}
            try { glProgram.setFloatUniform("uXFreq", xFreq) } catch(_: Exception){}
            try { glProgram.setFloatUniform("uYFreq", yFreq) } catch(_: Exception){}
            
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
