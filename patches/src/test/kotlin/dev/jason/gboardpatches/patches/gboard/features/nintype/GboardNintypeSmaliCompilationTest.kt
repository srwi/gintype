package dev.jason.gboardpatches.patches.gboard.features.nintype

import app.morphe.patcher.util.smali.InlineSmaliCompiler
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardNintypeSmaliCompilationTest {
    @Test
    fun `verify onGestureEventEntry invoke compiles for method g with 39 registers`() {
        val smali = RuntimeCallEmitter.invoke(
            RuntimeCallId.NINTYPE_RUNTIME_ON_GESTURE_EVENT_ENTRY,
            "v0, v8",
        )
        assertTrue(smali.startsWith("invoke-static {v0, v8}"))

        val instructions = InlineSmaliCompiler.compile(
            instructions = smali,
            parameters = "Landroid/view/MotionEvent;",
            registers = 39,
            forStaticMethod = false,
        )
        assertEquals(1, instructions.size)
    }

    @Test
    fun `verify shouldInterceptGestureDispatch compiles for method g with 39 registers`() {
        val shouldInterceptRef = RuntimeAbiCatalog.abi(
            RuntimeCallId.NINTYPE_RUNTIME_SHOULD_INTERCEPT_GESTURE_DISPATCH,
        ).reference
        val smali = """
            invoke-static {v0}, $shouldInterceptRef
            move-result v2
        """.trimIndent()

        val instructions = InlineSmaliCompiler.compile(
            instructions = smali,
            parameters = "Landroid/view/MotionEvent;",
            registers = 39,
            forStaticMethod = false,
        )
        assertEquals(2, instructions.size)
    }

    @Test
    fun `verify shouldInterceptGestureReset invoke compiles for method B with 6 registers`() {
        val shouldInterceptResetRef = RuntimeAbiCatalog.abi(
            RuntimeCallId.NINTYPE_RUNTIME_SHOULD_INTERCEPT_GESTURE_RESET,
        ).reference
        val smali = """
            invoke-static {p0}, $shouldInterceptResetRef
            move-result v0
            if-eqz v0, :nintype_skip_reset_early_return
            return-void
            :nintype_skip_reset_early_return
            nop
        """.trimIndent()

        val instructions = InlineSmaliCompiler.compile(
            instructions = smali,
            parameters = "",
            registers = 6,
            forStaticMethod = false,
        )
        assertEquals(5, instructions.size)
    }

    @Test
    fun `verify onGestureHandlerCancelRequested invoke compiles for method l with 1 register`() {
        val smali = RuntimeCallEmitter.invoke(
            RuntimeCallId.NINTYPE_RUNTIME_ON_GESTURE_CANCEL,
            "p0",
        )
        assertTrue(smali.startsWith("invoke-static"))

        val instructions = InlineSmaliCompiler.compile(
            instructions = smali,
            parameters = "",
            registers = 1,
            forStaticMethod = false,
        )
        assertEquals(1, instructions.size)
    }
}
