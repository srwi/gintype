package dev.srwi.gboardmultiswipe.patches.gboard.features.customholdpopups

import app.morphe.patcher.patch.resourcePatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.applyFeatureMarker
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardCustomHoldPopupsFeatureMarkerPatch = resourcePatch(
    description = "Marks Custom Hold Popups patch availability in AndroidManifest metadata.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        applyFeatureMarker(CUSTOM_HOLD_POPUPS_FEATURE_MARKER_NAME)
    }
}

internal const val CUSTOM_HOLD_POPUPS_FEATURE_MARKER_NAME =
    "dev.srwi.gboardmultiswipe.feature.custom_hold_popups"
