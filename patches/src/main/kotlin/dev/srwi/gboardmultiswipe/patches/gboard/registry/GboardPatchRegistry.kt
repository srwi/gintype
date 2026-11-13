package dev.srwi.gboardmultiswipe.patches.gboard.registry

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.ResourcePatchBuilder
import app.morphe.patcher.patch.resourcePatch
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.srwi.gboardmultiswipe.patches.gboard.features.matrixsuggestions.gboardMatrixSuggestionsBytecodePatch
import dev.srwi.gboardmultiswipe.patches.gboard.features.matrixsuggestions.gboardMatrixSuggestionsFeatureMarkerPatch
import dev.srwi.gboardmultiswipe.patches.gboard.features.nintype.gboardNintypeBytecodePatch
import dev.srwi.gboardmultiswipe.patches.gboard.features.nintype.gboardNintypeFeatureMarkerPatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.gboardPatchesSettingsPatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.generated.GboardTargetAdmission
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

private fun gboardPublicResourcePatch(
    featureId: String,
    name: String,
    description: String,
    default: Boolean,
    block: ResourcePatchBuilder.() -> Unit,
) = GboardTargetAdmission.admittedContributions(featureId).let { admitted ->
    resourcePatch(
        name = name.takeIf { admitted.isNotEmpty() },
        description = description,
        default = default,
    ) {
        if (admitted.isNotEmpty()) {
            block()
            GboardContributionWiring.wireContributions(featureId, admitted, this)
        }
    }
}

@Suppress("unused")
val gboardMatrixSuggestionsPatch = gboardPublicResourcePatch(
    featureId = "matrix_word_suggestions",
    name = "Matrix Word Suggestions",
    description = "Vertical word suggestions with up/down swipe and 2D sentence matrix.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(
        gboardPatchesSettingsPatch,
        gboardMatrixSuggestionsFeatureMarkerPatch,
        gboardMatrixSuggestionsBytecodePatch,
    )
}

@Suppress("unused")
val gboardNintypePatch = gboardPublicResourcePatch(
    featureId = "nintype_multi_touch",
    name = "Nintype Multi-Touch Swipe",
    description = "Two-finger swipe and tap+swipe chorded gesture typing powered by the Nintype algorithm and Gboard dictionary.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(
        gboardPatchesSettingsPatch,
        gboardNintypeFeatureMarkerPatch,
        gboardNintypeBytecodePatch,
    )
}

object GboardPublishedPatchCatalog {
    val morpheRegistrations: Set<Patch<*>> = linkedSetOf(
        gboardMatrixSuggestionsPatch,
        gboardNintypePatch,
    )

    fun publishedInventory(version: String): String {
        val publishedPatches = morpheRegistrations.mapNotNull { patch ->
            patch.name ?: return@mapNotNull null
            PublishedPatch(
                name = patch.name!!,
                description = patch.description,
                use = patch.default,
                dependencies = patch.dependencies.mapNotNull { dependency -> dependency.name }.sorted(),
                compatiblePackages = patch.compatibility?.mapNotNull { compatibility ->
                    compatibility.publishedPackageVersions()
                }?.toMap(),
                options = patch.options.values.map { option ->
                    PublishedPatch.Option(
                        key = option.key,
                        title = option.title,
                        description = option.description,
                        required = option.required,
                        type = option.type.toString(),
                        default = option.default,
                        values = option.values,
                    )
                },
            )
        }
        val gson = GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()
        val inventory = JsonObject().apply {
            addProperty("version", version)
            add("patches", gson.toJsonTree(publishedPatches))
        }

        return gson.toJson(inventory) + "\n"
    }

    private fun Compatibility.publishedPackageVersions(): Pair<String, Set<String>?>? {
        val packageName = packageName ?: return null
        val listedTargets = targets.filter { target -> !target.isExperimental }.ifEmpty { targets }
        if (listedTargets.isEmpty() || listedTargets.any { target -> target.version == null }) {
            return packageName to null
        }
        return packageName to listedTargets.mapTo(linkedSetOf()) { target -> target.version!! }
    }

    private class PublishedPatch(
        val name: String,
        val description: String?,
        val use: Boolean,
        val dependencies: List<String>,
        val compatiblePackages: Map<String, Set<String>?>?,
        val options: List<Option>,
    ) {
        class Option(
            val key: String,
            val title: String?,
            val description: String?,
            val required: Boolean,
            val type: String,
            val default: Any?,
            val values: Map<String, Any?>?,
        )
    }
}
