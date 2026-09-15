package expo.modules.hybridffmpeg

object FfmpegMathTranspiler {
    
    /**
     * Converts FFmpeg math strings (from the 96 advanced commands list) 
     * into standard OpenGL ES (GLSL) code for Media3 GPU rendering.
     */
    fun transpile(expression: String): String {
        var glsl = expression.lowercase()

        // 1. Convert Time & Dimension variables from FFmpeg to GLSL Uniforms
        glsl = glsl.replace(Regex("\\bt\\b"), "uTime")
        glsl = glsl.replace("in_w", "uWidth")
        glsl = glsl.replace("in_h", "uHeight")
        glsl = glsl.replace("out_w", "uOutWidth")
        glsl = glsl.replace("out_h", "uOutHeight")
        glsl = glsl.replace("iw", "uWidth")
        glsl = glsl.replace("ih", "uHeight")

        // 2. Convert specific FFmpeg logical functions to GLSL Ternary Operators
        
        // Handle mod(t, 4) -> mod(uTime, 4.0)
        glsl = glsl.replace(Regex("mod\\(([^,]+),([^)]+)\\)")) { matchResult ->
            val arg1 = matchResult.groupValues[1].trim()
            val arg2 = matchResult.groupValues[2].trim()
            "mod($arg1, float($arg2))"
        }

        // Handle gte(a, b) -> (a >= b ? 1.0 : 0.0)
        glsl = glsl.replace(Regex("gte\\(([^,]+),([^)]+)\\)")) { matchResult ->
            val arg1 = matchResult.groupValues[1].trim()
            val arg2 = matchResult.groupValues[2].trim()
            "($arg1 >= float($arg2) ? 1.0 : 0.0)"
        }
        
        // Handle lt(a, b) -> (a < b ? 1.0 : 0.0)
        glsl = glsl.replace(Regex("lt\\(([^,]+),([^)]+)\\)")) { matchResult ->
            val arg1 = matchResult.groupValues[1].trim()
            val arg2 = matchResult.groupValues[2].trim()
            "($arg1 < float($arg2) ? 1.0 : 0.0)"
        }

        // Handle between(t, 2, 3) -> (t >= 2.0 && t <= 3.0 ? 1.0 : 0.0)
        glsl = glsl.replace(Regex("between\\(([^,]+),([^,]+),([^)]+)\\)")) { matchResult ->
            val v = matchResult.groupValues[1].trim()
            val min = matchResult.groupValues[2].trim()
            val max = matchResult.groupValues[3].trim()
            "($v >= float($min) && $v <= float($max) ? 1.0 : 0.0)"
        }

        // 3. GLSL requires floats. Safely append .0 to standalone integers.
        // e.g., 2 -> 2.0, avoiding changes to variable names or existing decimals.
        glsl = glsl.replace(Regex("(?<![a-zA-Z0-9_.])([0-9]+)(?![.0-9a-zA-Z_])")) { matchResult ->
            matchResult.groupValues[1] + ".0"
        }

        return glsl
    }
}
