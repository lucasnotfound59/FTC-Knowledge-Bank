package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainTest {
    private val knowledgeRoot=Path.of("..","..","knowledge").normalize()

    @Test
    fun `validate reports rule counts`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("validate",knowledgeRoot.toString()),PrintStream(output))
        assertEquals(0,code)
        assertTrue(output.toString().contains("validation=ok"))
    }

    @Test
    fun `resolve prints active IDs for team and season`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("resolve",knowledgeRoot.toString(),"--team","20827","--season","2025-2026"),PrintStream(output))
        assertEquals(0,code)
        assertTrue(output.toString().contains("official.keep-customizations-in-teamcode"))
    }
}
