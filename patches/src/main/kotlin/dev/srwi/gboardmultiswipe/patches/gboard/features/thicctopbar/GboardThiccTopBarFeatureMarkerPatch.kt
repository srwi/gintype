package dev.srwi.gboardmultiswipe.patches.gboard.features.thicctopbar

import app.morphe.patcher.patch.resourcePatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.applyFeatureMarker
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardThiccTopBarFeatureMarkerPatch = resourcePatch(
    description = "Marks Thicc Top Bar patch availability in AndroidManifest metadata.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        applyFeatureMarker(THICC_TOP_BAR_FEATURE_MARKER_NAME)
    }
}

internal const val THICC_TOP_BAR_FEATURE_MARKER_NAME =
    "dev.srwi.gboardmultiswipe.feature.thicc_top_bar"
