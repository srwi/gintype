package dev.srwi.gboardmultiswipe.patches.gboard.registry

import dev.srwi.gboardmultiswipe.patches.gboard.features.thicctopbar.gboardThiccTopBarBytecodePatch
import dev.srwi.gboardmultiswipe.patches.gboard.features.thicctopbar.gboardThiccTopBarFeatureMarkerPatch
import dev.srwi.gboardmultiswipe.patches.gboard.features.multiswipe.gboardMultiSwipeBytecodePatch
import dev.srwi.gboardmultiswipe.patches.gboard.features.multiswipe.gboardMultiSwipeFeatureMarkerPatch
import dev.srwi.gboardmultiswipe.patches.gboard.features.typingeffects.gboardTypingEffectsBytecodePatch
import dev.srwi.gboardmultiswipe.patches.gboard.features.typingeffects.gboardTypingEffectsFeatureMarkerPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardRuntimePatchesContractTest {
    @Test
    fun `remaining features are independently published and enabled by default`() {
        val registrations = GboardPublishedPatchCatalog.morpheRegistrations
        val matrix = registrations.single { patch ->
            patch.name == "Thicc Top Bar"
        }
        val multiswipe = registrations.single { patch -> patch.name == "MultiSwipe" }
        val typingEffects = registrations.single { patch -> patch.name == "Typing Effects" }

        assertSame(gboardThiccTopBarPatch, matrix)
        assertSame(gboardMultiSwipePatch, multiswipe)
        assertSame(gboardTypingEffectsPatch, typingEffects)

        val generated = generatedPublishedPatches().associateBy { patch ->
            patch["name"].asString
        }
        assertTrue(generated.getValue(matrix.name!!)["use"].asBoolean)
        assertTrue(generated.getValue(multiswipe.name!!)["use"].asBoolean)
        assertTrue(generated.getValue(typingEffects.name!!)["use"].asBoolean)
    }

    @Test
    fun `each public patch owns only its feature marker and runtime contributions`() {
        assertTrue(gboardThiccTopBarPatch.dependencies.any { dependency ->
            dependency === gboardThiccTopBarFeatureMarkerPatch
        })
        assertTrue(gboardThiccTopBarPatch.dependencies.any { dependency ->
            dependency === gboardThiccTopBarBytecodePatch
        })

        assertTrue(gboardMultiSwipePatch.dependencies.any { dependency ->
            dependency === gboardMultiSwipeFeatureMarkerPatch
        })
        assertTrue(gboardMultiSwipePatch.dependencies.any { dependency ->
            dependency === gboardMultiSwipeBytecodePatch
        })

        assertTrue(gboardTypingEffectsPatch.dependencies.any { dependency ->
            dependency === gboardTypingEffectsFeatureMarkerPatch
        })
        assertTrue(gboardTypingEffectsPatch.dependencies.any { dependency ->
            dependency === gboardTypingEffectsBytecodePatch
        })
    }

    @Test
    fun `public descriptions match the requested bilingual product copy`() {
        assertEquals(
            "Vertical word suggestions with up/down swipe and 2D sentence matrix.",
            gboardThiccTopBarPatch.description,
        )
        assertEquals(
            "Two-finger swipe and tap+swipe chorded gesture typing powered by the Kinetica algorithm and Gboard dictionary.",
            gboardMultiSwipePatch.description,
        )
    }
}
