package dev.srwi.gboardmultiswipe.patches.gboard.features.matrixsuggestions

import app.morphe.patcher.util.smali.InlineSmaliCompiler
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.srwi.gboardmultiswipe.patches.gboard.shared.runtimeabi.RuntimeCallId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardMatrixSuggestionsSmaliCompilationTest {
    @Test
    fun `verify append candidates invoke range compiles for method D with 29 registers`() {
        val smaliRange = RuntimeCallEmitter.invoke(
            RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_UPDATED,
            "p0 .. p1",
        )
        assertTrue(smaliRange.startsWith("invoke-static/range"))

        val instructions = InlineSmaliCompiler.compile(
            instructions = smaliRange,
            parameters = "Ljava/util/List;Ljava/util/ArrayList;",
            registers = 29,
            forStaticMethod = false,
        )
        assertEquals(1, instructions.size)
    }

    @Test
    fun `verify clear candidates invoke compiles for method l with 7 registers`() {
        val smaliDirect = RuntimeCallEmitter.invoke(
            RuntimeCallId.MATRIX_SUGGESTIONS_RUNTIME_ON_CANDIDATES_CLEARED,
            "p0",
        )
        assertTrue(smaliDirect.startsWith("invoke-static"))

        val instructions = InlineSmaliCompiler.compile(
            instructions = smaliDirect,
            parameters = "",
            registers = 7,
            forStaticMethod = false,
        )
        assertEquals(1, instructions.size)
    }
}
