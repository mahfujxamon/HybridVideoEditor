package expo.modules.hybridffmpeg

object FfmpegVideoCommandParser {
    class VideoPlan(
        val supported: Boolean,
        val reason: String? = null,
        val sourceFilter: String? = null,
        val effects: List<EffectSpec> = emptyList()
    )

    sealed class EffectSpec {
        object HFlip : EffectSpec()
        object VFlip : EffectSpec()
        object Negate : EffectSpec()
        class ScaleFactor(val x: Float, val y: Float) : EffectSpec()
        class ScaleSize(val width: Int, val height: Int) : EffectSpec()
        class CropFactor(val xFactor: Float, val widthFactor: Float, val yFactor: Float, val heightFactor: Float) : EffectSpec()
        class CropPixels(val x: Float, val width: Float, val y: Float, val height: Float) : EffectSpec()
        class Rotate(val degrees: Float) : EffectSpec()
        class GaussianBlur(val sigma: Float) : EffectSpec()
        class DynamicCrop(val widthDivisor: Float, val heightDivisor: Float, val xFreq: Float, val yFreq: Float) : EffectSpec()
        
        // AI Shader Receiver
        class AiCustomShader(val shaderPath: String) : EffectSpec()
    }

    fun parse(command: String): VideoPlan {
        val filter = FfmpegCommandTokenizer.findOptionValue(command, "-vf") ?: return VideoPlan(false, "No -vf flag")
        
        val effectsList = mutableListOf<EffectSpec>()
        
        // AI শেডার কমান্ড ডিটেক্ট করা হচ্ছে
        if (filter.contains("ai_shader=")) {
            val path = Regex("ai_shader=([^,]+)").find(filter)?.groupValues?.get(1) ?: ""
            if (path.isNotEmpty()) {
                effectsList.add(EffectSpec.AiCustomShader(path))
            }
        }
        
        return VideoPlan(true, null, filter, effectsList)
    }
}
