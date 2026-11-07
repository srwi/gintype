package dev.jason.gboardpatches.patches.gboard.features.matrixsuggestions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import dev.jason.gboardpatches.patches.gboard.shared.findMutableMethodOrThrow
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.jason.gboardpatches.patches.gboard.shared.isMethodReference
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardMatrixSuggestionsBytecodePatch = bytecodePatch(
    description = "Injiziert den Matrix Word Suggestions Hook in LatinFixedCountCandidatesHolderView.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        findMutableMethodOrThrow(
            classType = LATIN_FIXED_COUNT_CANDIDATES_HOLDER_VIEW_CLASS,
            name = APPEND_CANDIDATES_METHOD_NAME,
            returnType = APPEND_CANDIDATES_RETURN_TYPE,
            parameterTypes = listOf(
                "Ljava/util/List;",
                "Ljava/util/ArrayList;",
            ),
        ).applyMatrixSuggestionsAppendDelegate()

        findMutableMethodOrThrow(
            classType = LATIN_FIXED_COUNT_CANDIDATES_HOLDER_VIEW_CLASS,
            name = CLEAR_CANDIDATES_METHOD_NAME,
            returnType = CLEAR_CANDIDATES_RETURN_TYPE,
            parameterTypes = emptyList(),
        ).applyMatrixSuggestionsClearDelegate()
    }
}

internal fun MutableMethod.applyMatrixSuggestionsAppendDelegate() {
    val abi = RuntimeAbiCatalog.abi(
        RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_UPDATED,
    )
    val instructions = implementation?.instructions
        ?: error("No instructions in $definingClass->$name")
    val existing = instructions.count { it.isMethodReference(abi.reference) }
    if (existing > 0) {
        check(existing == 1 && instructions.first().isMethodReference(abi.reference)) {
            "Malformed matrix suggestions append delegate in $definingClass->$name"
        }
        return
    }
    addInstructions(
        0,
        RuntimeCallEmitter.invoke(
            RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_UPDATED,
            "p0 .. p1",
        ),
    )
}

internal fun MutableMethod.applyMatrixSuggestionsClearDelegate() {
    val abi = RuntimeAbiCatalog.abi(
        RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED,
    )
    val instructions = implementation?.instructions
        ?: error("No instructions in $definingClass->$name")
    val existing = instructions.count { it.isMethodReference(abi.reference) }
    if (existing > 0) {
        check(existing == 1 && instructions.first().isMethodReference(abi.reference)) {
            "Malformed matrix suggestions clear delegate in $definingClass->$name"
        }
        return
    }
    addInstructions(
        0,
        RuntimeCallEmitter.invoke(
            RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED,
            "p0",
        ),
    )
}

private const val LATIN_FIXED_COUNT_CANDIDATES_HOLDER_VIEW_CLASS =
    "Lcom/google/android/apps/inputmethod/latin/keyboard/widget/LatinFixedCountCandidatesHolderView;"
private const val APPEND_CANDIDATES_METHOD_NAME = "D"
private const val APPEND_CANDIDATES_RETURN_TYPE = "V"
private const val CLEAR_CANDIDATES_METHOD_NAME = "l"
private const val CLEAR_CANDIDATES_RETURN_TYPE = "V"
