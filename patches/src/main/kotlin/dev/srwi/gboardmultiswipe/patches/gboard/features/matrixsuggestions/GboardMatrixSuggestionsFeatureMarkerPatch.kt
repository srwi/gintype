package dev.srwi.gboardmultiswipe.patches.gboard.features.matrixsuggestions

import app.morphe.patcher.patch.resourcePatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.applyFeatureMarker
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardMatrixSuggestionsFeatureMarkerPatch = resourcePatch(
    description = "Markiert Matrix Word Suggestions (vertikales Scrollen & Wortverlauf) als aktiv in der target APK.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        applyFeatureMarker(MATRIX_SUGGESTIONS_FEATURE_MARKER_NAME)
    }
}

internal const val MATRIX_SUGGESTIONS_FEATURE_MARKER_NAME =
    "dev.srwi.gboardmultiswipe.feature.matrix_suggestions"
