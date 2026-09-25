package expo.modules.hybridffmpeg

object WindowsCommandConverter {
    fun convert(rawCommand: String): String {
        var cmd = rawCommand.trim()
        
        // 1. Strip Windows batch wrapper (e.g., 'for %%t in (...) DO ffmpeg -y')
        val ffmpegMatch = Regex("(?i)^.*?\\bffmpeg(?:\\.exe)?\\s+").find(cmd)
        if (ffmpegMatch != null) {
            cmd = cmd.substring(ffmpegMatch.range.last + 1).trim()
        }
        
        // 2. Safely remove _output\... patterns completely
        cmd = cmd.replace(Regex("""(?i)"?_output[\\/][^"\s]+"?"""), "")
        
        // 3. Replace all variations of input placeholders
        cmd = cmd.replace(Regex("""(?i)"?%%t"?"""), "<INPUT_VIDEO>")
        cmd = cmd.replace(Regex("""(?i)"?%t"?"""), "<INPUT_VIDEO>")
        
        // 4. Replace all variations of output placeholders
        cmd = cmd.replace(Regex("""(?i)"?%%~nt\.mp4"?"""), "<OUTPUT_VIDEO>")
        cmd = cmd.replace(Regex("""(?i)"?%~nt\.mp4"?"""), "<OUTPUT_VIDEO>")
        
        // Fallbacks
        cmd = cmd.replace("%%t", "<INPUT_VIDEO>")
        cmd = cmd.replace("%~nt", "<OUTPUT_VIDEO>")
        
        return cmd
    }
}