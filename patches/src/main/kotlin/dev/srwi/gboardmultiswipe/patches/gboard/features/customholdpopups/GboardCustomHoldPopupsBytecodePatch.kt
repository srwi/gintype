package dev.srwi.gboardmultiswipe.patches.gboard.features.customholdpopups

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.srwi.gboardmultiswipe.patches.gboard.shared.isMethodReference
import dev.srwi.gboardmultiswipe.patches.gboard.shared.mutableClass
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.srwi.gboardmultiswipe.patches.shared.Constants.COMPATIBILITY_GBOARD

private const val SOFT_KEY_VIEW_CLASS =
    "Lcom/google/android/libraries/inputmethod/widgets/SoftKeyView;"
private const val SOFT_KEY_DEF_CLASS =
    "Lcom/google/android/libraries/inputmethod/metadata/SoftKeyDef;"
private const val TOUCH_ACTION_BUNDLE_CLASS = "Lqin;"
private const val POPUP_MANAGER_CLASS = "Lqsl;"
private const val BASIC_MOTION_EVENT_HANDLER_CLASS =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/BasicMotionEventHandler;"

internal val gboardCustomHoldPopupsBytecodePatch = bytecodePatch(
    description = "Hooks SoftKeyView.setSoftKeyDef, hold popup selection/dismissal, and key commit dispatch for customizable hold popups.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        // 1. SoftKeyView.s(SoftKeyDef, J)Z — transform SoftKeyDef before binding to SoftKeyView
        val softKeyViewClass = mutableClass(SOFT_KEY_VIEW_CLASS)
        val setSoftKeyDefMethod = softKeyViewClass.methods.firstOrNull {
            it.name == "s" &&
                it.returnType == "Z" &&
                it.parameterTypes == listOf(SOFT_KEY_DEF_CLASS, "J")
        } ?: throw PatchException(
            "Could not find s($SOFT_KEY_DEF_CLASS, J)Z in $SOFT_KEY_VIEW_CLASS",
        )
        val transformAbi = RuntimeAbiCatalog.abi(
            RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_TRANSFORM_SOFT_KEY_DEF,
        )
        val existingTransformHooks = setSoftKeyDefMethod.implementation?.instructions
            ?.count { it.isMethodReference(transformAbi.reference) } ?: 0
        if (existingTransformHooks == 0) {
            val transformInvoke = RuntimeCallEmitter.invoke(
                RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_TRANSFORM_SOFT_KEY_DEF,
                "p0, p1",
            )
            setSoftKeyDefMethod.addInstructions(
                0,
                """
                    $transformInvoke
                    move-result-object p1
                    check-cast p1, $SOFT_KEY_DEF_CLASS
                """.trimIndent(),
            )
        }

        // 2. Lqin.D(J)V (TouchActionBundle.showPopup) — notify runtime when a hold popup opens
        val touchActionBundleClass = mutableClass(TOUCH_ACTION_BUNDLE_CLASS)
        val showPopupMethod = touchActionBundleClass.methods.firstOrNull {
            it.name == "D" &&
                it.returnType == "V" &&
                it.parameterTypes == listOf("J")
        } ?: throw PatchException(
            "Could not find D(J)V in $TOUCH_ACTION_BUNDLE_CLASS",
        )
        val popupShownAbi = RuntimeAbiCatalog.abi(
            RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_ON_POPUP_SHOWN,
        )
        val existingPopupShownHooks = showPopupMethod.implementation?.instructions
            ?.count { it.isMethodReference(popupShownAbi.reference) } ?: 0
        if (existingPopupShownHooks == 0) {
            val popupShownInvoke = RuntimeCallEmitter.invoke(
                RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_ON_POPUP_SHOWN,
                "v0",
            )
            val instructions = showPopupMethod.implementation?.instructions?.toList()
                ?: throw PatchException("No implementation in $TOUCH_ACTION_BUNDLE_CLASS->D(J)V")
            val returnIndices = instructions.mapIndexedNotNull { idx, insn ->
                if (insn.opcode.name == "RETURN_VOID") idx else null
            }.sortedDescending()
            for (returnIdx in returnIndices) {
                showPopupMethod.addInstructions(returnIdx, popupShownInvoke)
            }
        }

        // 3. Lqsl.c(F, F, Z)Z — notify runtime when highlighted popup symbol updates during slide
        val popupManagerClass = mutableClass(POPUP_MANAGER_CLASS)
        val handleMoveMethod = popupManagerClass.methods.firstOrNull {
            it.name == "c" &&
                it.returnType == "Z" &&
                it.parameterTypes == listOf("F", "F", "Z")
        } ?: throw PatchException(
            "Could not find c(F, F, Z)Z in $POPUP_MANAGER_CLASS",
        )
        val selectionUpdatedAbi = RuntimeAbiCatalog.abi(
            RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_ON_POPUP_SELECTION_UPDATED,
        )
        val existingSelectionHooks = handleMoveMethod.implementation?.instructions
            ?.count { it.isMethodReference(selectionUpdatedAbi.reference) } ?: 0
        if (existingSelectionHooks == 0) {
            val selectionUpdatedInvoke = RuntimeCallEmitter.invoke(
                RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_ON_POPUP_SELECTION_UPDATED,
                "p0, p3",
            )
            val instructions = handleMoveMethod.implementation?.instructions?.toList()
                ?: throw PatchException("No implementation in $POPUP_MANAGER_CLASS->c(F, F, Z)Z")
            val returnIndices = instructions.mapIndexedNotNull { idx, insn ->
                if (insn.opcode.name == "RETURN") idx else null
            }.sortedDescending()
            for (returnIdx in returnIndices) {
                handleMoveMethod.addInstructions(returnIdx, selectionUpdatedInvoke)
            }
        }

        // 4. Lqsl.a(J)V & Lqsl.close()V — cancel 2s hold timer when popup hides or closes
        val hideAbi = RuntimeAbiCatalog.abi(
            RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_ON_POPUP_HIDDEN,
        )
        val hideInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_ON_POPUP_HIDDEN,
            "p0",
        )
        for (methodName in listOf("a", "close")) {
            val method = popupManagerClass.methods.firstOrNull {
                it.name == methodName && it.returnType == "V"
            } ?: throw PatchException("Could not find $methodName in $POPUP_MANAGER_CLASS")
            val existingHideHooks = method.implementation?.instructions
                ?.count { it.isMethodReference(hideAbi.reference) } ?: 0
            if (existingHideHooks == 0) {
                method.addInstructions(0, hideInvoke)
            }
        }

        // 5. BasicMotionEventHandler.o(...)V — intercept pencil (✎), 2s hold-to-edit suppression, and multi-char snippet commits
        val basicHandlerClass = mutableClass(BASIC_MOTION_EVENT_HANDLER_CLASS)
        val fireKeyDataMethod = basicHandlerClass.methods.firstOrNull {
            it.name == "o" &&
                it.returnType == "V" &&
                it.parameterTypes.size >= 4 &&
                it.parameterTypes[0] == TOUCH_ACTION_BUNDLE_CLASS &&
                it.parameterTypes[3] == SOFT_KEY_DEF_CLASS
        } ?: throw PatchException(
            "Could not find o(...)V in $BASIC_MOTION_EVENT_HANDLER_CLASS",
        )
        val interceptAbi = RuntimeAbiCatalog.abi(
            RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_SHOULD_INTERCEPT_KEY_DATA_DISPATCH,
        )
        val existingInterceptHooks = fireKeyDataMethod.implementation?.instructions
            ?.count { it.isMethodReference(interceptAbi.reference) } ?: 0
        if (existingInterceptHooks == 0) {
            val interceptInvoke = RuntimeCallEmitter.invoke(
                RuntimeCallId.CUSTOM_HOLD_POPUPS_RUNTIME_SHOULD_INTERCEPT_KEY_DATA_DISPATCH,
                "p0 .. p4",
            )
            fireKeyDataMethod.addInstructionsWithLabels(
                0,
                """
                    $interceptInvoke
                    move-result v8
                    if-eqz v8, :custom_hold_popups_allow_dispatch
                    return-void
                    :custom_hold_popups_allow_dispatch
                    nop
                """.trimIndent(),
            )
        }
    }
}
