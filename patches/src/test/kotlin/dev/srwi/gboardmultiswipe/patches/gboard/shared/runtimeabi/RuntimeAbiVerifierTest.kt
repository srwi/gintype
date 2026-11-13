package dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeAbiVerifierTest {
    @Test
    fun `reports a missing runtime method through the ABI verification seam`() {
        val inventory = RuntimeArtifactInventory { _, _ -> emptyList() }

        val mismatches = RuntimeAbiVerifier.verify(
            calls = listOf(RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED),
            inventory = inventory,
        )

        assertEquals(
            listOf(
                RuntimeAbiMismatch.MissingMethod(
                    RuntimeAbi(
                        owner = "Ldev/srwi/gboardmultiswipe/extension/matrixsuggestions/GboardMatrixSuggestionsRuntime;",
                        name = "onCandidatesCleared",
                        parameters = listOf("Ljava/lang/Object;"),
                        returnType = "V",
                        isStatic = true,
                    )
                )
            ),
            mismatches,
        )
    }

    @Test
    fun `reports a runtime method with the wrong descriptor`() {
        val inventory = RuntimeArtifactInventory { owner, name ->
            listOf(
                RuntimeArtifactMethod(
                    owner = owner,
                    name = name,
                    descriptor = "()Z",
                    isStatic = true,
                )
            )
        }

        val mismatches = RuntimeAbiVerifier.verify(
            calls = listOf(RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED),
            inventory = inventory,
        )

        assertEquals(
            listOf(
                RuntimeAbiMismatch.WrongDescriptor(
                    expected = RuntimeAbiCatalog.abi(
                        RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED
                    ),
                    actualDescriptors = listOf("()Z"),
                )
            ),
            mismatches,
        )
    }

    @Test
    fun `reports a runtime method with the wrong static access`() {
        val expected = RuntimeAbiCatalog.abi(
            RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED
        )
        val inventory = RuntimeArtifactInventory { owner, name ->
            listOf(
                RuntimeArtifactMethod(
                    owner = owner,
                    name = name,
                    descriptor = expected.descriptor,
                    isStatic = false,
                )
            )
        }

        val mismatches = RuntimeAbiVerifier.verify(
            calls = listOf(RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED),
            inventory = inventory,
        )

        assertEquals(
            listOf(
                RuntimeAbiMismatch.WrongStaticAccess(
                    expected = expected,
                    actualIsStatic = false,
                )
            ),
            mismatches,
        )
    }
}
