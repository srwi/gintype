package dev.srwi.gintype.patches.gboard.shared

import app.morphe.patcher.patch.bytecodePatch

internal val gboardPatchesExtensionCarrierPatch = bytecodePatch(
    description = "Merge GinType runtime extension into target APK.",
) {
    extendWith("extensions/gintype.rve")
}
