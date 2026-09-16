package expo.modules.hybridffmpeg

object AdvancedFfmpegMathCompiler {
    
    // TASK 3: কনভার্ট করে FFmpeg crop ম্যাথকে GLSL Fragment Shader ম্যাথে রূপান্তর করে
    fun compileCropMathToGLSL(ffmpegMath: String): String {
        var glsl = ffmpegMath.lowercase()
        
        // UV Coordinates এ 1.0 মানে পুরো স্ক্রিন
        glsl = glsl.replace("in_w", "1.0").replace("iw", "1.0")
        glsl = glsl.replace("in_h", "1.0").replace("ih", "1.0")
        
        // আউটপুট উইডথ/হাইট
        glsl = glsl.replace("out_w", "uCropW").replace("ow", "uCropW")
        glsl = glsl.replace("out_h", "uCropH").replace("oh", "uCropH")
        
        // Time ভ্যারিয়েবল
        glsl = glsl.replace(Regex("\\bt\\b"), "uTime")
        glsl = glsl.replace("pi", "3.14159")
        
        // OpenGL Float ফিক্সিং (যেমন 2 কে 2.0 করা, যেন টাইপ এরর না আসে)
        glsl = glsl.replace(Regex("(?<![a-zA-Z0-9.])(\\d+)(?![a-zA-Z0-9.])")) { match -> "${match.value}.0" }
        return glsl
    }

    // FFmpeg Enable স্ট্রিংকে Kotlin Time Evaluator এ রূপান্তর
    fun evaluateTimeVisibility(expression: String, timeSeconds: Double): Boolean {
        if (expression.isBlank()) return true
        val expr = expression.replace(" ", "").lowercase().replace("'", "")
        
        // Pattern 1: gte(mod(t,5),3)
        Regex("gte\\(mod\\(t,([0-9.]+)\\),([0-9.]+)\\)").find(expr)?.let {
            val period = it.groupValues[1].toDouble()
            val start = it.groupValues[2].toDouble()
            return (timeSeconds % period) >= start
        }
        
        // Pattern 2: lt(mod(t,7),3.5)*gte(t,3)
        Regex("lt\\(mod\\(t,([0-9.]+)\\),([0-9.]+)\\)\\*gte\\(t,([0-9.]+)\\)").find(expr)?.let {
            val period = it.groupValues[1].toDouble()
            val ltVal = it.groupValues[2].toDouble()
            val minT = it.groupValues[3].toDouble()
            val r = timeSeconds % period
            return r < ltVal && timeSeconds >= minT
        }
        
        return true // Default visible
    }
}