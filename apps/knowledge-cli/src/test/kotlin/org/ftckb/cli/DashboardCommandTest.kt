package org.ftckb.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

class DashboardCommandTest {
    @Test fun `options only accept knowledge port and no browser without duplicates`() {
        assertEquals(DashboardOptions(Path.of("knowledge"),0,false),parseDashboardOptions(listOf("--knowledge","knowledge")))
        assertEquals(DashboardOptions(Path.of("knowledge"),8080,true),parseDashboardOptions(listOf("--no-browser","--port","8080","--knowledge","knowledge")))
        listOf(emptyList(),listOf("--knowledge"),listOf("--knowledge","knowledge","--port","65536"),listOf("--knowledge","knowledge","--port","x"),listOf("--knowledge","knowledge","--port","-1"),listOf("--knowledge","knowledge","--no-browser","--no-browser"),listOf("--knowledge","knowledge","--config","secret.yml")).forEach {
            val out=ByteArrayOutputStream(); assertEquals(64,runDashboardCommand(it,PrintStream(out))); assertTrue(out.toString().startsWith("usage:"))
        }
    }
    @Test fun `unavailable knowledge exits two without configuration or key lookup`() {
        val out=ByteArrayOutputStream()
        assertEquals(2,runDashboardCommand(listOf("--knowledge","/no/such/knowledge","--no-browser"),PrintStream(out)))
        assertFalse(out.toString().contains("token")); assertFalse(out.toString().contains("API"))
    }
}
