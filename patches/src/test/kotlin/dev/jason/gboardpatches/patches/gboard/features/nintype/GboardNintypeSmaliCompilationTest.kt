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
    fun `verify onGestureReset invoke compiles for method B with 6 registers`() {
        val smali = RuntimeCallEmitter.invoke(
            RuntimeCallId.NINTYPE_RUNTIME_ON_GESTURE_RESET,
            "p0",
        )
        assertTrue(smali.startsWith("invoke-static"))

        val instructions = InlineSmaliCompiler.compile(
            instructions = smali,
            parameters = "",
            registers = 6,
            forStaticMethod = false,
        )
        assertEquals(1, instructions.size)
    }
}
