package expo.modules.hybridffmpeg

/**
 * Small command-to-IR parser used only for deciding whether a video command can
 * be represented by Media3 Transformer effects. The original FFmpeg command is
 * always retained for fallback, so this parser never reduces the app's command
 * compatibility surface.
 */
object FfmpegVideoCommandParser {
    data class VideoPlan(
        val supported: Boolean,
        val effects: List<EffectSpec> = emptyList(),
        val sourceFilter: String = "",
        val reason: String? = null
    )

    sealed class EffectSpec {
        data object HFlip : EffectSpec()
        data object VFlip : EffectSpec()
        data object Negate : EffectSpec()
        data class ScaleFactor(val x: Float, val y: Float) : EffectSpec()
        data class ScaleSize(val width: Int, val height: Int) : EffectSpec()
        data class CropPixels(val width: Int, val height: Int, val x: Int, val y: Int) : EffectSpec()
        data class CropFactor(val widthFactor: Float, val heightFactor: Float, val xFactor: Float = 0f, val yFactor: Float = 0f) : EffectSpec()
        data class DynamicCrop(
            val widthDivisor: Float,
            val heightDivisor: Float,
            val xFreq: Float,
            val yFreq: Float,
        ) : EffectSpec()
        data class Rotate(val degrees: Float) : EffectSpec()
        data class GaussianBlur(val sigma: Float) : EffectSpec()
    }

    fun parse(command: String): VideoPlan {
        val lower = command.lowercase()
        if (lower.contains("-filter_complex")) {
            return VideoPlan(false, reason = "filter_complex is not yet mapped to the Media3 single-video IR")
        }
        if (Regex("(^|\\s)-vf\\s+", RegexOption.IGNORE_CASE).containsMatchIn(command).not()) {
            return VideoPlan(false, reason = "No simple -vf video filter graph found")
        }

        val filter = extractVf(command)
            ?: return VideoPlan(false, reason = "Unable to extract -vf filter graph")
        if (filter.isBlank()) return VideoPlan(false, reason = "Empty -vf graph")

        val effects = parseFilterChain(filter)
            ?: return VideoPlan(false, sourceFilter = filter, reason = "Unsupported linear video filter graph")
        return if (effects.isEmpty()) {
            VideoPlan(false, sourceFilter = filter, reason = "No GPU video effects found")
        } else {
            VideoPlan(true, effects, filter)
        }
    }

    fun parseFilterChain(filter: String): List<EffectSpec>? {
        val tokens = splitFilters(filter)
        val effects = mutableListOf<EffectSpec>()
        for (raw in tokens) {
            val token = raw.trim()
            val name = token.substringBefore('=').trim().lowercase()
            val args = token.substringAfter('=', "").trim()
            when (name) {
                "hflip" -> effects += EffectSpec.HFlip
                "vflip" -> effects += EffectSpec.VFlip
                "negate" -> effects += EffectSpec.Negate
                "scale" -> parseScale(args)?.let { effects += it } ?: return null
                "crop" -> parseCrop(args)?.let { effects += it } ?: return null
                "rotate" -> parseFloat(args)?.let { effects += EffectSpec.Rotate(it * 180f / Math.PI.toFloat()) } ?: return null
                "boxblur" -> parseBoxBlur(args)?.let { effects += it } ?: return null
                else -> return null
            }
        }
        return effects
    }

    private fun extractVf(command: String): String? {
        val m = Regex("(?:^|\\s)-vf\\s+(\\\"(?:[^\\\"]|\\\\.)*\\\"|'(?:[^']|\\\\.)*'|[^\\s]+)", RegexOption.IGNORE_CASE).find(command)
            ?: return null
        return m.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
    }

    private fun splitFilters(filter: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        for (i in filter.indices) {
            val c = filter[i]
            if (quote != null) {
                if (c == quote && (i == 0 || filter[i - 1] != '\\')) quote = null
            } else if (c == '\'' || c == '"') {
                quote = c
            } else if (c == '(' || c == '[') depth++
            else if (c == ')' || c == ']') depth--
            else if (c == ',' && depth == 0) {
                out += filter.substring(start, i)
                start = i + 1
            }
        }
        out += filter.substring(start)
        return out.filter { it.isNotBlank() }
    }

    private fun parseScale(args: String): EffectSpec? {
        val p = args.split(':')
        if (p.size != 2) return null
        val x = p[0].trim()
        val y = p[1].trim()
        val xn = x.toFloatOrNull()
        val yn = y.toFloatOrNull()
        if (xn != null && yn != null) return EffectSpec.ScaleSize(xn.toInt(), yn.toInt())

        fun factor(expr: String, base: String): Float? {
            if (expr.equals(base, ignoreCase = true)) return 1f
            Regex("${base}\\s*\\*\\s*([0-9.]+)", RegexOption.IGNORE_CASE).matchEntire(expr)?.let {
                return it.groupValues[1].toFloatOrNull()
            }
            Regex("${base}\\s*/\\s*([0-9.]+)", RegexOption.IGNORE_CASE).matchEntire(expr)?.let {
                val divisor = it.groupValues[1].toFloatOrNull() ?: return null
                if (divisor == 0f) return null
                return 1f / divisor
            }
            return null
        }
        val xf = factor(x, "iw")
        val yf = factor(y, "ih")
        if (xf != null && yf != null) return EffectSpec.ScaleFactor(xf, yf)
        return null
    }

    private fun parseCrop(args: String): EffectSpec? {
        // Dynamic crop form used by the reference commands:
        // in_w/D:in_h/D:(in_w-out_w)/D + ((in_w-out_w)/D)*sin(t*fx):...sin(t*fy)
        val hasSin = Regex("sin\\s*\\(\\s*t\\s*\\*", RegexOption.IGNORE_CASE).containsMatchIn(args)
        if (hasSin) {
            val wDiv = Regex("in_w\\s*/\\s*([0-9.]+)", RegexOption.IGNORE_CASE).find(args)?.groupValues?.get(1)?.toFloatOrNull()
            val hDiv = Regex("in_h\\s*/\\s*([0-9.]+)", RegexOption.IGNORE_CASE).find(args)?.groupValues?.get(1)?.toFloatOrNull()
            val freqs = Regex("sin\\s*\\(\\s*t\\s*\\*\\s*([0-9.]+)\\s*\\)", RegexOption.IGNORE_CASE)
                .findAll(args).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
            if (wDiv != null && hDiv != null && freqs.size >= 2 && wDiv > 0f && hDiv > 0f) {
                return EffectSpec.DynamicCrop(wDiv, hDiv, freqs[0], freqs[1])
            }
        }

        val p = args.split(':')
        if (p.size < 2 || p.size > 4) return null
        val wRaw = p[0].trim()
        val hRaw = p[1].trim()
        val w = wRaw.toIntOrNull()
        val h = hRaw.toIntOrNull()
        if (w != null && h != null) {
            val x = p.getOrNull(2)?.toIntOrNull() ?: 0
            val y = p.getOrNull(3)?.toIntOrNull() ?: 0
            return EffectSpec.CropPixels(w, h, x, y)
        }
        fun factor(expr: String, base: String): Float? {
            if (expr.equals(base, ignoreCase = true)) return 1f
            Regex("${base}\\s*\\*\\s*([0-9.]+)", RegexOption.IGNORE_CASE).matchEntire(expr)?.let {
                return it.groupValues[1].toFloatOrNull()
            }
            Regex("${base}\\s*/\\s*([0-9.]+)", RegexOption.IGNORE_CASE).matchEntire(expr)?.let {
                val divisor = it.groupValues[1].toFloatOrNull() ?: return null
                if (divisor == 0f) return null
                return 1f / divisor
            }
            return null
        }
        val wf = factor(wRaw, "iw")
        val hf = factor(hRaw, "ih")
        if (wf != null && hf != null) {
            val xf = p.getOrNull(2)?.trim()?.let { it.toFloatOrNull() ?: factor(it, "iw") } ?: 0f
            val yf = p.getOrNull(3)?.trim()?.let { it.toFloatOrNull() ?: factor(it, "ih") } ?: 0f
            return EffectSpec.CropFactor(wf, hf, xf, yf)
        }
        return null
    }

    private fun parseBoxBlur(args: String): EffectSpec? {
        val first = args.split(':').firstOrNull()?.trim()?.toFloatOrNull() ?: return null
        if (first <= 0f) return null
        return EffectSpec.GaussianBlur(first.coerceIn(0.1f, 50f))
    }

    private fun parseFloat(value: String): Float? = value.trim().toFloatOrNull()
}
