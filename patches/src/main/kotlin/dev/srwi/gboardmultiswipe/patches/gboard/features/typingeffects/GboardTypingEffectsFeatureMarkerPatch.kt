package dev.srwi.gboardmultiswipe.patches.gboard.features.typingeffects

import app.morphe.patcher.patch.resourcePatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.applyFeatureMarker
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardTypingEffectsFeatureMarkerPatch = resourcePatch(
    description = "Marks Typing Effects patch availability in AndroidManifest metadata.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        applyFeatureMarker(TYPING_EFFECTS_FEATURE_MARKER_NAME)
    }
}

internal const val TYPING_EFFECTS_FEATURE_MARKER_NAME =
    "dev.srwi.gboardmultiswipe.feature.typing_effects"
