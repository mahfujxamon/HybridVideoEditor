package expo.modules.hybridffmpeg

/**
 * Parser for a deliberately small but useful subset of FFmpeg -filter_complex
 * graphs that can be represented by Media3 Composition + OpenGL.
 *
 * It never removes universal FFmpeg support: unsupported graphs return null and
 * continue through the normal FFmpegKit renderer.
 */
object FfmpegComplexCommandParser {
    data class OverlayPlan(
        val supported: Boolean,
        val baseInput: Int = 0,
        val overlayInput: Int = 1,
        val baseStartSeconds: Double = 0.0,
        val overlayStartSeconds: Double = 0.0,
        val baseEffects: List<FfmpegVideoCommandParser.EffectSpec> = emptyList(),
        val overlayEffects: List<FfmpegVideoCommandParser.EffectSpec> = emptyList(),
        val enable: Visibility = Visibility.Always,
        val x: Float = 0f,
        val y: Float = 0f,
        val reason: String? = null
    )

    sealed class Visibility {
        data object Always : Visibility()
        data class ModGte(val period: Double, val start: Double) : Visibility()
        data class ModLtAndGte(val period: Double, val end: Double, val minTime: Double) : Visibility()
    }

    fun parse(command: String): OverlayPlan? {
        val graph = extractFilterComplex(command) ?: return null
        val statements = splitTopLevel(graph, ';')
        val overlayIndex = statements.indexOfFirst { it.substringAfter(']').contains(Regex("(?i)\\boverlay\\s*=")) || Regex("(?i)\\[\\w+\\]\\[\\w+\\]overlay").containsMatchIn(it) }
        if (overlayIndex < 0) return null

        val overlayStatement = statements[overlayIndex]
        val inputLabels = Regex("\\[([^]]+)\\]").findAll(overlayStatement).map { it.groupValues[1] }.toList()
        if (inputLabels.size < 2) return null
        val firstLabel = inputLabels[0]
        val secondLabel = inputLabels[1]
        val firstSource = findBranchSource(statements, firstLabel) ?: return null
        val secondSource = findBranchSource(statements, secondLabel) ?: return null
        if (firstSource.inputIndex == secondSource.inputIndex) return null
        val baseLabel = firstLabel
        val overlayLabel = secondLabel
        val baseInput = firstSource.inputIndex
        val overlayInput = secondSource.inputIndex
        val baseBranch = findLinearBranch(statements, baseLabel, baseInput) ?: return null
        val overlayBranch = findLinearBranch(statements, overlayLabel, overlayInput) ?: return null

        val overlayToken = overlayStatement.substringAfter(']').substringAfter(']').trim()
        val overlayArgs = overlayToken.substringAfter('=', "").trim()
        val params = parseNamedArgs(overlayArgs)
        val x = params["x"]?.toFloatOrNull() ?: 0f
        val y = params["y"]?.toFloatOrNull() ?: 0f
        // The current compositor implementation intentionally starts with the
        // common x=0:y=0 form used by the reference command set.
        if (x != 0f || y != 0f) return null

        val enable = parseVisibility(params["enable"] ?: "") ?: return null
        val baseStart = inputStartSeconds(command, baseInput)
        val overlayStart = inputStartSeconds(command, overlayInput)

        // Reject unrelated video graph statements. Audio statements are allowed
        // because the video composition can be muxed with a separately processed
        // FFmpeg audio track.
        for ((index, statement) in statements.withIndex()) {
            if (index == overlayIndex || index == baseBranch.statementIndex || index == overlayBranch.statementIndex) continue
            if (statement.contains(Regex("\\[\\d+:a\\]")) || containsAudioFilter(statement)) continue
            if (statement.contains("amovie", true)) continue
            // A second video transform/overlay graph is beyond this IR for now.
            if (statement.contains(Regex("\\[\\d+:v\\]")) || statement.contains("overlay", true)) return null
        }

        return OverlayPlan(
            supported = true,
            baseInput = baseInput,
            overlayInput = overlayInput,
            baseStartSeconds = baseStart,
            overlayStartSeconds = overlayStart,
            baseEffects = baseBranch.effects,
            overlayEffects = overlayBranch.effects,
            enable = enable,
            x = x,
            y = y
        )
    }

    private data class Branch(val effects: List<FfmpegVideoCommandParser.EffectSpec>, val statementIndex: Int)

    private data class BranchSource(val inputIndex: Int)

    private fun findBranchSource(statements: List<String>, label: String): BranchSource? {
        Regex("(\\d+):v", RegexOption.IGNORE_CASE).matchEntire(label)?.let {
            return BranchSource(it.groupValues[1].toInt())
        }
        for (statement in statements) {
            val match = Regex("\\[(\\d+):v\\]([^;]*)\\[${Regex.escape(label)}\\]", RegexOption.IGNORE_CASE).find(statement)
            if (match != null) return BranchSource(match.groupValues[1].toInt())
        }
        return null
    }

    private fun findLinearBranch(statements: List<String>, label: String, inputIndex: Int): Branch? {
        if (label.equals("${inputIndex}:v", ignoreCase = true)) return Branch(emptyList(), -1)
        var best: Branch? = null
        for ((index, statement) in statements.withIndex()) {
            if (!statement.contains("[$label]")) continue
            val source = "[$inputIndex:v]"
            if (!statement.contains(source)) continue
            val firstClose = statement.indexOf(source) + source.length
            val body = statement.substring(firstClose).trim()
            if (body.isEmpty()) return Branch(emptyList(), index)
            if (body.contains("overlay", true)) return null
            val effectsText = body.substringBeforeLast("[$label]").trim().trimEnd(';')
            val effects = FfmpegVideoCommandParser.parseFilterChain(effectsText)
                ?: return null
            return Branch(effects, index)
        }
        return best
    }

    private fun extractFilterComplex(command: String): String? {
        return FfmpegCommandTokenizer.findOptionValue(command, "-filter_complex")
            ?.takeIf { it.isNotBlank() }
    }

    private fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        for (i in value.indices) {
            val c = value[i]
            if (quote != null) {
                if (c == quote && (i == 0 || value[i - 1] != '\\')) quote = null
            } else if (c == '\'' || c == '"') quote = c
            else if (c == '(' || c == '[') depth++
            else if (c == ')' || c == ']') depth--
            else if (c == delimiter && depth == 0) {
                out += value.substring(start, i)
                start = i + 1
            }
        }
        out += value.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseNamedArgs(value: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pieces = splitTopLevel(value, ':')
        for (piece in pieces) {
            val eq = piece.indexOf('=')
            if (eq > 0) result[piece.substring(0, eq).trim().lowercase()] = piece.substring(eq + 1).trim().trim('"', '\'')
        }
        return result
    }

    private fun parseVisibility(raw: String): Visibility? {
        if (raw.isBlank()) return Visibility.Always
        val s = raw.trim().trim('"', '\'')
        Regex("gte\\(mod\\(t\\s*,\\s*([0-9.]+)\\)\\s*,\\s*([0-9.]+)\\)", RegexOption.IGNORE_CASE).matchEntire(s)?.let {
            return Visibility.ModGte(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
        }
        Regex("lt\\(mod\\(t\\s*,\\s*([0-9.]+)\\)\\s*,\\s*([0-9.]+)\\)\\s*\\*\\s*gte\\(t\\s*,\\s*([0-9.]+)\\)", RegexOption.IGNORE_CASE).matchEntire(s)?.let {
            return Visibility.ModLtAndGte(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble())
        }
        return null
    }

    private fun inputStartSeconds(command: String, inputIndex: Int): Double {
        val tokens = FfmpegCommandTokenizer.tokenize(command)
        var inputSeen = 0
        for (i in tokens.indices) {
            if (tokens[i].equals("-i", ignoreCase = true) && i + 1 < tokens.size) {
                if (inputSeen == inputIndex) {
                    if (i >= 2 && tokens[i - 2].equals("-ss", ignoreCase = true)) {
                        return tokens[i - 1].toDoubleOrNull() ?: 0.0
                    }
                    if (i >= 1 && tokens[i - 1].startsWith("-ss=", ignoreCase = true)) {
                        return tokens[i - 1].substringAfter('=').toDoubleOrNull() ?: 0.0
                    }
                    return 0.0
                }
                inputSeen++
            }
        }
        return 0.0
    }

    private fun containsAudioFilter(statement: String): Boolean {
        val s = statement.lowercase()
        return listOf("atempo", "bass", "volume", "aecho", "firequalizer", "compand", "pan=", "highpass", "lowpass", "amix", "amovie").any { s.contains(it) }
    }
}
