package expo.modules.hybridffmpeg

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object FfmpegAssetResolver {
    class AssetResult(
        val command: String,
        val missingAssets: List<String>
    )

    fun resolve(command: String, context: Context): AssetResult {
        val missing = mutableListOf<String>()
        val assetPattern = Regex("(?i)(\\b(?:amovie|movie)\\s*=\\s*)(?:'([^']+)'|\"([^\"]+)\"|([^,:;\\]\\s]+))")
        
        val newCommand = assetPattern.replace(command) { match ->
            val prefix = match.groupValues[1]
            val rawPath = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[4] }.trim()
            
            val resolvedPath = findAsset(rawPath, context)
            if (resolvedPath == null) {
                missing.add(rawPath)
                match.value
            } else {
                prefix + "'" + resolvedPath + "'"
            }
        }
        return AssetResult(newCommand, missing)
    }

    private fun findAsset(path: String, context: Context): String? {
        val cleanPath = path.removePrefix("/").removePrefix("./")
        val candidates = listOf(
            File(context.filesDir, cleanPath),
            File(context.cacheDir, cleanPath),
            File(context.getExternalFilesDir(null), cleanPath),
            File("/storage/emulated/0/Bypass", cleanPath),
            File("/storage/emulated/0", cleanPath)
        )
        candidates.firstOrNull { it.isFile && it.canRead() }?.let { return it.absolutePath }

        return try {
            val assetManager = context.assets
            val safeName = cleanPath.replace("/", "_")
            val outputFile = File(context.cacheDir, "asset_$safeName")
            if (!outputFile.exists() || outputFile.length() == 0L) {
                assetManager.open(cleanPath).use { input ->
                    FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                }
            }
            outputFile.absolutePath
        } catch (_: Throwable) { null }
    }
}