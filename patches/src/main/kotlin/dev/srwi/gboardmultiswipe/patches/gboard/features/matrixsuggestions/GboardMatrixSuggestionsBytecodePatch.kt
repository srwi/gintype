package dev.srwi.gboardmultiswipe.patches.gboard.features.matrixsuggestions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import dev.srwi.gboardmultiswipe.patches.gboard.shared.findMutableMethodOrThrow
import dev.srwi.gboardmultiswipe.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.isMethodReference
import dev.srwi.gboardmultiswipe.patches.gboard.shared.mutableClass
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardMatrixSuggestionsBytecodePatch = bytecodePatch(
    description = "Injects the Matrix Word Suggestions hook into LatinFixedCountCandidatesHolderView.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        val holderClass = mutableClass(LATIN_FIXED_COUNT_CANDIDATES_HOLDER_VIEW_CLASS)

        // Resilient append-candidates lookup: match by name 'D' or parameter signature (List, ArrayList) -> void
        val appendMethod = holderClass.methods.firstOrNull {
            it.name == APPEND_CANDIDATES_METHOD_NAME &&
                it.returnType == APPEND_CANDIDATES_RETURN_TYPE &&
                it.parameterTypes == listOf("Ljava/util/List;", "Ljava/util/ArrayList;")
        } ?: holderClass.methods.firstOrNull {
            it.returnType == APPEND_CANDIDATES_RETURN_TYPE &&
                it.parameterTypes == listOf("Ljava/util/List;", "Ljava/util/ArrayList;")
        } ?: error("Could not find append candidates method in $LATIN_FIXED_COUNT_CANDIDATES_HOLDER_VIEW_CLASS")

        appendMethod.applyMatrixSuggestionsAppendDelegate()

        // Resilient clear-candidates lookup: match by name 'l' or clear signature () -> void
        val clearMethod = holderClass.methods.firstOrNull {
            it.name == CLEAR_CANDIDATES_METHOD_NAME &&
                it.returnType == CLEAR_CANDIDATES_RETURN_TYPE &&
                it.parameterTypes.isEmpty()
        } ?: holderClass.methods.firstOrNull {
            it.returnType == CLEAR_CANDIDATES_RETURN_TYPE &&
                it.parameterTypes.isEmpty() &&
                !it.name.startsWith("<") &&
                it.name != "onDetachedFromWindow" &&
                it.name != "onAttachedToWindow"
        } ?: error("Could not find clear candidates method in $LATIN_FIXED_COUNT_CANDIDATES_HOLDER_VIEW_CLASS")

        clearMethod.applyMatrixSuggestionsClearDelegate()
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
