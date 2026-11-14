package dev.srwi.gboardmultiswipe.patches.gboard.features.thicctopbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import dev.srwi.gboardmultiswipe.patches.gboard.shared.addHelperMethodIfMissing
import dev.srwi.gboardmultiswipe.patches.gboard.shared.findMutableMethodOrThrow
import dev.srwi.gboardmultiswipe.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.instructionIndices
import dev.srwi.gboardmultiswipe.patches.gboard.shared.isMethodReference
import dev.srwi.gboardmultiswipe.patches.gboard.shared.mutableClass
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardThiccTopBarBytecodePatch = bytecodePatch(
    description = "Injects the Thicc Top Bar hook into LatinFixedCountCandidatesHolderView.",
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

        val softKeyboardViewClass = mutableClass(SOFT_KEYBOARD_VIEW_CLASS)
        val onFinishInflateMethod = softKeyboardViewClass.methods.firstOrNull {
            it.name == "onFinishInflate" &&
                it.returnType == "V" &&
                it.parameterTypes.isEmpty()
        } ?: throw PatchException(
            "Could not find onFinishInflate() in $SOFT_KEYBOARD_VIEW_CLASS",
        )
        onFinishInflateMethod.applySoftKeyboardViewInflateDelegate()

        softKeyboardViewClass.applyHeaderTargetHeightWrapper()
    }
}

internal fun MutableMethod.applyMatrixSuggestionsAppendDelegate() {
    val abi = RuntimeAbiCatalog.abi(
        RuntimeCallId.THICC_TOP_BAR_RUNTIME_ON_CANDIDATES_UPDATED,
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
            RuntimeCallId.THICC_TOP_BAR_RUNTIME_ON_CANDIDATES_UPDATED,
            "p0 .. p1",
        ),
    )
}

internal fun MutableMethod.applyMatrixSuggestionsClearDelegate() {
    val abi = RuntimeAbiCatalog.abi(
        RuntimeCallId.THICC_TOP_BAR_RUNTIME_ON_CANDIDATES_CLEARED,
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
            RuntimeCallId.THICC_TOP_BAR_RUNTIME_ON_CANDIDATES_CLEARED,
            "p0",
        ),
    )
}

internal fun MutableMethod.applySoftKeyboardViewInflateDelegate() {
    val abi = RuntimeAbiCatalog.abi(
        RuntimeCallId.THICC_TOP_BAR_RUNTIME_ON_SOFT_KEYBOARD_VIEW_INFLATED,
    )
    val instructions = implementation?.instructions
        ?: error("No instructions in $definingClass->$name")
    val returnIndices = instructionIndices("RETURN_VOID")
    check(returnIndices.isNotEmpty()) {
        "No return-void instruction in $definingClass->$name"
    }
    val existing = instructions.count { it.isMethodReference(abi.reference) }
    if (existing > 0) {
        check(existing == returnIndices.size) {
            "Expected $definingClass->$name to match ${returnIndices.size} return hooks, found $existing"
        }
        return
    }
    val invokeSmali = RuntimeCallEmitter.invoke(
        RuntimeCallId.THICC_TOP_BAR_RUNTIME_ON_SOFT_KEYBOARD_VIEW_INFLATED,
        "p0",
    )
    returnIndices.asReversed().forEach { retIdx ->
        addInstructions(retIdx, invokeSmali)
    }
}

internal fun MutableClass.applyHeaderTargetHeightWrapper() {
    val scaleAbi = RuntimeAbiCatalog.abi(
        RuntimeCallId.THICC_TOP_BAR_RUNTIME_SCALE_HEADER_TARGET_HEIGHT,
    )
    val targetMethod = methods.firstOrNull {
        it.name == TARGET_HEIGHT_METHOD_NAME &&
            it.parameterTypes.isEmpty() &&
            it.returnType == "I" &&
            (it.accessFlags and AccessFlags.STATIC.value) == 0
    } ?: throw PatchException(
        "Could not find $TARGET_HEIGHT_METHOD_NAME()I in $type",
    )

    val existingHelper = methods.any { it.name == ORIG_TARGET_HEIGHT_HELPER_NAME }
    val existingScaleCalls = targetMethod.implementation?.instructions
        ?.count { it.isMethodReference(scaleAbi.reference) }
        ?: 0
    if (existingHelper || existingScaleCalls > 0) {
        check(existingHelper && existingScaleCalls == 1) {
            "Expected $type->$TARGET_HEIGHT_METHOD_NAME()I to be wrapped once"
        }
        return
    }

    val origImpl = targetMethod.implementation
        ?: throw PatchException("No instructions in ${targetMethod.definingClass}->${targetMethod.name}")
    methods.add(
        MutableMethod(
            ImmutableMethod(
                type,
                ORIG_TARGET_HEIGHT_HELPER_NAME,
                emptyList(),
                "I",
                AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                targetMethod.annotations,
                null,
                ImmutableMethodImplementation.of(origImpl),
            ),
        ),
    )

    val origAccessFlags = targetMethod.accessFlags
    val origName = targetMethod.name
    methods.remove(targetMethod)
    val scaleInvokeSmali = RuntimeCallEmitter.invoke(
        RuntimeCallId.THICC_TOP_BAR_RUNTIME_SCALE_HEADER_TARGET_HEIGHT,
        "p0, v0",
    )
    addHelperMethodIfMissing(
        name = origName,
        parameterTypes = emptyList(),
        returnType = "I",
        accessFlags = origAccessFlags,
        registerCount = 2,
        body = """
            invoke-direct {p0}, $type->$ORIG_TARGET_HEIGHT_HELPER_NAME()I
            move-result v0
            $scaleInvokeSmali
            move-result v0
            return v0
        """.trimIndent(),
    )
}

private const val LATIN_FIXED_COUNT_CANDIDATES_HOLDER_VIEW_CLASS =
    "Lcom/google/android/apps/inputmethod/latin/keyboard/widget/LatinFixedCountCandidatesHolderView;"
private const val APPEND_CANDIDATES_METHOD_NAME = "D"
private const val APPEND_CANDIDATES_RETURN_TYPE = "V"
private const val CLEAR_CANDIDATES_METHOD_NAME = "l"
private const val CLEAR_CANDIDATES_RETURN_TYPE = "V"
private const val SOFT_KEYBOARD_VIEW_CLASS =
    "Lcom/google/android/libraries/inputmethod/widgets/SoftKeyboardView;"
private const val TARGET_HEIGHT_METHOD_NAME = "z"
private const val ORIG_TARGET_HEIGHT_HELPER_NAME = "gboardPatchesOriginalTargetHeight"
