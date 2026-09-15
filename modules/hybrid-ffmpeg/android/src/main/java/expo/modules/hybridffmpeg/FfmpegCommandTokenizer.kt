package expo.modules.hybridffmpeg

object FfmpegCommandTokenizer {
    fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var quoteChar = '\u0000'

        for (char in command) {
            if (char == '"' || char == '\'') {
                if (!inQuotes) {
                    inQuotes = true
                    quoteChar = char
                } else if (char == quoteChar) {
                    inQuotes = false
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
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    fun hasOption(command: String, option: String): Boolean {
        return tokenize(command).contains(option)
    }

    fun findOptionValue(command: String, option: String): String? {
        val tokens = tokenize(command)
        val idx = tokens.indexOf(option)
        return if (idx != -1 && idx + 1 < tokens.size) tokens[idx + 1] else null
    }
}
