package expo.modules.hybridffmpeg

/**
 * Quote-aware FFmpeg command tokenizer.
 *
 * It is intentionally shell-like, but does not execute a shell. Single and
 * double quoted regions stay inside one argument, which is important for
 * -filter_complex graphs containing spaces, pipes, parentheses and expressions.
 */
object FfmpegCommandTokenizer {
    data class Token(val value: String, val start: Int, val end: Int)

    fun tokenize(command: String): List<String> = tokenizeWithRanges(command).map { it.value }

    fun tokenizeWithRanges(command: String): List<Token> {
        val result = mutableListOf<Token>()
        var i = 0
        while (i < command.length) {
            while (i < command.length && command[i].isWhitespace()) i++
            if (i >= command.length) break

            val start = i
            val out = StringBuilder()
            var quote: Char? = null
            var escaped = false

            while (i < command.length) {
                val c = command[i]
                if (escaped) {
                    out.append(c)
                    escaped = false
                    i++
                    continue
                }

                if (quote == '\'') {
                    if (c == '\'') {
                        quote = null
                    } else {
                        out.append(c)
                    }
                    i++
                    continue
                }

                if (quote == '"') {
                    when (c) {
                        '"' -> {
                            quote = null
                            i++
                        }
                        '\\' -> {
                            // Keep escaped characters useful inside double quotes.
                            escaped = true
                            i++
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                    continue
                }

                when {
                    c.isWhitespace() -> break
                    c == '\'' || c == '"' -> {
                        quote = c
                        i++
                    }
                    c == '\\' -> {
                        escaped = true
                        i++
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }

            if (escaped) out.append('\\')
            // Unterminated quotes are intentionally preserved as one token. The
            // caller can report the malformed command instead of silently splitting it.
            result += Token(out.toString(), start, i)
            while (i < command.length && command[i].isWhitespace()) i++
        }
        return result
    }

    fun findOptionValue(command: String, option: String): String? {
        val tokens = tokenize(command)
        for (i in tokens.indices) {
            if (tokens[i].equals(option, ignoreCase = true) && i + 1 < tokens.size) {
                return tokens[i + 1]
            }
            if (tokens[i].startsWith("$option=", ignoreCase = true)) {
                return tokens[i].substringAfter('=')
            }
        }
        return null
    }

    fun hasOption(command: String, option: String): Boolean =
        tokenize(command).any { it.equals(option, ignoreCase = true) || it.startsWith("$option=", ignoreCase = true) }
}
