package dev.srwi.gboardmultiswipe.patches.gboard.features.multiswipe

import app.morphe.patcher.patch.resourcePatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.applyFeatureMarker
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardMultiSwipeFeatureMarkerPatch = resourcePatch(
    description = "Marks MultiSwipe multi-finger swipe patch availability in AndroidManifest metadata.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        applyFeatureMarker(MULTISWIPE_FEATURE_MARKER_NAME)
    }
}

internal const val MULTISWIPE_FEATURE_MARKER_NAME =
    "dev.srwi.gboardmultiswipe.feature.multiswipe"
