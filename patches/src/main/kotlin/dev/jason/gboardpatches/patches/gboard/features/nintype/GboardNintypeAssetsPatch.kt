package dev.jason.gboardpatches.patches.gboard.features.nintype

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD
import kotlin.text.Charsets.UTF_8

private const val NINTYPE_ASSETS_RESOURCE_PREFIX = "nintype-assets"
private const val NINTYPE_ASSETS_INDEX_PATH = "$NINTYPE_ASSETS_RESOURCE_PREFIX/index.txt"
private const val APK_NINTYPE_ASSETS_DIR = "assets/nintype"

internal val gboardNintypeAssetsPatch = resourcePatch(
    description = "Copies Nintype multi-finger swipe dictionary assets into Gboard APK assets.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        copyBundledNintypeAssets()
    }
}

context(context: ResourcePatchContext)
private fun copyBundledNintypeAssets() = with(context) {
    val resourceClassLoader = object {}.javaClass.classLoader
    val indexEntries = resourceClassLoader.getResourceAsStream(NINTYPE_ASSETS_INDEX_PATH)
        ?.bufferedReader(UTF_8)
        ?.use { reader ->
            val entries = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    entries += trimmed
                }
            }
            entries
        }
        ?: error("Nintype asset index \"$NINTYPE_ASSETS_INDEX_PATH\" not found")

    check(indexEntries.isNotEmpty()) {
        "Nintype asset index \"$NINTYPE_ASSETS_INDEX_PATH\" does not list any assets"
    }

    indexEntries.forEach { relativePath ->
        val resourcePath = "$NINTYPE_ASSETS_RESOURCE_PREFIX/$relativePath"
        val assetBytes = resourceClassLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: error("Nintype asset resource \"$resourcePath\" not found")

        val targetPath = "$APK_NINTYPE_ASSETS_DIR/$relativePath"
        val targetFile = this[targetPath, false]
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { destination ->
            destination.write(assetBytes)
        }
    }
}
