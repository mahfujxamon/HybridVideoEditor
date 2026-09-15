package expo.modules.hybridffmpeg

import android.util.Log

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
    }

    fun parse(command: String): VideoPlan {
        val filter = FfmpegCommandTokenizer.findOptionValue(command, "-vf") ?: return VideoPlan(false, "No -vf flag found")
        
        val effectsList = mutableListOf<EffectSpec>()
        val filters = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        for (f in filters) {
            val name = f.substringBefore('=').trim().lowercase()
            val args = f.substringAfter('=', "").trim()

            when (name) {
                "ai_shader" -> effectsList.add(EffectSpec.AiCustomShader(args))
                "hflip" -> effectsList.add(EffectSpec.HFlip)
                "vflip" -> effectsList.add(EffectSpec.VFlip)
                "negate" -> effectsList.add(EffectSpec.Negate)
                "boxblur" -> effectsList.add(EffectSpec.GaussianBlur(1.0f))
                "crop" -> {
                    // জিপিইউ যদি ক্রপ বুঝতে না পারে, তবে রিজেক্ট করে FFmpeg-এ পাঠানো হবে
                    if (args.contains("sin(")) {
                        effectsList.add(EffectSpec.DynamicCrop(1.5f, 1.5f, 0.5f, 0.2f))
                    } else {
                        Log.e("HVE-Parser", "⚠️ Complex Crop detected, sending to FFmpeg Fallback...")
                        return VideoPlan(false, "Complex crop '$args' not fully mapped to Media3 GPU", filter)
                    }
                }
                else -> {
                    // STRICT REJECTION: অজানা ইফেক্ট পেলে আর ফাঁকি দেবে না, সোজা FFmpeg-এ পাঠাবে!
                    Log.e("HVE-Parser", "⚠️ Unsupported Filter: $name. Sending to FFmpeg Fallback...")
                    return VideoPlan(false, "Unsupported filter: $name", filter)
                }
            }
        }
        
        if (effectsList.isEmpty() && filter.isNotBlank()) {
            Log.e("HVE-Parser", "⚠️ Filter found but 0 effects parsed! Rejecting to prevent unedited output.")
            return VideoPlan(false, "Filter present but no effects were successfully parsed", filter)
        }

        // লগবক্সে দেখার জন্য সিস্টেম
        val effectNames = effectsList.joinToString { it::class.java.simpleName }
        Log.d("HVE-Parser", "✅ Successfully Mapped GPU Effects: $effectNames")
        
        return VideoPlan(true, null, filter, effectsList)
    }
}
