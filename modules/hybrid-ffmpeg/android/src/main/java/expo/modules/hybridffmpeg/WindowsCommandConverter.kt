// File: modules/hybrid-ffmpeg/android/src/main/java/expo/modules/hybridffmpeg/WindowsCommandConverter.kt
package expo.modules.hybridffmpeg

object WindowsCommandConverter {
    fun convert(rawCommand: String): String {
        var cmd = rawCommand.trim()
        val ffmpegMatch = Regex("(?i)^.*?\\bffmpeg(?:\\.exe)?\\s+").find(cmd)
        if (ffmpegMatch != null) {
            cmd = cmd.substring(ffmpegMatch.range.last + 1).trim()
        }
        cmd = cmd.replace(Regex("""(?i)"?_output[\\/][^"\s]+"?"""), "")
        cmd = cmd.replace("%%t", "<INPUT_VIDEO>")
        cmd = cmd.replace("%~nt", "<OUTPUT_VIDEO>")
        return cmd
    }
}