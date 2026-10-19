package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Byte strings captured from the installed pre-audit CLI; no JSON reserialization. */
class KernelAuditCompatibilityTest {
    @TempDir lateinit var temp:Path
    @Test fun `legacy usage and context failures retain exact bytes and exit codes`() {
        val cases=listOf(
            Triple(listOf("check","--json"),64,"{\"schemaVersion\":2,\"command\":\"check\",\"ok\":false,\"error\":{\"code\":\"usage\",\"message\":\"missing --team\"}}\n"),
            Triple(listOf("resolve","knowledge","--team","20827","--season","2025-2026","--json"),2,
                "{\"schemaVersion\":2,\"command\":\"resolve\",\"ok\":false,\"error\":{\"code\":\"context-required\",\"message\":\"Select a project profile or explicitly select generic\"}}\n"),
            Triple(listOf("resolve","knowledge","--team","20827","--season","2025-2026","--profile","unknown","--json"),2,
                "{\"schemaVersion\":2,\"command\":\"resolve\",\"ok\":false,\"error\":{\"code\":\"invalid-context\",\"message\":\"Unknown profiles: unknown\"}}\n")
        )
        cases.forEach { (args,code,expected) ->
            val output=ByteArrayOutputStream()
            assertEquals(code,runCli(args,PrintStream(output),auditDirectory=temp.resolve("audit")))
            assertEquals(expected,output.toString())
        }
    }
    @Test fun `empty library success retains exact output with healthy or broken audit`() {
        val root=Files.createDirectories(temp.resolve("knowledge"))
        val broken=Files.writeString(temp.resolve("blocked"),"file")
        listOf(temp.resolve("healthy"),broken).forEach { audit ->
            val output=ByteArrayOutputStream()
            assertEquals(0,runCli(listOf("validate",root.toString(),"--json"),PrintStream(output),auditDirectory=audit,auditDiagnostics={}))
            assertEquals("{\"schemaVersion\":2,\"command\":\"validate\",\"ok\":true,\"ruleCount\":0,\"violations\":[]}\n",output.toString())
        }
    }
}
