package dev.jason.gboardpatches.patches.gboard.features.matrixsuggestions

import app.morphe.patcher.patch.resourcePatch
import dev.jason.gboardpatches.patches.gboard.shared.applyFeatureMarker
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardMatrixSuggestionsFeatureMarkerPatch = resourcePatch(
    description = "Markiert Matrix Word Suggestions (vertikales Scrollen & Wortverlauf) als aktiv in der target APK.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        applyFeatureMarker(MATRIX_SUGGESTIONS_FEATURE_MARKER_NAME)
    }
}

internal const val MATRIX_SUGGESTIONS_FEATURE_MARKER_NAME =
    "dev.jason.gboardpatches.feature.matrix_suggestions"
