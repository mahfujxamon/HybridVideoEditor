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

        // ১. ভিডিও এনকোডার খুঁজুন
        val vcodecIdx = mutableTokens.indexOfFirst { it == "-c:v" || it == "-vcodec" || it == "-codec:v" }
        
        if (vcodecIdx != -1 && vcodecIdx + 1 < mutableTokens.size) {
            requested = mutableTokens[vcodecIdx + 1]
            // libx264 বা h264 থাকলে হার্ডওয়্যার এনকোডারে কনভার্ট করুন
            if ((requested.equals("libx264", true) || requested.equals("h264", true)) && isMediaCodecAvailable) {
                mutableTokens[vcodecIdx + 1] = "h264_mediacodec"
                actual = "h264_mediacodec"
                hwUsed = true
            } else {
                actual = requested
                hwUsed = requested.contains("mediacodec")
            }
        } else {
            // যদি এনকোডার না দেওয়া থাকে, তবে হার্ডওয়্যার এনকোডার বসিয়ে দিন
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

        // ২. ক্র্যাশ ফিক্স: হার্ডওয়্যার এনকোডার ব্যবহার হলে সফটওয়্যার-অনলি ফ্ল্যাগগুলো ডিলিট করুন
        if (hwUsed) {
            val flagsToStrip = listOf("-preset", "-crf", "-tune")
            for (flag in flagsToStrip) {
                var idx = mutableTokens.indexOf(flag)
                while (idx != -1 && idx < mutableTokens.size) {
                    mutableTokens.removeAt(idx) // ফ্ল্যাগ রিমুভ করুন (যেমন: -preset)
                    if (idx < mutableTokens.size && !mutableTokens[idx].startsWith("-")) {
                        mutableTokens.removeAt(idx) // ভ্যালু রিমুভ করুন (যেমন: ultrafast)
                    }
                    idx = mutableTokens.indexOf(flag)
                }
            }
            
            // CRF রিমুভ করায় কোয়ালিটি যেন খারাপ না হয়, তাই বিটরেট না থাকলে একটি ডিফল্ট বিটরেট দিন
            val hasVideoBitrate = mutableTokens.any { it == "-b:v" || it == "-vb" }
            if (!hasVideoBitrate) {
                val outputIdx = mutableTokens.size - 1
                if (outputIdx >= 0) {
                    mutableTokens.add(outputIdx, "-b:v")
                    mutableTokens.add(outputIdx + 1, "4M")
                }
            }
        }

        // ৩. থ্রেড সেফটি
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