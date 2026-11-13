package dev.jason.gboardpatches.patches.gboard.features.nintype

import app.morphe.patcher.patch.resourcePatch
import dev.jason.gboardpatches.patches.gboard.shared.applyFeatureMarker
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardNintypeFeatureMarkerPatch = resourcePatch(
    description = "Marks Nintype multi-finger swipe patch availability in AndroidManifest metadata.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        applyFeatureMarker(NINTYPE_FEATURE_MARKER_NAME)
    }
}

internal const val NINTYPE_FEATURE_MARKER_NAME =
    "dev.jason.gboardpatches.feature.nintype"
