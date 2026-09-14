package expo.modules.hybridffmpeg

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaCodecList
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.io.FileOutputStream

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class HybridFfmpegModule : Module() {

    private fun getContext(): Context {
        return requireNotNull(appContext.reactContext)
    }

    /**
     * When the UI does not supply a selected URI, automatically choose the
     * newest video visible through Android MediaStore. This makes the command
     * box usable as a standalone FFmpeg editor: paste arguments and RUN.
     */
    private fun findLatestVideoUri(): Uri {
        val context = getContext()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val selection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Video.Media.RELATIVE_PATH} NOT LIKE ?"
        } else null
        val selectionArgs = if (android.os.Build.VERSION.SDK_INT >= 29) {
            arrayOf("Movies/HybridVideoEditor/%")
        } else null

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sort
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                )
                return Uri.withAppendedPath(collection, id.toString())
            }
        }

        throw Exception(
            "No video was found in Android MediaStore. Select a video once or add a video to device storage."
        )
    }

    private fun resolveInputFile(inputUriString: String?): Pair<File, String> {
        val supplied = inputUriString?.trim().orEmpty()
        val uri = if (supplied.isBlank()) findLatestVideoUri() else Uri.parse(supplied)
        val file = copyUriToCache(uri.toString())
        return file to uri.toString()
    }

    /** Publish the rendered cache file into Movies/HybridVideoEditor so the
     * result survives app cache cleanup and is visible in normal Android media apps. */
    private fun publishVideoToMediaStore(source: File): Uri? {
        if (!source.exists() || source.length() == 0L) return null
        val context = getContext()
        val resolver = context.contentResolver
        val name = "HVE_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HybridVideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw Exception("Cannot open MediaStore output stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    // ============================================================
    // INPUT
    // ============================================================

    private fun copyUriToCache(uriString: String): File {

        val context = getContext()
        val uri = Uri.parse(uriString)

        val extension =
            uri.path
                ?.substringAfterLast('.', "mp4")
                ?.takeIf { it.length in 1..8 }
                ?: "mp4"

        val inputFile = File(
            context.cacheDir,
            "ffmpeg_input_${System.currentTimeMillis()}.$extension"
        )

        val inputStream =
            context.contentResolver.openInputStream(uri)
                ?: throw Exception("Cannot open selected media")

        inputStream.use { input ->
            FileOutputStream(inputFile).use { output ->
                input.copyTo(output)
            }
        }

        return inputFile
    }

    // ============================================================
    // ASSET
    // ============================================================

    private fun copyAssetToCache(assetPath: String): File? {

        val context = getContext()

        val cleanPath =
            assetPath
                .removePrefix("/")
                .removePrefix("./")

        return try {

            val assetManager = context.assets

            assetManager.open(cleanPath).use { }

            val safeName =
                cleanPath.replace("/", "_")

            val outputFile =
                File(
                    context.cacheDir,
                    "asset_$safeName"
                )

            if (
                !outputFile.exists() ||
                outputFile.length() == 0L
            ) {

                assetManager.open(cleanPath).use { input ->

                    FileOutputStream(outputFile).use { output ->

                        input.copyTo(output)

                    }
                }
            }

            outputFile

        } catch (_: Throwable) {

            null
        }
    }

    private fun resolveAssetPath(assetPath: String): String {

        val context = getContext()

        val cleanPath =
            assetPath
                .removePrefix("/")
                .removePrefix("./")

        val candidates = listOf(
            File(context.filesDir, cleanPath),
            File(context.cacheDir, cleanPath),
            File(context.getExternalFilesDir(null), cleanPath),
            File("/storage/emulated/0/Bypass", cleanPath),
            File("/storage/emulated/0", cleanPath)
        )

        candidates.firstOrNull { it.isFile && it.canRead() }?.let {
            return it.absolutePath
        }

        val packagedAsset =
            copyAssetToCache(cleanPath)

        if (packagedAsset != null) {
            return packagedAsset.absolutePath
        }

        throw Exception(
            "Required asset not found: $assetPath"
        )
    }

    // ============================================================
    // COMMAND NORMALIZATION
    // ============================================================

    private fun normalizeCommand(command: String): String {

        var result = command.trim()

        result =
            result.replace(
                "<INPUT_VIDEO>-i",
                "<INPUT_VIDEO> -i"
            )

        result =
            result.replace(
                "<INPUT_VIDEO>-i",
                "<INPUT_VIDEO> -i"
            )

        return result.trim()
    }

    // ============================================================
    // ASSET RESOLUTION
    // ============================================================

    private fun resolveCommandAssets(
        command: String
    ): String {
        val pattern = Regex(
            "(?i)(\\b(?:amovie|movie)\\s*=\\s*)(?:'([^']+)'|\"([^\"]+)\"|([^,:;\\]\\s]+))"
        )

        return pattern.replace(command) { match ->
            val prefix = match.groupValues[1]
            val rawPath = when {
                match.groupValues[2].isNotEmpty() -> match.groupValues[2]
                match.groupValues[3].isNotEmpty() -> match.groupValues[3]
                else -> match.groupValues[4]
            }.trim()

            if (rawPath.isBlank() || rawPath.startsWith("/")) {
                match.value
            } else {
                try {
                    val absolutePath = resolveAssetPath(rawPath)
                    prefix + "'" + absolutePath + "'"
                } catch (_: Throwable) {
                    // Leave unknown paths untouched so normal FFmpeg diagnostics
                    // remain available instead of crashing during preprocessing.
                    match.value
                }
            }
        }
    }

    // ============================================================
    // FALLBACK SAFETY
    // ============================================================

    /**
     * FFmpegKit fallback must not force Android MediaCodec for graphs that the
     * Media3 GPU IR could not represent. In particular, dynamic expressions
     * and complex filter graphs are device-sensitive on some MediaCodec
     * implementations. We keep MediaCodec for ordinary/simple fallbacks, but
     * deliberately use libopenh264 for risky graphs so a failed Media3 parse cannot
     * turn into a native hardware-encoder crash.
     */
    private fun shouldPreferCpuFallback(command: String): Boolean {
        val lower = command.lowercase()
        return lower.contains("-filter_complex") ||
            Regex("\\b(sin|cos|tan|asin|acos|atan|random|if)\\s*\\(").containsMatchIn(lower) ||
            lower.contains("overlay=") ||
            lower.contains("hwupload") ||
            lower.contains("hwdownload") ||
            lower.contains("_opencl") ||
            lower.contains("vulkan")
    }

    private fun prepareCpuFallbackEncoder(command: String): String {
        var result = command

        // FIXED: Replaced libx264 with libopenh264 to match FFmpeg LTS capabilities
        result = result.replace(
            Regex("(?i)(-c:v\\s+)(h264_mediacodec|h264|libx265|libx264)"),
            "$1libopenh264"
        )
        result = result.replace(
            Regex("(?i)(-vcodec\\s+)(h264_mediacodec|h264|libx265|libx264)"),
            "$1libopenh264"
        )
        result = result.replace(
            Regex("(?i)(-codec:v\\s+)(h264_mediacodec|h264|libx265|libx264)"),
            "$1libopenh264"
        )

        val hasVideoCodec = Regex(
            "(?i)(-c:v|-vcodec|-codec:v)\\s+\\S+"
        ).containsMatchIn(result)
        
        if (!hasVideoCodec) result += " -c:v libopenh264"

        // FIXED: Remove unsupported x264 specific flags for libopenh264 compatibility
        result = result.replace(Regex("(?i)\\s+-preset\\s+\\S+"), "")
        result = result.replace(Regex("(?i)\\s+-crf\\s+\\S+"), "")
        result = result.replace(Regex("(?i)\\s+-tune\\s+\\S+"), "")
        result = result.replace(Regex("(?i)\\s+-threads\\s+\\S+"), "")

        return result.trim()
    }

    private fun classifyFallbackReason(command: String, media3Error: String? = null): String {
        val lower = command.lowercase()
        return when {
            media3Error?.contains("encoder", true) == true || media3Error?.contains("codec", true) == true -> "MEDIA3_HARDWARE_ENCODER_FAILURE"
            lower.contains("filter_complex") || Regex("\\b(sin|cos|tan|asin|acos|atan|random|if)\\s*\\(").containsMatchIn(lower) -> "COMPLEX_OR_DYNAMIC_GRAPH_NOT_MAPPED_TO_MEDIA3"
            lower.contains("opencl") || lower.contains("hwupload") || lower.contains("vulkan") -> "EXPLICIT_EXTERNAL_GPU_GRAPH"
            lower.contains("amovie=") || lower.contains("movie=") -> "EXTERNAL_ASSET_OR_AUDIO_GRAPH"
            else -> "MEDIA3_GRAPH_UNSUPPORTED"
        }
    }

    // ============================================================
    // HARDWARE ENCODER
    // ============================================================

    private fun prepareHardwareEncoder(
        command: String
    ): String {

        var result = command

        result =
            result.replace(
                Regex(
                    """(?i)(-c:v\s+)(libx264|libx265|h264)"""
                ),
                "$1h264_mediacodec"
            )

        result =
            result.replace(
                Regex(
                    """(?i)(-vcodec\s+)(libx264|libx265|h264)"""
                ),
                "$1h264_mediacodec"
            )

        result =
            result.replace(
                Regex(
                    """(?i)(-codec:v\s+)(libx264|libx265|h264)"""
                ),
                "$1h264_mediacodec"
            )

        val hasVideoCodec =
            Regex(
                """(?i)(-c:v|-vcodec|-codec:v)\s+\S+"""
            ).containsMatchIn(result)

        if (!hasVideoCodec) {

            result =
                "$result -c:v h264_mediacodec"
        }

        // CPU encoder specific options
        result =
            result.replace(
                Regex("""(?i)\s+-preset\s+\S+"""),
                ""
            )

        result =
            result.replace(
                Regex("""(?i)\s+-crf\s+\S+"""),
                ""
            )

        result =
            result.replace(
                Regex("""(?i)\s+-tune\s+\S+"""),
                ""
            )

        result =
            result.replace(
                Regex("""(?i)\s+-threads\s+\S+"""),
                ""
            )

        return result.trim()
    }

    // ============================================================
    // UNIVERSAL COMMAND
    // ============================================================

    private fun buildUniversalCommand(
        userCommand: String,
        inputFile: File,
        outputFile: File
    ): String {

        var command =
            normalizeCommand(userCommand)

        val isFullCommand =
            command.contains("-i") ||
            command.contains("<INPUT_VIDEO>")

        if (isFullCommand) {

            command =
                command.replace(
                    "<INPUT_VIDEO>",
                    "\"${inputFile.absolutePath}\""
                )

            command =
                command.replace(
                    "<OUTPUT_VIDEO>",
                    "\"${outputFile.absolutePath}\""
                )

            if (
                !command.contains(
                    outputFile.absolutePath
                )
            ) {

                command =
                    "$command \"${outputFile.absolutePath}\""
            }

        } else {

            command =
                "-y " +
                "-i \"${inputFile.absolutePath}\" " +
                command +
                " " +
                "\"${outputFile.absolutePath}\""
        }

        command =
            resolveCommandAssets(command)

        command = if (shouldPreferCpuFallback(command)) {
            prepareCpuFallbackEncoder(command)
        } else {
            prepareHardwareEncoder(command)
        }

        return command.trim()
    }

    // ============================================================
    // GPU ANALYSIS
    // ============================================================

    private fun analyzeGpuRequest(
        command: String
    ): Map<String, Any> {

        val lower =
            command.lowercase()

        val gpuFilters =
            mutableListOf<String>()

        val knownOpenClFilters =
            listOf(
                "avgblur_opencl",
                "boxblur_opencl",
                "convolution_opencl",
                "crop_opencl",
                "deshake_opencl",
                "nlmeans_opencl",
                "overlay_opencl",
                "pad_opencl",
                "prewitt_opencl",
                "program_opencl",
                "remap_opencl",
                "scale_opencl",
                "tonemap_opencl",
                "transpose_opencl",
                "unsharp_opencl"
            )

        for (filter in knownOpenClFilters) {

            if (lower.contains(filter)) {
                gpuFilters.add(filter)
            }
        }

        val openClRequested =
            lower.contains("opencl") ||
            lower.contains("hwupload") ||
            lower.contains("hwdownload")

        return mapOf(

            "gpuRequested" to
                openClRequested,

            "openclFilters" to
                gpuFilters,

            "hybridMode" to
                if (openClRequested)
                    "GPU+CPU"
                else
                    "CPU+HardwareEncoder"
        )
    }


    // ============================================================
    // OPENGL ES RUNTIME DIAGNOSTIC
    //
    // This intentionally uses Android's public OpenGL ES API.
    // It does NOT load vendor/private libOpenCL.so files.
    //
    // The test creates a real EGL GPU context, compiles vertex and
    // fragment shaders, renders a triangle into a PBuffer and reads
    // back a pixel. This proves that the app process can reach the
    // Android OpenGL ES driver and execute GPU shader work.
    // ============================================================

    private fun checkOpenGlRuntime(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null

        fun eglError(): String {
            return "0x" + Integer.toHexString(EGL14.eglGetError())
        }

        fun compileShader(type: Int, source: String): Pair<Int, String?> {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) {
                return Pair(0, "glCreateShader returned 0")
            }

            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)

            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                return Pair(0, log)
            }

            return Pair(shader, null)
        }

        fun linkProgram(vertexShader: Int, fragmentShader: Int): Pair<Int, String?> {
            val program = GLES20.glCreateProgram()
            if (program == 0) {
                return Pair(0, "glCreateProgram returned 0")
            }

            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)

            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                return Pair(0, log)
            }

            return Pair(program, null)
        }

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

            if (display == EGL14.EGL_NO_DISPLAY) {
                result["success"] = false
                result["status"] = "EGL_DISPLAY_UNAVAILABLE"
                result["error"] = "eglGetDisplay returned EGL_NO_DISPLAY"
                return result
            }

            val major = IntArray(1)
            val minor = IntArray(1)

            if (!EGL14.eglInitialize(display, major, 0, minor, 0)) {
                result["success"] = false
                result["status"] = "EGL_INITIALIZE_FAILED"
                result["error"] = "eglInitialize failed: ${eglError()}"
                return result
            }

            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)

            var es3ConfigAvailable =
                EGL14.eglChooseConfig(
                    display,
                    configAttributes,
                    0,
                    configs,
                    0,
                    1,
                    configCount,
                    0
                ) && configCount[0] > 0

            var config = if (es3ConfigAvailable) configs[0] else null
            var contextVersion = 3

            if (config == null) {
                val es2Attributes = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
                )

                val es2Configs = arrayOfNulls<EGLConfig>(1)

                val ok =
                    EGL14.eglChooseConfig(
                        display,
                        es2Attributes,
                        0,
                        es2Configs,
                        0,
                        1,
                        configCount,
                        0
                    )

                if (!ok || configCount[0] <= 0 || es2Configs[0] == null) {
                    result["success"] = false
                    result["status"] = "EGL_CONFIG_FAILED"
                    result["error"] = "No usable OpenGL ES PBuffer config: ${eglError()}"
                    return result
                }

                config = es2Configs[0]
                contextVersion = 2
                es3ConfigAvailable = false
            }

            val contextAttributes =
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    contextVersion,
                    EGL14.EGL_NONE
                )

            context =
                EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0
                )

            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                result["success"] = false
                result["status"] = "EGL_CONTEXT_FAILED"
                result["contextVersion"] = contextVersion
                result["error"] = "eglCreateContext failed: ${eglError()}"
                return result
            }

            val surfaceAttributes =
                intArrayOf(
                    EGL14.EGL_WIDTH, 64,
                    EGL14.EGL_HEIGHT, 64,
                    EGL14.EGL_NONE
                )

            surface =
                EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    surfaceAttributes,
                    0
                )

            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                result["success"] = false
                result["status"] = "EGL_SURFACE_FAILED"
                result["error"] = "eglCreatePbufferSurface failed: ${eglError()}"
                return result
            }

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                result["success"] = false
                result["status"] = "EGL_MAKE_CURRENT_FAILED"
                result["error"] = "eglMakeCurrent failed: ${eglError()}"
                return result
            }

            val glVendor =
                GLES20.glGetString(GLES20.GL_VENDOR) ?: "UNKNOWN"

            val glRenderer =
                GLES20.glGetString(GLES20.GL_RENDERER) ?: "UNKNOWN"

            val glVersion =
                GLES20.glGetString(GLES20.GL_VERSION) ?: "UNKNOWN"

            val glslVersion =
                GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
                    ?: "UNKNOWN"

            result["eglMajor"] = major[0]
            result["eglMinor"] = minor[0]
            result["contextVersionRequested"] = contextVersion
            result["es3ConfigAvailable"] = es3ConfigAvailable
            result["vendor"] = glVendor
            result["renderer"] = glRenderer
            result["glVersion"] = glVersion
            result["glslVersion"] = glslVersion

            val vertexSource =
                if (contextVersion >= 3) {
                    """
                    #version 300 es
                    in vec2 aPosition;
                    void main() {
                        gl_Position = vec4(aPosition, 0.0, 1.0);
                    }
                    """.trimIndent()
                } else {
                    """
                    attribute vec2 aPosition;
                    void main() {
                        gl_Position = vec4(aPosition, 0.0, 1.0);
                    }
                    """.trimIndent()
                }

            val fragmentSource =
                if (contextVersion >= 3) {
                    """
                    #version 300 es
                    precision mediump float;
                    out vec4 fragColor;
                    void main() {
                        fragColor = vec4(0.0, 1.0, 0.0, 1.0);
                    }
                    """.trimIndent()
                } else {
                    """
                    precision mediump float;
                    void main() {
                        gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
                    }
                    """.trimIndent()
                }

            val vertexResult =
                compileShader(
                    GLES20.GL_VERTEX_SHADER,
                    vertexSource
                )

            if (vertexResult.first == 0) {
                result["success"] = false
                result["status"] = "VERTEX_SHADER_COMPILE_FAILED"
                result["shaderError"] = vertexResult.second ?: "Unknown"
                return result
            }

            val fragmentResult =
                compileShader(
                    GLES20.GL_FRAGMENT_SHADER,
                    fragmentSource
                )

            if (fragmentResult.first == 0) {
                GLES20.glDeleteShader(vertexResult.first)
                result["success"] = false
                result["status"] = "FRAGMENT_SHADER_COMPILE_FAILED"
                result["shaderError"] = fragmentResult.second ?: "Unknown"
                return result
            }

            val programResult =
                linkProgram(
                    vertexResult.first,
                    fragmentResult.first
                )

            GLES20.glDeleteShader(vertexResult.first)
            GLES20.glDeleteShader(fragmentResult.first)

            if (programResult.first == 0) {
                result["success"] = false
                result["status"] = "PROGRAM_LINK_FAILED"
                result["shaderError"] = programResult.second ?: "Unknown"
                return result
            }

            val program = programResult.first

            val vertices: FloatBuffer =
                ByteBuffer
                    .allocateDirect(3 * 2 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()

            vertices.put(
                floatArrayOf(
                    0.0f, 0.8f,
                    -0.8f, -0.8f,
                    0.8f, -0.8f
                )
            )
            vertices.position(0)

            val positionLocation =
                GLES20.glGetAttribLocation(
                    program,
                    "aPosition"
                )

            if (positionLocation < 0) {
                GLES20.glDeleteProgram(program)
                result["success"] = false
                result["status"] = "ATTRIBUTE_LOOKUP_FAILED"
                result["error"] = "aPosition was not found"
                return result
            }

            GLES20.glViewport(0, 0, 64, 64)
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(positionLocation)

            GLES20.glVertexAttribPointer(
                positionLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                2 * 4,
                vertices
            )

            GLES20.glDrawArrays(
                GLES20.GL_TRIANGLES,
                0,
                3
            )

            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glFinish()

            val pixel =
                ByteBuffer
                    .allocateDirect(4)
                    .order(ByteOrder.nativeOrder())

            GLES20.glReadPixels(
                32,
                32,
                1,
                1,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                pixel
            )

            val red = pixel.get(0).toInt() and 0xff
            val green = pixel.get(1).toInt() and 0xff
            val blue = pixel.get(2).toInt() and 0xff
            val alpha = pixel.get(3).toInt() and 0xff

            val glError =
                GLES20.glGetError()

            GLES20.glDeleteProgram(program)

            val renderedGreenPixel =
                green > 100 &&
                    green > red * 2 &&
                    green > blue * 2 &&
                    alpha > 0

            result["pixel"] =
                mapOf(
                    "r" to red,
                    "g" to green,
                    "b" to blue,
                    "a" to alpha
                )

            result["glError"] = glError
            result["shaderCompile"] = true
            result["shaderLink"] = true
            result["gpuRenderTest"] = renderedGreenPixel
            result["success"] = renderedGreenPixel

            result["status"] =
                if (renderedGreenPixel) {
                    "OPENGL_ES_GPU_WORKING"
                } else if (glError != GLES20.GL_NO_ERROR) {
                    "OPENGL_GL_ERROR"
                } else {
                    "OPENGL_RENDER_TEST_FAILED"
                }

            return result

        } catch (error: Throwable) {

            result["success"] = false
            result["status"] = "OPENGL_DIAGNOSTIC_EXCEPTION"
            result["error"] =
                error.message ?: error.toString()

            return result

        } finally {

            try {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                }
            } catch (_: Throwable) {
            }

            try {
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(
                        display,
                        surface
                    )
                }
            } catch (_: Throwable) {
            }

            try {
                if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(
                        display,
                        context
                    )
                }
            } catch (_: Throwable) {
            }

            try {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ============================================================
    // REAL VIDEO FRAME -> OPENGL ES -> PROCESSED FRAME TEST
    //
    // This is the first real bridge milestone. FFmpeg decodes one
    // video frame to RGBA, OpenGL ES uploads that frame as a texture,
    // a fragment shader processes it on the GPU, and the processed
    // pixels are read back and encoded by FFmpeg. It is intentionally
    // a one-frame proof before we build the full streaming pipeline.
    // ============================================================

    private fun runOpenGlVideoFrameTest(
        inputUri: String
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        var program = 0
        var inputTexture = 0
        var outputTexture = 0
        var framebuffer = 0

        fun eglError(): String =
            "0x" + Integer.toHexString(EGL14.eglGetError())

        try {
            val inputFile = copyUriToCache(inputUri)
            val stamp = System.currentTimeMillis()
            val rawInput = File(getContext().cacheDir, "gpu_frame_in_$stamp.rgba")
            val rawOutput = File(getContext().cacheDir, "gpu_frame_out_$stamp.rgba")
            val pngOutput = File(getContext().cacheDir, "gpu_frame_out_$stamp.png")

            // Decode exactly one frame with FFmpeg. The frame is now an
            // ordinary RGBA buffer that can be uploaded to the GPU.
            val decodeCommand =
                "-y -i \"${inputFile.absolutePath}\" -frames:v 1 " +
                "-vf scale=320:240,format=rgba -f rawvideo \"${rawInput.absolutePath}\""

            val decodeSession = FFmpegKit.execute(decodeCommand)
            if (!ReturnCode.isSuccess(decodeSession.returnCode) ||
                !rawInput.exists() || rawInput.length() < 320L * 240L * 4L
            ) {
                result["success"] = false
                result["status"] = "GPU_FRAME_DECODE_FAILED"
                result["ffmpegLog"] = decodeSession.getAllLogsAsString().takeLast(5000)
                return result
            }

            val width = 320
            val height = 240
            val frameBytes = ByteArray(width * height * 4)
            rawInput.inputStream().use { input ->
                var offset = 0
                while (offset < frameBytes.size) {
                    val n = input.read(frameBytes, offset, frameBytes.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                if (offset != frameBytes.size) {
                    throw Exception("Short RGBA frame: $offset/${frameBytes.size} bytes")
                }
            }

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                result["success"] = false
                result["status"] = "GPU_FRAME_EGL_DISPLAY_FAILED"
                result["error"] = "eglGetDisplay failed"
                return result
            }

            val major = IntArray(1)
            val minor = IntArray(1)
            if (!EGL14.eglInitialize(display, major, 0, minor, 0)) {
                result["success"] = false
                result["status"] = "GPU_FRAME_EGL_INIT_FAILED"
                result["error"] = eglError()
                return result
            }

            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                result["success"] = false
                result["status"] = "GPU_FRAME_EGL_CONFIG_FAILED"
                result["error"] = eglError()
                return result
            }

            val contextAttrs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                contextAttrs,
                0
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                result["success"] = false
                result["status"] = "GPU_FRAME_EGL_CONTEXT_FAILED"
                result["error"] = eglError()
                return result
            }

            val surfaceAttrs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                surfaceAttrs,
                0
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE ||
                !EGL14.eglMakeCurrent(display, surface, surface, context)
            ) {
                result["success"] = false
                result["status"] = "GPU_FRAME_EGL_SURFACE_FAILED"
                result["error"] = eglError()
                return result
            }

            val vertexSource = """
                attribute vec2 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vTexCoord = aTexCoord;
                }
            """.trimIndent()

            // Deliberately obvious GPU operation: invert RGB values.
            val fragmentSource = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() {
                    vec4 c = texture2D(uTexture, vTexCoord);
                    gl_FragColor = vec4(1.0 - c.rgb, c.a);
                }
            """.trimIndent()

            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                if (shader == 0) throw Exception("glCreateShader failed")
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    throw Exception(log ?: "Shader compile failed")
                }
                return shader
            }

            val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val link = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
            if (link[0] == 0) throw Exception(GLES20.glGetProgramInfoLog(program) ?: "Program link failed")

            val textures = IntArray(2)
            GLES20.glGenTextures(2, textures, 0)
            inputTexture = textures[0]
            outputTexture = textures[1]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val inputBuffer = ByteBuffer.allocateDirect(frameBytes.size).order(ByteOrder.nativeOrder())
            inputBuffer.put(frameBytes).position(0)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, inputBuffer
            )

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null
            )

            val fb = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            framebuffer = fb[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                outputTexture,
                0
            )
            val fbStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (fbStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw Exception("Framebuffer incomplete: 0x${Integer.toHexString(fbStatus)}")
            }

            val vertices = floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            )
            val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuffer.put(vertices).position(0)

            val pos = GLES20.glGetAttribLocation(program, "aPosition")
            val tex = GLES20.glGetAttribLocation(program, "aTexCoord")
            val sampler = GLES20.glGetUniformLocation(program, "uTexture")
            if (pos < 0 || tex < 0 || sampler < 0) throw Exception("Shader attribute/uniform lookup failed")

            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
            GLES20.glUniform1i(sampler, 0)
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(tex)
            GLES20.glFinish()

            val outputBuffer = ByteBuffer.allocateDirect(frameBytes.size).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                0, 0, width, height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                outputBuffer
            )
            val glError = GLES20.glGetError()
            if (glError != GLES20.GL_NO_ERROR) throw Exception("OpenGL error: 0x${Integer.toHexString(glError)}")

            outputBuffer.position(0)
            rawOutput.outputStream().use { out ->
                val chunk = ByteArray(64 * 1024)
                while (outputBuffer.hasRemaining()) {
                    val n = minOf(outputBuffer.remaining(), chunk.size)
                    outputBuffer.get(chunk, 0, n)
                    out.write(chunk, 0, n)
                }
            }

            // Encode the GPU-produced frame to a visible image using FFmpeg.
            val encodeCommand =
                "-y -f rawvideo -pix_fmt rgba -s ${width}x${height} -i \"${rawOutput.absolutePath}\" " +
                "-frames:v 1 \"${pngOutput.absolutePath}\""
            val encodeSession = FFmpegKit.execute(encodeCommand)

            result["success"] = ReturnCode.isSuccess(encodeSession.returnCode) && pngOutput.exists()
            result["status"] = if (result["success"] == true) "OPENGL_ES_VIDEO_FRAME_GPU_WORKING" else "GPU_FRAME_ENCODE_FAILED"
            result["gpuVendor"] = GLES20.glGetString(GLES20.GL_VENDOR) ?: "UNKNOWN"
            result["gpuRenderer"] = GLES20.glGetString(GLES20.GL_RENDERER) ?: "UNKNOWN"
            result["glVersion"] = GLES20.glGetString(GLES20.GL_VERSION) ?: "UNKNOWN"
            result["width"] = width
            result["height"] = height
            result["shaderOperation"] = "RGB_INVERT"
            result["inputRgbaBytes"] = rawInput.length()
            result["outputRgbaBytes"] = rawOutput.length()
            result["outputImagePath"] = pngOutput.absolutePath
            result["message"] = "FFmpeg decoded a video frame, OpenGL ES processed it on the GPU, and FFmpeg encoded the processed frame."
            if (result["success"] != true) {
                result["ffmpegLog"] = encodeSession.getAllLogsAsString().takeLast(5000)
            }
            return result
        } catch (error: Throwable) {
            result["success"] = false
            result["status"] = "OPENGL_ES_VIDEO_FRAME_EXCEPTION"
            result["error"] = error.message ?: error.toString()
            return result
        } finally {
            try {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (inputTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
                if (outputTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(outputTexture), 0)
                if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            } catch (_: Throwable) { }
            try {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
            } catch (_: Throwable) { }
            try {
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            } catch (_: Throwable) { }
            try {
                if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            } catch (_: Throwable) { }
            try {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            } catch (_: Throwable) { }
        }
    }

    // ============================================================
    // OPENCL DIAGNOSTIC
    // ============================================================

    private fun runOpenClDiagnostic(): Map<String, Any> {

        val result =
            mutableMapOf<String, Any>()

        try {

            // ----------------------------------------------------
            // 1. FFmpeg version
            // ----------------------------------------------------

            val versionSession =
                FFmpegKit.execute(
                    "-hide_banner -version"
                )

            val versionOutput =
                versionSession.getAllLogsAsString()

            result["ffmpegVersion"] =
                versionOutput.takeLast(4000)

            // ----------------------------------------------------
            // 2. Hardware device list
            // ----------------------------------------------------

            val deviceListSession =
                FFmpegKit.execute(
                    "-hide_banner -init_hw_device list"
                )

            val deviceList =
                deviceListSession.getAllLogsAsString()

            result["hardwareDeviceList"] =
                deviceList.takeLast(6000)

            // ----------------------------------------------------
            // 3. FFmpeg build configuration
            // ----------------------------------------------------

            val buildConfSession =
                FFmpegKit.execute(
                    "-hide_banner -buildconf"
                )

            val buildConf =
                buildConfSession.getAllLogsAsString()

            result["buildConfiguration"] =
                buildConf.takeLast(6000)

            val openClEnabled =
                buildConf.contains(
                    "--enable-opencl",
                    ignoreCase = true
                )

            result["openclCompileEnabled"] =
                openClEnabled

            // ----------------------------------------------------
            // 4. Filter list
            // ----------------------------------------------------

            val filterSession =
                FFmpegKit.execute(
                    "-hide_banner -filters"
                )

            val filterOutput =
                filterSession.getAllLogsAsString()

            val openClFilters =
                filterOutput
                    .lines()
                    .filter {
                        it.contains(
                            "_opencl",
                            ignoreCase = true
                        )
                    }

            result["openclFilterCount"] =
                openClFilters.size

            result["openclFilters"] =
                openClFilters

            // ----------------------------------------------------
            // 5. Device initialization test
            // ----------------------------------------------------

            val deviceCommands =
                listOf(

                    "-hide_banner " +
                    "-init_hw_device opencl=gpu:0.0 " +
                    "-f lavfi " +
                    "-i color=c=black:s=320x240:d=1 " +
                    "-vf format=rgba,hwupload,avgblur_opencl=1,hwdownload,format=yuv420p " +
                    "-f null -",

                    "-hide_banner " +
                    "-init_hw_device opencl=gpu:0.0 " +
                    "-f lavfi " +
                    "-i color=c=black:s=320x240:d=1 " +
                    "-vf format=rgba,hwupload,boxblur_opencl=1:1,hwdownload,format=yuv420p " +
                    "-f null -"
                )

            val tests =
                mutableListOf<Map<String, Any>>()

            for (testCommand in deviceCommands) {

                val session =
                    FFmpegKit.execute(
                        testCommand
                    )

                val logs =
                    session.getAllLogsAsString()

                val success =
                    ReturnCode.isSuccess(
                        session.returnCode
                    )

                tests.add(
                    mapOf(

                        "command" to
                            testCommand,

                        "success" to
                            success,

                        "returnCode" to
                            (
                                session.returnCode
                                    ?.toString()
                                    ?: "NULL"
                            ),

                        "logs" to
                            logs.takeLast(8000),

                        "output" to
                            (
                                session.output
                                    ?: ""
                            ),

                        "failStackTrace" to
                            (
                                session.failStackTrace
                                    ?: ""
                            )
                    )
                )
            }

            result["openclTests"] =
                tests

            // ----------------------------------------------------
            // FINAL STATUS
            // ----------------------------------------------------

            val anySuccess =
                tests.any {
                    it["success"] == true
                }

            val status =
                when {

                    anySuccess ->
                        "OPENCL_WORKING"

                    !openClEnabled ->
                        "FFMPEG_BUILD_HAS_NO_OPENCL"

                    openClFilters.isEmpty() ->
                        "NO_OPENCL_FILTERS"

                    else ->
                        "OPENCL_DEVICE_OR_RUNTIME_FAILED"
                }

            result["deviceCreationSuccess"] =
                anySuccess

            result["status"] =
                status

            return result

        } catch (error: Throwable) {

            result["status"] =
                "OPENCL_DIAGNOSTIC_EXCEPTION"

            result["error"] =
                error.message
                    ?: "Unknown OpenCL diagnostic error"

            return result
        }
    }

    // ============================================================
    // NATIVE BRIDGE & MODULE DEFINITION
    // ============================================================

    init {
        try {
            System.loadLibrary("hybrid_opencl")
        } catch (_: UnsatisfiedLinkError) {
            // Native OpenCL bridge unavailable.
            // FFmpeg functionality remains available.
        }
    }

    private external fun nativeOpenClDiagnostic(): String

    private fun parseOpenClPlatforms(
        array: org.json.JSONArray?
    ): List<Map<String, Any?>> {

        if (array == null) {
            return emptyList()
        }

        val platforms =
            mutableListOf<Map<String, Any?>>()

        for (i in 0 until array.length()) {

            val platform =
                array.optJSONObject(i)
                    ?: continue

            val devices =
                mutableListOf<Map<String, Any?>>()

            val deviceArray =
                platform.optJSONArray("devices")

            if (deviceArray != null) {

                for (d in 0 until deviceArray.length()) {

                    val device =
                        deviceArray.optJSONObject(d)
                            ?: continue

                    devices.add(
                        mapOf(
                            "name" to device.optString(
                                "name",
                                "UNKNOWN"
                            ),
                            "vendor" to device.optString(
                                "vendor",
                                "UNKNOWN"
                            ),
                            "version" to device.optString(
                                "version",
                                "UNKNOWN"
                            ),
                            "driver" to device.optString(
                                "driver",
                                ""
                            )
                        )
                    )
                }
            }

            platforms.add(
                mapOf(
                    "name" to platform.optString(
                        "name",
                        "UNKNOWN"
                    ),
                    "vendor" to platform.optString(
                        "vendor",
                        "UNKNOWN"
                    ),
                    "version" to platform.optString(
                        "version",
                        "UNKNOWN"
                    ),
                    "deviceCount" to platform.optInt(
                        "deviceCount",
                        devices.size
                    ),
                    "devices" to devices
                )
            )
        }

        return platforms
    }

    override fun definition() = ModuleDefinition {

        Name("HybridFfmpeg")

        AsyncFunction("checkAndroidOpenClRuntime") {
            try {
                val json = nativeOpenClDiagnostic()

                val root = org.json.JSONObject(json)
                val candidatesJson =
                    root.optJSONArray("candidates")

                val candidates = mutableListOf<Map<String, Any?>>()

                var selectedLibrary = "NONE"
                var selectedPlatformCount = 0
                var overallStatus = "OPENCL_NOT_AVAILABLE"
                var anyLibraryLoaded = false
                var anyOpenClSymbols = false

                if (candidatesJson != null) {
                    for (i in 0 until candidatesJson.length()) {
                        val item =
                            candidatesJson.optJSONObject(i)
                                ?: continue

                        val loaded =
                            item.optBoolean("loaded", false)

                        val symbols =
                            item.optBoolean("openclSymbols", false)

                        val itemStatus =
                            item.optString(
                                "status",
                                "UNKNOWN"
                            )

                        val platformCount =
                            item.optInt(
                                "platformCount",
                                0
                            )

                        if (loaded) {
                            anyLibraryLoaded = true
                        }

                        if (symbols) {
                            anyOpenClSymbols = true
                        }

                        if (
                            itemStatus == "OPENCL_WORKING" &&
                            selectedLibrary == "NONE"
                        ) {
                            selectedLibrary =
                                item.optString(
                                    "library",
                                    "UNKNOWN"
                                )

                            selectedPlatformCount =
                                platformCount

                            overallStatus =
                                "OPENCL_WORKING"
                        }

                        val candidateMap =
                            mutableMapOf<String, Any?>()

                        val keys =
                            listOf(
                                "library",
                                "loaded",
                                "openclSymbols",
                                "status",
                                "error",
                                "platformQueryResult",
                                "platformCount",
                                "platforms"
                            )

                        for (key in keys) {
                            if (item.has(key)) {
                                candidateMap[key] =
                                    when (key) {
                                        "loaded",
                                        "openclSymbols" ->
                                            item.optBoolean(
                                                key,
                                                false
                                            )

                                        "platformQueryResult",
                                        "platformCount" ->
                                            item.optInt(
                                                key,
                                                0
                                            )

                                        "platforms" ->
                                            parseOpenClPlatforms(
                                                item.optJSONArray(
                                                    key
                                                )
                                            )

                                        else ->
                                            item.optString(
                                                key,
                                                ""
                                            )
                                    }
                            }
                        }

                        candidates.add(candidateMap)
                    }
                }

                if (overallStatus != "OPENCL_WORKING") {
                    overallStatus =
                        when {
                            !anyLibraryLoaded ->
                                "OPENCL_LIBRARY_LOAD_BLOCKED"

                            !anyOpenClSymbols ->
                                "OPENCL_SYMBOLS_NOT_FOUND"

                            else ->
                                "OPENCL_NO_PLATFORM"
                        }
                }

                mapOf(
                    "success" to true,
                    "status" to overallStatus,
                    "library" to selectedLibrary,
                    "platformCount" to selectedPlatformCount,
                    "candidateCount" to candidates.size,
                    "candidates" to candidates,
                    "anyLibraryLoaded" to anyLibraryLoaded,
                    "anyOpenClSymbols" to anyOpenClSymbols,
                    "nativeStatus" to root.optString(
                        "status",
                        "UNKNOWN"
                    ),
                    "nativeResult" to json
                )
            } catch (e: Throwable) {
                mapOf(
                    "success" to false,
                    "status" to "NATIVE_OPENCL_EXCEPTION",
                    "error" to (e.message ?: e.toString())
                )
            }
        }

        // ========================================================
        // HARDWARE CAPABILITY
        // ========================================================

        Function("getHardwareCapabilities") {

            val mediaCodecH264Available =
                try {

                    MediaCodecList(
                        MediaCodecList.ALL_CODECS
                    )
                        .codecInfos
                        .any { info ->

                            info.isEncoder &&
                                info.supportedTypes.any { type ->

                                    type.equals(
                                        "video/avc",
                                        ignoreCase = true
                                    )
                                }
                        }

                } catch (_: Throwable) {

                    false
                }

            mapOf(

                "ffmpegAvailable" to
                    true,

                "mediaCodecH264Available" to
                    mediaCodecH264Available,

                "gpuDetection" to
                    "COMMAND_DETECTION",

                "universalCommandEngine" to
                    true
            )
        }

        // ========================================================
        // OPENGL ES TEST
        // ========================================================

        AsyncFunction("checkOpenGlRuntime") {
            checkOpenGlRuntime()
        }

        AsyncFunction("testOpenGlVideoFrame") {
            inputUri: String ->
            runOpenGlVideoFrameTest(inputUri)
        }

        // ========================================================
        // OPENCL TEST
        // ========================================================

        AsyncFunction("checkOpenCl") {

            promise: Promise ->

            try {

                val result =
                    runOpenClDiagnostic()

                promise.resolve(
                    result
                )

            } catch (error: Throwable) {

                promise.reject(

                    "OPENCL_DIAGNOSTIC_FAILED",

                    error.message
                        ?: "OpenCL diagnostic failed",

                    error
                )
            }
        }

        // ========================================================
        // HARDWARE ENCODER TEST
        // ========================================================

        AsyncFunction("testHardwareEncoder") {

            inputUri: String,
            promise: Promise ->

            try {

                val inputFile =
                    copyUriToCache(
                        inputUri
                    )

                val outputFile =
                    File(
                        getContext().cacheDir,
                        "hardware_test_${System.currentTimeMillis()}.mp4"
                    )

                val command =
                    "-y " +
                    "-i \"${inputFile.absolutePath}\" " +
                    "-c:v h264_mediacodec " +
                    "-b:v 4M " +
                    "-c:a aac " +
                    "-b:a 128k " +
                    "-movflags +faststart " +
                    "\"${outputFile.absolutePath}\""

                FFmpegKit.executeAsync(
                    command
                ) { session ->

                    val returnCode =
                        session.returnCode

                    if (
                        ReturnCode.isSuccess(
                            returnCode
                        )
                    ) {

                        promise.resolve(

                            mapOf(

                                "success" to
                                    true,

                                "hardwareEncoder" to
                                    "h264_mediacodec",

                                "outputPath" to
                                    outputFile.absolutePath,

                                "command" to
                                    command,

                                "message" to
                                    "MediaCodec H.264 hardware encoding succeeded"
                            )
                        )

                    } else {

                        val diagnostic =
                            session.getAllLogsAsString()

                        promise.reject(

                            "HARDWARE_ENCODER_FAILED",

                            diagnostic.ifBlank {

                                session.failStackTrace
                                    ?: session.output
                                    ?: "Hardware encoder test failed"
                            },

                            null
                        )
                    }
                }

            } catch (error: Exception) {

                promise.reject(

                    "HARDWARE_ENCODER_EXCEPTION",

                    error.message
                        ?: "Unknown hardware encoder error",

                    error
                )
            }
        }

        // ========================================================
        // MEDIA3 GPU VIDEO ROUTING
        // ========================================================

        fun hasAudioProcessing(command: String): Boolean {
            val lower = command.lowercase()
            val audioMarkers = listOf(
                "amix", "amovie", "atempo", "aecho", "bass",
                "firequalizer", "compand", "pan=", "volume",
                "highpass", "lowpass", "acompressor", "afade"
            )
            return FfmpegCommandTokenizer.hasOption(command, "-af") ||
                audioMarkers.any { lower.contains(it) }
        }

        fun extractSimpleAudioFilter(command: String): String? {
            val filter = FfmpegCommandTokenizer.findOptionValue(command, "-af")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val complexAudio = listOf("amix", "amovie", "amerge", "pan=", "asplit", "concat=")
            if (complexAudio.any { filter.contains(it, ignoreCase = true) }) return null
            return filter
        }

        fun runMedia3VideoBackend(
            inputFile: File,
            outputFile: File,
            command: String,
            removeAudio: Boolean = false
        ): Map<String, Any>? {
            val plan = FfmpegVideoCommandParser.parse(command)
            if (!plan.supported) return null
            return Media3VideoTransformer.render(getContext(), inputFile, outputFile, plan, removeAudio)
                .toMutableMap()
                .apply {
                    this["sourceFilter"] = plan.sourceFilter
                    this["media3Plan"] = plan.effects.map { it::class.simpleName ?: "effect" }
                }
        }

        fun extractFilterComplex(command: String): String? {
            return FfmpegCommandTokenizer.findOptionValue(command, "-filter_complex")
                ?.takeIf { it.isNotBlank() }
        }

        fun splitComplexStatements(graph: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var quote: Char? = null
            var start = 0
            for (i in graph.indices) {
                val c = graph[i]
                if (quote != null) {
                    if (c == quote && (i == 0 || graph[i - 1] != '\\')) quote = null
                } else if (c == '\'' || c == '"') quote = c
                else if (c == '(' || c == '[') depth++
                else if (c == ')' || c == ']') depth--
                else if (c == ';' && depth == 0) {
                    out += graph.substring(start, i).trim()
                    start = i + 1
                }
            }
            out += graph.substring(start).trim()
            return out.filter { it.isNotBlank() }
        }

        fun extractComplexAudioGraph(command: String): String? {
            val graph = extractFilterComplex(command) ?: return null
            val statements = splitComplexStatements(graph)
            val audioMarkers = listOf(
                "atempo", "bass", "volume", "aecho", "firequalizer", "compand",
                "pan=", "highpass", "lowpass", "amix", "amerge", "amovie",
                "anull", "acompressor", "afade", "aresample", "aformat"
            )
            val selected = mutableListOf<String>()
            val knownLabels = mutableSetOf("0:a")
            var changed = true
            while (changed) {
                changed = false
                for (statement in statements) {
                    if (selected.contains(statement)) continue
                    val lower = statement.lowercase()
                    val hasAudioSource = lower.contains("[0:a]") || lower.contains("[1:a]") || lower.contains("amovie=")
                    val referencesKnown = knownLabels.any { statement.contains("[$it]") }
                    val hasAudioFilter = audioMarkers.any { lower.contains(it) }
                    if (hasAudioSource || referencesKnown || hasAudioFilter) {
                        selected += statement
                        Regex("\\[([^]]+)\\]").findAll(statement).forEach { knownLabels += it.groupValues[1] }
                        changed = true
                    }
                }
            }
            if (selected.isEmpty()) return null
            // Reject graphs that accidentally captured a video statement.
            if (selected.any { it.contains(Regex("\\[\\d+:v\\]")) || it.contains("overlay=", true) }) return null
            var last = selected.last().trim()
            if (!Regex("\\[[A-Za-z_][A-Za-z0-9_]*]\\s*$").containsMatchIn(last)) {
                last += "[hve_aout]"
                selected[selected.lastIndex] = last
            } else {
                val labelMatch = Regex("\\[([A-Za-z_][A-Za-z0-9_]*)]\\s*$").find(last)
                if (labelMatch != null) {
                    selected[selected.lastIndex] = last.substring(0, labelMatch.range.first) + "[hve_aout]"
                }
            }
            return selected.joinToString(";")
        }

        fun runComplexOverlayHybridBackend(
            inputFile: File,
            outputFile: File,
            command: String,
            plan: FfmpegComplexCommandParser.OverlayPlan
        ): Map<String, Any>? {
            val hasAudio = hasAudioProcessing(command)
            val audioGraph = if (hasAudio) extractComplexAudioGraph(command) else null
            if (hasAudio && audioGraph == null) return null

            val audioFile = File(getContext().cacheDir, "hve_composite_audio_${System.currentTimeMillis()}.m4a")
            try {
                if (audioGraph != null) {
                    val audioCommand = "-y -i \"${inputFile.absolutePath}\" -filter_complex \"$audioGraph\" -map \"[hve_aout]\" -vn -c:a aac -b:a 192k \"${audioFile.absolutePath}\""
                    val audioSession = FFmpegKit.execute(audioCommand)
                    if (!ReturnCode.isSuccess(audioSession.returnCode)) {
                        throw Exception(
                            audioSession.getAllLogsAsString().ifBlank {
                                audioSession.failStackTrace ?: "Complex audio processing failed"
                            }
                        )
                    }
                }

                val media3 = Media3OverlayComposer.render(
                    getContext(),
                    inputFile,
                    outputFile,
                    plan,
                    removeAudio = audioGraph != null,
                    externalAudioFile = audioFile.takeIf { audioGraph != null && it.exists() }
                )

                return media3.toMutableMap().apply {
                    this["videoBackend"] = "Media3 Composition + OpenGL ES"
                    this["audioBackend"] = if (audioGraph != null) "FFmpegKit -> Media3 audio sequence" else "Media3 passthrough"
                    this["hybridMode"] = if (audioGraph != null) "GPU_COMPOSITION+FFMPEG_AUDIO_IN_COMPOSITION" else "GPU_COMPOSITION"
                    this["muxBackend"] = "Media3 Transformer muxer"
                    this["filterComplexVideo"] = true
                    this["gpuPipeline"] = "MediaCodec decoders -> OpenGL ES effects -> Media3 compositor -> Media3 audio sequence -> MediaCodec encoder/muxer"
                    this["audioStorageStage"] = if (audioGraph != null) "FFmpeg-produced .m4a cache file consumed directly by Media3 composition" else "NONE"
                }
            } finally {
                audioFile.delete()
            }
        }

        /**
         * Hybrid path for commands that have a Media3-compatible video graph and
         * a simple FFmpeg -af audio filter. Video is rendered by Media3/OpenGL/
         * MediaCodec, audio is processed by FFmpegKit, then the compressed video
         * and audio are muxed without re-encoding the video.
         */
        fun runHybridVideoAudioBackend(
            inputFile: File,
            videoFile: File,
            audioFile: File,
            outputFile: File,
            command: String
        ): Map<String, Any>? {
            val plan = FfmpegVideoCommandParser.parse(command)
            val audioFilter = extractSimpleAudioFilter(command) ?: return null
            if (!plan.supported) return null

            if (audioFile.exists()) audioFile.delete()
            if (outputFile.exists()) outputFile.delete()

            val audioCommand = "-y -i \"${inputFile.absolutePath}\" -vn -sn -dn -af \"$audioFilter\" -c:a aac -b:a 192k \"${audioFile.absolutePath}\""
            val audioSession = FFmpegKit.execute(audioCommand)
            if (!ReturnCode.isSuccess(audioSession.returnCode)) {
                throw Exception(
                    audioSession.getAllLogsAsString().ifBlank {
                        audioSession.failStackTrace ?: "FFmpeg audio processing failed"
                    }
                )
            }

            val media3 = Media3VideoTransformer.render(
                getContext(),
                inputFile,
                outputFile,
                plan,
                removeAudio = true,
                externalAudioFile = audioFile
            )

            return media3.toMutableMap().apply {
                this["videoBackend"] = "Media3 Transformer + OpenGL ES"
                this["audioBackend"] = "FFmpegKit (-af) -> Media3 audio sequence"
                this["hybridMode"] = "GPU_VIDEO+FFMPEG_AUDIO_IN_COMPOSITION"
                this["audioFilter"] = audioFilter
                this["muxBackend"] = "Media3 Transformer muxer"
                this["gpuPipeline"] = "MediaCodec decoder -> OpenGL ES effects -> Media3 audio sequence -> MediaCodec encoder/muxer"
                this["audioStorageStage"] = "FFmpeg-produced .m4a cache file consumed directly by Media3 composition"
            }
        }

        // ========================================================
        // UNIVERSAL RENDER
        // ========================================================

        AsyncFunction("renderTestVideo") {

            inputUri: String,
            userCommand: String,
            promise: Promise ->

            try {

                val resolvedInput =
                    resolveInputFile(inputUri)
                val inputFile = resolvedInput.first
                val resolvedInputUri = resolvedInput.second

                val outputFile =
                    File(
                        getContext().cacheDir,
                        "ffmpeg_output_${System.currentTimeMillis()}.mp4"
                    )

                val finalCommand =
                    buildUniversalCommand(
                        userCommand,
                        inputFile,
                        outputFile
                    )

                val gpuInfo =
                    analyzeGpuRequest(
                        finalCommand
                    ).toMutableMap()

                gpuInfo["fallbackSafetyPolicy"] =
                    if (shouldPreferCpuFallback(finalCommand)) "CPU_SAFE_FOR_COMPLEX_OR_DYNAMIC_GRAPH"
                    else "MEDIA_CODEC_ALLOWED"

                try {
                    val complexOverlayPlan = FfmpegComplexCommandParser.parse(finalCommand)
                    val media3Result = if (complexOverlayPlan?.supported == true) {
                        runComplexOverlayHybridBackend(inputFile, outputFile, finalCommand, complexOverlayPlan)
                    } else {
                        val hasAudioFilter = hasAudioProcessing(finalCommand)
                        if (hasAudioFilter) {
                            val videoOnlyFile = File(getContext().cacheDir, "hve_video_${System.currentTimeMillis()}.mp4")
                            val audioFile = File(getContext().cacheDir, "hve_audio_${System.currentTimeMillis()}.m4a")
                            val hybrid = runHybridVideoAudioBackend(
                                inputFile, videoOnlyFile, audioFile, outputFile, finalCommand
                            )
                            videoOnlyFile.delete()
                            audioFile.delete()
                            hybrid
                        } else {
                            runMedia3VideoBackend(inputFile, outputFile, finalCommand, false)
                        }
                    }
                    if (media3Result != null) {
                        val result = media3Result.toMutableMap()
                        val publishedUri = publishVideoToMediaStore(outputFile)
                        result["command"] = finalCommand
                        result["originalCommand"] = userCommand
                        result["inputUri"] = resolvedInputUri
                        result["inputName"] = inputFile.name
                        result["outputUri"] = publishedUri?.toString() ?: ""
                        result["gpuRequested"] = true
                        if (!result.containsKey("hybridMode")) result["hybridMode"] = "GPU_VIDEO"
                        if (!result.containsKey("audioBackend")) result["audioBackend"] = "Media3 passthrough (audio processing not requested)"
                        promise.resolve(result)
                        return@AsyncFunction
                    }
                } catch (gpuError: Throwable) {
                    gpuInfo["gpuFallback"] = true
                    gpuInfo["gpuFallbackReason"] = gpuError.message ?: "Media3 GPU backend failed"
                    gpuInfo["fallbackReasonCode"] = classifyFallbackReason(finalCommand, gpuError.message)
                }

                val fallbackCpuSafe = shouldPreferCpuFallback(finalCommand)

                fun resolveFallbackResult(session: com.arthenica.ffmpegkit.Session, usedCommand: String, encoderStrategy: String) {
                    val returnCode = session.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        val publishedUri = publishVideoToMediaStore(outputFile)
                        val result = mutableMapOf<String, Any>(
                            "success" to true,
                            "outputPath" to outputFile.absolutePath,
                            "outputUri" to (publishedUri?.toString() ?: ""),
                            "inputUri" to resolvedInputUri,
                            "inputName" to inputFile.name,
                            "command" to usedCommand,
                            "originalCommand" to userCommand,
                            "hardwareEncoder" to encoderStrategy,
                            "videoBackend" to "FFmpegKit fallback",
                            "fallbackEncoderStrategy" to encoderStrategy,
                            "audioBackend" to "FFmpegKit",
                            "message" to "Universal FFmpeg fallback render completed"
                        )
                        result.putAll(gpuInfo)
                        promise.resolve(result)
                    } else {
                        val diagnostic = session.getAllLogsAsString().takeLast(12000)
                        val failureMessage = buildString {
                            append("FFmpeg rendering failed\n\n")
                            append("Backend: FFmpegKit fallback\n")
                            append("Encoder strategy: ").append(encoderStrategy).append('\n')
                            append("Return code: ").append(returnCode?.toString() ?: "NULL").append("\n\n")
                            append("Command used:\n").append(usedCommand)
                            if (diagnostic.isNotBlank()) append("\n\nFFmpeg log:\n").append(diagnostic)
                        }
                        promise.reject("FFMPEG_FAILED", failureMessage, null)
                    }
                }

                FFmpegKit.executeAsync(finalCommand) { session ->
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        resolveFallbackResult(session, finalCommand, if (fallbackCpuSafe) "libopenh264 (CPU_SAFE_FALLBACK)" else "h264_mediacodec")
                        return@executeAsync
                    }

                    // Automatic hardware-encoder recovery: retry the same graph once with libopenh264.
                    // Do not do this for graphs already classified as CPU-safe, because their issue
                    // is the graph itself rather than the hardware encoder.
                    if (!fallbackCpuSafe && finalCommand.contains("h264_mediacodec", ignoreCase = true)) {
                        val cpuRetryCommand = prepareCpuFallbackEncoder(finalCommand)
                        FFmpegKit.executeAsync(cpuRetryCommand) { retrySession ->
                            if (ReturnCode.isSuccess(retrySession.returnCode)) {
                                resolveFallbackResult(retrySession, cpuRetryCommand, "libopenh264 (AUTO_RETRY_AFTER_HARDWARE_FAILURE)")
                            } else {
                                val retryDiagnostic = retrySession.getAllLogsAsString().takeLast(12000)
                                val failureMessage = buildString {
                                    append("FFmpeg hardware and CPU fallback both failed\n\n")
                                    append("Hardware command:\n").append(finalCommand)
                                    append("\n\nCPU retry command:\n").append(cpuRetryCommand)
                                    append("\n\nCPU retry log:\n").append(retryDiagnostic)
                                }
                                promise.reject("FFMPEG_FAILED_AFTER_CPU_FALLBACK", failureMessage, null)
                            }
                        }
                        return@executeAsync
                    }

                    val diagnostic = session.getAllLogsAsString().takeLast(12000)
                    val failureMessage = buildString {
                        append("FFmpeg rendering failed\n\n")
                        append("Backend: FFmpegKit fallback\n")
                        append("Encoder strategy: ").append(if (fallbackCpuSafe) "libopenh264 (CPU_SAFE_FALLBACK)" else "h264_mediacodec").append('\n')
                        append("Return code: ").append(session.returnCode?.toString() ?: "NULL").append("\n\n")
                        append("Command used:\n").append(finalCommand)
                        if (diagnostic.isNotBlank()) append("\n\nFFmpeg log:\n").append(diagnostic)
                    }
                    promise.reject("FFMPEG_FAILED", failureMessage, null)
                }

            } catch (error: Exception) {

                promise.reject(

                    "FFMPEG_EXCEPTION",

                    error.message
                        ?: "Unknown FFmpeg error",

                    error
                )
            }
        }
    }
}
