package dev.jason.gboardpatches.patches.gboard.registry

import dev.jason.gboardpatches.patches.gboard.features.matrixsuggestions.gboardMatrixSuggestionsBytecodePatch
import dev.jason.gboardpatches.patches.gboard.features.matrixsuggestions.gboardMatrixSuggestionsFeatureMarkerPatch
import dev.jason.gboardpatches.patches.gboard.features.nintype.gboardNintypeBytecodePatch
import dev.jason.gboardpatches.patches.gboard.features.nintype.gboardNintypeFeatureMarkerPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardRuntimePatchesContractTest {
    @Test
    fun `remaining features are independently published and enabled by default`() {
        val registrations = GboardPublishedPatchCatalog.morpheRegistrations
        val matrix = registrations.single { patch ->
            patch.name == "Matrix Word Suggestions"
        }
        val nintype = registrations.single { patch -> patch.name == "Nintype Multi-Touch Swipe" }

        assertSame(gboardMatrixSuggestionsPatch, matrix)
        assertSame(gboardNintypePatch, nintype)

        val generated = generatedPublishedPatches().associateBy { patch ->
            patch["name"].asString
        }
        assertTrue(generated.getValue(matrix.name!!)["use"].asBoolean)
        assertTrue(generated.getValue(nintype.name!!)["use"].asBoolean)
    }

    @Test
    fun `each public patch owns only its feature marker and runtime contributions`() {
        assertTrue(gboardMatrixSuggestionsPatch.dependencies.any { dependency ->
            dependency === gboardMatrixSuggestionsFeatureMarkerPatch
        })
        assertTrue(gboardMatrixSuggestionsPatch.dependencies.any { dependency ->
            dependency === gboardMatrixSuggestionsBytecodePatch
        })

        assertTrue(gboardNintypePatch.dependencies.any { dependency ->
            dependency === gboardNintypeFeatureMarkerPatch
        })
        assertTrue(gboardNintypePatch.dependencies.any { dependency ->
            dependency === gboardNintypeBytecodePatch
        })
    }

    @Test
    fun `public descriptions match the requested bilingual product copy`() {
        assertEquals(
            "Vertikale Wortvorschläge mit Up/Down-Swipe und 2D-Satzverlauf.\n" +
                "Vertical word suggestions with up/down swipe and 2D sentence matrix.",
            gboardMatrixSuggestionsPatch.description,
        )
        assertEquals(
            "Zwei-Finger-Swipe und Tap+Swipe-Eingabe nach dem Nintype-Algorithmus mit Gboard-Wörterbuch.\n" +
                "Two-finger swipe and tap+swipe chorded gesture typing powered by the Nintype algorithm and Gboard dictionary.",
            gboardNintypePatch.description,
        )
    }
}
