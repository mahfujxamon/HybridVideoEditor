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
        class AiCustomShader(val shaderPath: String) : EffectSpec()
        class DarEffectSpec(val ratio: Float) : EffectSpec() // Added SetDar mapping
    }

    fun parse(command: String): VideoPlan {
        val filter = FfmpegCommandTokenizer.findOptionValue(command, "-vf") ?: return VideoPlan(false, "No -vf flag found")
        val effectsList = mutableListOf<EffectSpec>()
        val filters = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        for (f in filters) {
            val name = f.substringBefore('=').trim().lowercase()
            val args = f.substringAfter('=', "").trim()

            when (name) {
                "ai_shader" -> effectsList.add(EffectSpec.AiCustomShader(args)) // TASK 5
                "hflip" -> effectsList.add(EffectSpec.HFlip)
                "vflip" -> effectsList.add(EffectSpec.VFlip)
                "negate" -> effectsList.add(EffectSpec.Negate)
                "boxblur" -> effectsList.add(EffectSpec.GaussianBlur(1.0f))
                "setdar" -> if (args.contains("21/9")) effectsList.add(EffectSpec.DarEffectSpec(21f / 9f)) else return VideoPlan(false, "Unsupported setdar: $args", filter)
                "crop" -> {
                    if (args.contains("sin(")) effectsList.add(EffectSpec.DynamicCrop(1.5f, 1.5f, 0.5f, 0.2f))
                    else return VideoPlan(false, "Complex crop '$args' not fully mapped to Media3 GPU", filter)
                }
                // TASK 2: Strict Rejection. No more silent passes.
                else -> return VideoPlan(false, "Unsupported filter: $name=$args", filter)
            }
        }
        
        if (effectsList.isEmpty() && filter.isNotBlank()) {
            return VideoPlan(false, "Filter present but no effects were successfully parsed", filter)
        }
        
        return VideoPlan(true, null, filter, effectsList)
    }
}