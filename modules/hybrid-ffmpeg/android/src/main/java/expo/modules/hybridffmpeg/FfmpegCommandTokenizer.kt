package expo.modules.hybridffmpeg

object FfmpegCommandTokenizer {
    fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar = '\u0000'
        var escapeNext = false

        for (char in command) {
            if (escapeNext) {
                current.append(char)
                escapeNext = false
                continue
            }
            if (char == '\\') {
                current.append(char)
                escapeNext = true
                continue
            }
            if (char == '\'' || char == '"') {
                if (!inQuotes) {
                    inQuotes = true
                    quoteChar = char
                    current.append(char)
                } else if (char == quoteChar) {
                    inQuotes = false
                    current.append(char)
                } else {
                    current.append(char)
                }
            } else if (char.isWhitespace() && !inQuotes) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
            } else {
                current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    fun buildCommand(tokens: List<String>): String {
        return tokens.joinToString(" ")
    }
}