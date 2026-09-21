// File: modules/hybrid-ffmpeg/android/src/main/java/expo/modules/hybridffmpeg/FfmpegEncoderSelector.kt
package expo.modules.hybridffmpeg

object FfmpegEncoderSelector {
    class EncoderResult(
        val tokens: List<String>,
        val requestedEncoder: String,
        val actualEncoder: String,
        val hardwareEncoderUsed: Boolean
    )

    fun applyHardwarePolicy(tokens: List<String>, isMediaCodecAvailable: Boolean): EncoderResult {
        val mutableTokens = tokens.toMutableList()
        var requested = "default"
        var actual = "default"
        var hwUsed = false

        val vcodecIdx = mutableTokens.indexOfFirst { it == "-c:v" || it == "-vcodec" || it == "-codec:v" }
        
        if (vcodecIdx != -1 && vcodecIdx + 1 < mutableTokens.size) {
            requested = mutableTokens[vcodecIdx + 1]
            if ((requested == "libx264" || requested == "h264") && isMediaCodecAvailable) {
                mutableTokens[vcodecIdx + 1] = "h264_mediacodec"
                actual = "h264_mediacodec"
                hwUsed = true
            } else {
                actual = requested
                hwUsed = requested.contains("mediacodec")
            }
        } else {
            if (isMediaCodecAvailable) {
                val outputIdx = mutableTokens.size - 1
                if (outputIdx >= 0) {
                    mutableTokens.add(outputIdx, "-c:v")
                    mutableTokens.add(outputIdx + 1, "h264_mediacodec")
                    actual = "h264_mediacodec"
                    hwUsed = true
                }
            }
        }

        val threadsIdx = mutableTokens.indexOfFirst { it == "-threads" }
        if (threadsIdx != -1 && threadsIdx + 1 < mutableTokens.size) {
            if (mutableTokens[threadsIdx + 1] == "0") mutableTokens[threadsIdx + 1] = "2"
        } else {
            val outputIdx = mutableTokens.size - 1
            if (outputIdx >= 0) {
                mutableTokens.add(outputIdx, "-threads")
                mutableTokens.add(outputIdx + 1, "2")
            }
        }

        return EncoderResult(mutableTokens, requested, actual, hwUsed)
    }
}