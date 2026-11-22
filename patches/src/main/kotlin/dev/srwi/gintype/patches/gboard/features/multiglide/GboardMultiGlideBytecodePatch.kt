package dev.srwi.gintype.patches.gboard.features.multiglide

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.srwi.gintype.patches.gboard.shared.addHelperMethodIfMissing
import dev.srwi.gintype.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.srwi.gintype.patches.gboard.shared.instructionIndices
import dev.srwi.gintype.patches.gboard.shared.isMethodReference
import dev.srwi.gintype.patches.gboard.shared.mutableClass
import dev.srwi.gintype.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.srwi.gintype.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.srwi.gintype.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.srwi.gintype.patches.shared.Constants.COMPATIBILITY_GBOARD

private const val ABSTRACT_MOTION_EVENT_HANDLER_CLASS =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/AbstractMotionEventHandler;"
private const val ABSTRACT_GESTURE_HANDLER_CLASS =
    "Lcom/google/android/apps/inputmethod/libs/gestureui/AbstractGestureMotionEventHandler;"
private const val BASIC_MOTION_EVENT_HANDLER_CLASS =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/BasicMotionEventHandler;"
private const val SOFT_KEY_VIEW_CLASS =
    "Lcom/google/android/libraries/inputmethod/widgets/SoftKeyView;"
private const val SOFT_KEYBOARD_VIEW_CLASS =
    "Lcom/google/android/libraries/inputmethod/widgets/SoftKeyboardView;"
private const val SOFT_KEY_DEF_CLASS =
    "Lcom/google/android/libraries/inputmethod/metadata/SoftKeyDef;"

private const val HANDLE_GESTURE_MOTION_EVENT_METHOD_NAME = "g"
private const val SHOULD_START_GESTURE_METHOD_NAME = "t"
private const val RESET_GESTURE_STATE_METHOD_NAME = "B"

internal val gboardMultiGlideBytecodePatch = bytecodePatch(
    description = "Hooks Gboard's gesture motion handler to decode MultiGlide two-finger swipe and tap+swipe gestures.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        val handlerClass = mutableClass(ABSTRACT_GESTURE_HANDLER_CLASS)

        val handleGestureMethod = handlerClass.methods.firstOrNull {
            it.name == HANDLE_GESTURE_MOTION_EVENT_METHOD_NAME &&
                it.returnType == "V" &&
                it.parameterTypes == listOf("Landroid/view/MotionEvent;")
        } ?: handlerClass.methods.firstOrNull {
            it.returnType == "V" &&
                it.parameterTypes == listOf("Landroid/view/MotionEvent;")
        } ?: throw PatchException(
            "Could not find handle gesture motion event method in $ABSTRACT_GESTURE_HANDLER_CLASS",
        )

        val insns = handleGestureMethod.instructions
        val firstMove = insns.getOrNull(0) as? TwoRegisterInstruction
        val secondMove = insns.getOrNull(1) as? TwoRegisterInstruction
        if (
            firstMove == null ||
            secondMove == null ||
            firstMove.opcode != Opcode.MOVE_OBJECT_FROM16 ||
            secondMove.opcode != Opcode.MOVE_OBJECT_FROM16
        ) {
            throw PatchException(
                "Expected leading move-object/from16 instructions in AbstractGestureMotionEventHandler.g(MotionEvent).",
            )
        }
        val thisReg = firstMove.registerA
        val eventReg = secondMove.registerA
        if (thisReg > 15 || eventReg > 15) {
            throw PatchException(
                "Expected 4-bit registers for this/event in AbstractGestureMotionEventHandler.g(MotionEvent), got v$thisReg, v$eventReg.",
            )
        }

        // Resolve the `letterStarted` boolean field written right after `->p(SoftKeyView)Z`,
        // then locate the `iget-boolean` -> `if-eqz` guard inside `g(MotionEvent)`.
        val letterKeyCheckIndex = insns.indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@indexOfFirst false
            val methodRef = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@indexOfFirst false
            methodRef.definingClass == ABSTRACT_GESTURE_HANDLER_CLASS &&
                methodRef.returnType == "Z" &&
                methodRef.parameterTypes == listOf(SOFT_KEY_VIEW_CLASS)
        }
        val letterKeyIput = insns.getOrNull(letterKeyCheckIndex + 2)
        val letterStartedFieldRef = if (
            letterKeyCheckIndex >= 0 &&
            insns.getOrNull(letterKeyCheckIndex + 1)?.opcode == Opcode.MOVE_RESULT &&
            letterKeyIput?.opcode == Opcode.IPUT_BOOLEAN
        ) {
            (letterKeyIput as? ReferenceInstruction)?.reference as? FieldReference
        } else {
            null
        }
        val letterStartedIgetIndex = insns.indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.IGET_BOOLEAN) return@indexOfFirst false
            val fieldRef = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                ?: return@indexOfFirst false
            fieldRef.definingClass == ABSTRACT_GESTURE_HANDLER_CLASS &&
                fieldRef.type == "Z" &&
                (letterStartedFieldRef == null || fieldRef.name == letterStartedFieldRef.name)
        }
        if (letterStartedIgetIndex < 0) {
            throw PatchException(
                "Unable to locate letterStarted iget-boolean guard in AbstractGestureMotionEventHandler.g(MotionEvent).",
            )
        }
        val letterStartedIgetInsn = insns[letterStartedIgetIndex] as? TwoRegisterInstruction
            ?: throw PatchException("letterStarted iget-boolean instruction is not TwoRegisterInstruction.")
        val letterStartedIfEqz = insns.getOrNull(letterStartedIgetIndex + 1)
        if (letterStartedIfEqz?.opcode != Opcode.IF_EQZ) {
            throw PatchException(
                "Expected if-eqz immediately after letterStarted iget-boolean in AbstractGestureMotionEventHandler.g(MotionEvent).",
            )
        }
        val letterStartedReg = letterStartedIgetInsn.registerA
        if (letterStartedReg > 15) {
            throw PatchException(
                "Expected 4-bit register for letterStarted guard in AbstractGestureMotionEventHandler.g(MotionEvent), got v$letterStartedReg.",
            )
        }

        val shouldStartCallIndex = insns.indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@indexOfFirst false
            val methodRef = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@indexOfFirst false
            methodRef.definingClass == ABSTRACT_GESTURE_HANDLER_CLASS &&
                methodRef.name == SHOULD_START_GESTURE_METHOD_NAME &&
                methodRef.returnType == "Z" &&
                methodRef.parameterTypes.isEmpty()
        }
        if (shouldStartCallIndex < 0) {
            throw PatchException(
                "Unable to locate shouldStartGesture invoke-virtual ->t()Z in AbstractGestureMotionEventHandler.g(MotionEvent).",
            )
        }
        val shouldStartInsn = insns[shouldStartCallIndex] as? FiveRegisterInstruction
            ?: throw PatchException("shouldStartGesture instruction is not FiveRegisterInstruction.")
        val shouldStartMoveResult = insns.getOrNull(shouldStartCallIndex + 1) as? OneRegisterInstruction
        if (shouldStartMoveResult == null || shouldStartMoveResult.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("Expected move-result immediately after ->t()Z in AbstractGestureMotionEventHandler.g(MotionEvent).")
        }
        val tThisReg = shouldStartInsn.registerC
        val tResultReg = shouldStartMoveResult.registerA
        if (tThisReg > 15 || tResultReg > 15) {
            throw PatchException(
                "Expected 4-bit registers for ->t()Z hook in AbstractGestureMotionEventHandler.g(MotionEvent), got v$tThisReg, v$tResultReg.",
            )
        }

        val dispatchIndex = insns.indexOfLast { instruction ->
            if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@indexOfLast false
            val methodRef = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@indexOfLast false
            methodRef.name == "n" &&
                methodRef.returnType == "V" &&
                methodRef.parameterTypes.size == 1
        }
        if (dispatchIndex < 0) {
            throw PatchException(
                "Unable to locate gesture event dispatch invoke-interface ->n(...)V in AbstractGestureMotionEventHandler.g(MotionEvent).",
            )
        }

        if (!(dispatchIndex > shouldStartCallIndex + 1 && shouldStartCallIndex + 1 > letterStartedIgetIndex && letterStartedIgetIndex >= 2)) {
            throw PatchException(
                "Unexpected instruction order in AbstractGestureMotionEventHandler.g(MotionEvent): entry=2, letterStarted=$letterStartedIgetIndex, shouldStart=$shouldStartCallIndex, dispatch=$dispatchIndex.",
            )
        }

        val dispatchInsn = insns[dispatchIndex] as? FiveRegisterInstruction
            ?: throw PatchException("Gesture dispatch instruction is not FiveRegisterInstruction.")
        val dispatchMethodRef = (insns[dispatchIndex] as ReferenceInstruction).reference as MethodReference
        val qitReg = dispatchInsn.registerC
        val oheReg = dispatchInsn.registerD

        val nextInsn = insns.getOrNull(dispatchIndex + 1) as? TwoRegisterInstruction
        val scratchReg = if (
            nextInsn != null &&
            nextInsn.opcode == Opcode.IGET_OBJECT &&
            nextInsn.registerA in 0..15 &&
            nextInsn.registerA != thisReg &&
            nextInsn.registerA != qitReg &&
            nextInsn.registerA != oheReg
        ) {
            nextInsn.registerA
        } else {
            2
        }

        val originalInvokeDescriptor =
            "${dispatchMethodRef.definingClass}->${dispatchMethodRef.name}(${dispatchMethodRef.parameterTypes.joinToString("")})${dispatchMethodRef.returnType}"

        val shouldInterceptInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_SHOULD_INTERCEPT_GESTURE_DISPATCH,
            "v$thisReg",
        )
        val shouldStartGestureInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_SHOULD_START_GESTURE,
            "v$tThisReg, v$tResultReg",
        )
        val resolveLetterStartedInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_RESOLVE_LETTER_STARTED,
            "v$thisReg, v$letterStartedReg",
        )
        val onEventEntryInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_ON_GESTURE_EVENT_ENTRY,
            "v$thisReg, v$eventReg",
        )

        // Mutate in descending instruction-index order to preserve earlier indices.
        handleGestureMethod.removeInstruction(dispatchIndex)
        handleGestureMethod.addInstructionsWithLabels(
            dispatchIndex,
            """
                $shouldInterceptInvoke
                move-result v$scratchReg
                if-nez v$scratchReg, :multiglide_skip_gesture_dispatch
                invoke-interface {v$qitReg, v$oheReg}, $originalInvokeDescriptor
                :multiglide_skip_gesture_dispatch
                nop
            """.trimIndent(),
        )

        handleGestureMethod.addInstructionsWithLabels(
            shouldStartCallIndex + 2,
            """
                $shouldStartGestureInvoke
                move-result v$tResultReg
            """.trimIndent(),
        )

        handleGestureMethod.addInstructionsWithLabels(
            letterStartedIgetIndex + 1,
            """
                $resolveLetterStartedInvoke
                move-result v$letterStartedReg
            """.trimIndent(),
        )

        handleGestureMethod.addInstruction(
            2,
            onEventEntryInvoke,
        )

        val resetMethod = handlerClass.methods.firstOrNull {
            it.name == RESET_GESTURE_STATE_METHOD_NAME &&
                it.returnType == "V" &&
                it.parameterTypes.isEmpty()
        } ?: throw PatchException(
            "Could not find reset gesture state method B() in $ABSTRACT_GESTURE_HANDLER_CLASS",
        )
        val shouldInterceptResetInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_SHOULD_INTERCEPT_GESTURE_RESET,
            "p0",
        )
        resetMethod.addInstructionsWithLabels(
            0,
            """
                $shouldInterceptResetInvoke
                move-result v0
                if-eqz v0, :multiglide_skip_reset_early_return
                return-void
                :multiglide_skip_reset_early_return
                nop
            """.trimIndent(),
        )

        // Override AbstractMotionEventHandler.gu()Z so dispatcher ownership transitions from
        // BasicMotionEventHandler do not cancel AbstractGestureMotionEventHandler while active.
        val abstractMotionHandlerClass = mutableClass(ABSTRACT_MOTION_EVENT_HANDLER_CLASS)
        val receiveEventsMethod = abstractMotionHandlerClass.methods.singleOrNull {
            it.returnType == "Z" && it.parameterTypes.isEmpty()
        } ?: throw PatchException(
            "Could not uniquely locate zero-arg boolean method (gu) in $ABSTRACT_MOTION_EVENT_HANDLER_CLASS",
        )
        val shouldReceiveInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_SHOULD_RECEIVE_EVENTS_DURING_OTHER_OWNER,
            "p0",
        )
        handlerClass.addHelperMethodIfMissing(
            name = receiveEventsMethod.name,
            parameterTypes = emptyList(),
            returnType = "Z",
            accessFlags = AccessFlags.PUBLIC.value,
            registerCount = 2,
            body = """
                $shouldReceiveInvoke
                move-result v0
                return v0
            """.trimIndent(),
        )

        // Hook BasicMotionEventHandler.t(MotionEvent) to suppress competing tap/popup processing
        // once MultiGlide takeover is active, and locate Lqil.c(MotionEvent, Z) to suppress premature
        // rollover tap commits during simultaneous dual-thumb touch starts.
        val basicHandlerClass = mutableClass(BASIC_MOTION_EVENT_HANDLER_CLASS)
        val basicHandleG = basicHandlerClass.methods.firstOrNull {
            it.name == "g" && it.returnType == "V" && it.parameterTypes == listOf("Landroid/view/MotionEvent;")
        } ?: throw PatchException(
            "Could not find g(MotionEvent) in $BASIC_MOTION_EVENT_HANDLER_CLASS",
        )
        val basicInnerMethodRef = basicHandleG.instructions
            .asSequence()
            .filter { it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull {
                it.definingClass == BASIC_MOTION_EVENT_HANDLER_CLASS &&
                    it.returnType == "V" &&
                    it.parameterTypes == listOf("Landroid/view/MotionEvent;")
            } ?: throw PatchException(
            "Could not resolve internal handleTouch method called from BasicMotionEventHandler.g(MotionEvent).",
        )
        val basicEventMethod = basicHandlerClass.methods.firstOrNull {
            it.name == basicInnerMethodRef.name &&
                it.returnType == "V" &&
                it.parameterTypes == listOf("Landroid/view/MotionEvent;")
        } ?: throw PatchException(
            "Could not find ${basicInnerMethodRef.name}(MotionEvent) in $BASIC_MOTION_EVENT_HANDLER_CLASS",
        )

        // First resolve Lqil.c(MotionEvent, Z) from BasicMotionEventHandler.t before mutating t's instructions.
        val touchBundleCallRef = basicEventMethod.instructions
            .asSequence()
            .filter { it.opcode == Opcode.INVOKE_VIRTUAL }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull {
                !it.definingClass.startsWith("Landroid/") &&
                    it.parameterTypes == listOf("Landroid/view/MotionEvent;", "Z")
            } ?: throw PatchException(
            "Could not locate TouchActionBundle handler method (MotionEvent, Z) inside BasicMotionEventHandler.${basicEventMethod.name}.",
        )

        val basicRegCount = basicEventMethod.implementation?.registerCount
            ?: throw PatchException("Missing implementation for BasicMotionEventHandler.${basicEventMethod.name}.")
        if (basicRegCount !in 3..16) {
            throw PatchException(
                "Unexpected registerCount=$basicRegCount in BasicMotionEventHandler.${basicEventMethod.name}; expected 3..16.",
            )
        }
        val basicThisReg = basicRegCount - 2
        val basicEventReg = basicRegCount - 1
        val suppressBasicInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_SHOULD_SUPPRESS_BASIC_HANDLER,
            "v$basicThisReg, v$basicEventReg",
        )
        basicEventMethod.addInstructionsWithLabels(
            0,
            """
                $suppressBasicInvoke
                move-result v0
                if-eqz v0, :multiglide_continue_basic_handler
                return-void
                :multiglide_continue_basic_handler
                nop
            """.trimIndent(),
        )

        // Hook Lqil.c(MotionEvent, Z) right before Lqil.t(Lqin, MotionEvent, I)V on ACTION_POINTER_DOWN.
        val touchBundleHandlerClass = mutableClass(touchBundleCallRef.definingClass)
        val touchBundleMethod = touchBundleHandlerClass.methods.firstOrNull {
            it.name == touchBundleCallRef.name &&
                it.parameterTypes == touchBundleCallRef.parameterTypes &&
                it.returnType == touchBundleCallRef.returnType
        } ?: throw PatchException(
            "Could not find ${touchBundleCallRef.name}(MotionEvent, Z) in ${touchBundleCallRef.definingClass}.",
        )
        val tbInsns = touchBundleMethod.instructions
        val softKeyOIndex = tbInsns.indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@indexOfFirst false
            val methodRef = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@indexOfFirst false
            methodRef.definingClass == SOFT_KEY_DEF_CLASS &&
                methodRef.returnType == "Z" &&
                methodRef.parameterTypes.isEmpty()
        }
        if (softKeyOIndex < 0) {
            throw PatchException(
                "Unable to locate SoftKeyDef.o()Z rollover check in ${touchBundleCallRef.definingClass}.${touchBundleCallRef.name}.",
            )
        }
        val oMoveResult = tbInsns.getOrNull(softKeyOIndex + 1) as? OneRegisterInstruction
        val oIfNez = tbInsns.getOrNull(softKeyOIndex + 2)
        val commitInsn = tbInsns.getOrNull(softKeyOIndex + 3) as? FiveRegisterInstruction
        val commitMethodRef = (tbInsns.getOrNull(softKeyOIndex + 3) as? ReferenceInstruction)?.reference as? MethodReference
        if (
            oMoveResult == null ||
            oMoveResult.opcode != Opcode.MOVE_RESULT ||
            oIfNez?.opcode != Opcode.IF_NEZ ||
            commitInsn == null ||
            tbInsns[softKeyOIndex + 3].opcode != Opcode.INVOKE_STATIC ||
            commitMethodRef == null ||
            commitMethodRef.definingClass != touchBundleCallRef.definingClass ||
            commitMethodRef.returnType != "V" ||
            commitMethodRef.parameterTypes.size != 3 ||
            commitMethodRef.parameterTypes[1] != "Landroid/view/MotionEvent;"
        ) {
            throw PatchException(
                "Unexpected instruction pattern after SoftKeyDef.o()Z in ${touchBundleCallRef.definingClass}.${touchBundleCallRef.name}.",
            )
        }
        val flagReg = oMoveResult.registerA
        val tbEventReg = commitInsn.registerD
        if (flagReg > 15 || tbEventReg > 15) {
            throw PatchException(
                "Expected 4-bit registers in ${touchBundleCallRef.definingClass}.${touchBundleCallRef.name}, got flagReg=v$flagReg eventReg=v$tbEventReg.",
            )
        }
        val commitMethodDescriptor =
            "${commitMethodRef.definingClass}->${commitMethodRef.name}(${commitMethodRef.parameterTypes.joinToString("")})${commitMethodRef.returnType}"
        val suppressRolloverInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_SHOULD_SUPPRESS_ROLLOVER_COMMIT,
            "v$tbEventReg",
        )
        touchBundleMethod.removeInstruction(softKeyOIndex + 3)
        touchBundleMethod.addInstructionsWithLabels(
            softKeyOIndex + 3,
            """
                $suppressRolloverInvoke
                move-result v$flagReg
                if-nez v$flagReg, :multiglide_skip_rollover_commit
                invoke-static {v${commitInsn.registerC}, v${commitInsn.registerD}, v${commitInsn.registerE}}, $commitMethodDescriptor
                :multiglide_skip_rollover_commit
                nop
            """.trimIndent(),
        )

        // Preload active language dictionary when SoftKeyboardView is inflated or attached so
        // the first two-finger swipe does not stall waiting for .lm parsing.
        val softKeyboardViewClass = mutableClass(SOFT_KEYBOARD_VIEW_CLASS)
        val preloadAbi = RuntimeAbiCatalog.abi(RuntimeCallId.MULTIGLIDE_RUNTIME_ON_KEYBOARD_VIEW_READY)
        val preloadInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.MULTIGLIDE_RUNTIME_ON_KEYBOARD_VIEW_READY,
            "p0",
        )
        val onFinishInflateMethod = softKeyboardViewClass.methods.firstOrNull {
            it.name == "onFinishInflate" &&
                it.returnType == "V" &&
                it.parameterTypes.isEmpty()
        } ?: throw PatchException(
            "Could not find onFinishInflate() in $SOFT_KEYBOARD_VIEW_CLASS",
        )
        listOfNotNull(
            onFinishInflateMethod,
            softKeyboardViewClass.methods.firstOrNull {
                it.name == "onAttachedToWindow" &&
                    it.returnType == "V" &&
                    it.parameterTypes.isEmpty()
            },
        ).forEach { targetMethod ->
            val targetInsns = targetMethod.implementation?.instructions
                ?: error("No instructions in ${targetMethod.definingClass}->${targetMethod.name}")
            val returnIndices = targetMethod.instructionIndices("RETURN_VOID")
            check(returnIndices.isNotEmpty()) {
                "No return-void instruction in ${targetMethod.definingClass}->${targetMethod.name}"
            }
            val existing = targetInsns.count { it.isMethodReference(preloadAbi.reference) }
            if (existing == 0) {
                returnIndices.asReversed().forEach { retIdx ->
                    targetMethod.addInstructions(retIdx, preloadInvoke)
                }
            } else {
                check(existing == returnIndices.size) {
                    "Expected ${targetMethod.definingClass}->${targetMethod.name} to match ${returnIndices.size} preload hooks, found $existing"
                }
            }
        }
    }
}
