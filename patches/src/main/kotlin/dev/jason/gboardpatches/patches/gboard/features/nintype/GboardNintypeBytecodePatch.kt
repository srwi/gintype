package dev.jason.gboardpatches.patches.gboard.features.nintype

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.jason.gboardpatches.patches.gboard.shared.mutableClass
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

private const val ABSTRACT_GESTURE_HANDLER_CLASS =
    "Lcom/google/android/apps/inputmethod/libs/gestureui/AbstractGestureMotionEventHandler;"

private const val HANDLE_GESTURE_MOTION_EVENT_METHOD_NAME = "g"
private const val SHOULD_START_GESTURE_METHOD_NAME = "t"
private const val RESET_GESTURE_STATE_METHOD_NAME = "B"

internal val gboardNintypeBytecodePatch = bytecodePatch(
    description = "Hooks Gboard's gesture motion handler to decode Nintype two-finger swipe and tap+swipe gestures.",
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
            RuntimeCallId.NINTYPE_RUNTIME_SHOULD_INTERCEPT_GESTURE_DISPATCH,
            "v$thisReg",
        )
        val shouldStartGestureInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.NINTYPE_RUNTIME_SHOULD_START_GESTURE,
            "v$tThisReg, v$tResultReg",
        )
        val onEventEntryInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.NINTYPE_RUNTIME_ON_GESTURE_EVENT_ENTRY,
            "v$thisReg, v$eventReg",
        )

        // Mutate in descending instruction-index order to preserve earlier indices.
        handleGestureMethod.removeInstruction(dispatchIndex)
        handleGestureMethod.addInstructionsWithLabels(
            dispatchIndex,
            """
                $shouldInterceptInvoke
                move-result v$scratchReg
                if-nez v$scratchReg, :nintype_skip_gesture_dispatch
                invoke-interface {v$qitReg, v$oheReg}, $originalInvokeDescriptor
                :nintype_skip_gesture_dispatch
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
        val onGestureResetInvoke = RuntimeCallEmitter.invoke(
            RuntimeCallId.NINTYPE_RUNTIME_ON_GESTURE_RESET,
            "p0",
        )
        resetMethod.addInstruction(
            0,
            onGestureResetInvoke,
        )
    }
}
