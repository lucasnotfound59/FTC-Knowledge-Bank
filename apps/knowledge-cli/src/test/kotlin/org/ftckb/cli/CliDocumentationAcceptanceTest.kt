package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.ftckb.model.ProviderConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class CliDocumentationAcceptanceTest {
    @Test
    fun `config example parses without secrets and names all three provider kinds`() {
        val path=Path.of("..","..","config","ftckb-config.example.yaml").normalize()
        val text=Files.readString(path)
        val config=ProviderConfigLoader.decode(text)

        assertEquals("deepseek",config.defaultProvider)
        listOf("deepseek","openai","custom").forEach { name ->
            assertTrue(config.providers.containsKey(name),name)
        }
        assertFalse(text.contains("sk-"))
        assertFalse(text.contains("Bearer"))
        assertFalse(Regex("(?i)api[_-]?key[\"']?\\s*[:=]\\s*[^\\s#]").containsMatchIn(text))
    }

    @Test
    fun `cli agent documentation names every command and states the safety boundaries`() {
        val text=Files.readString(Path.of("..","..","docs","cli-agent.md").normalize())

        listOf(
            "ftckb chat","--knowledge","--team","--season","--provider","--repo","--config","installDist",
            "/help","/mode ask","/mode edit","/undo","/discard","/diff","/save","/commit","/status","/exit"
        ).forEach { token -> assertTrue(text.contains(token),token) }
        listOf(
            "不创建、不切换分支",
            "只存内存",
            "不联网",
            "没有 Run 模式",
            "永不自动 commit/push/merge/rebase",
            ".env",
            "local.properties",
            "48,000",
            "<untrusted_context>",
            "approved_rule",
            "code_observation",
            "model_inference",
            "insufficient_evidence",
            "没有 Android Studio 界面"
        ).forEach { phrase -> assertTrue(text.contains(phrase),phrase) }
    }

    @Test
    fun `main entry help works without credentials`() {
        val out=ByteArrayOutputStream()

        assertEquals(0,runCli(listOf("chat","--help"),PrintStream(out)))
        assertEquals(0,runCli(listOf("eval","--help"),PrintStream(out)))

        val text=out.toString()
        assertTrue(text.contains("usage: knowledge-cli chat"))
        assertTrue(text.contains("usage: knowledge-cli eval"))
    }

    @Test
    fun `installDist launcher runs help without credentials`() {
        val script=Path.of("build","install","knowledge-cli","bin","ftckb").normalize()
        assumeTrue(Files.exists(script),"installDist output is not present")

        val process=ProcessBuilder(script.toString(),"chat","--help").start()
        val finished=process.waitFor(60,TimeUnit.SECONDS)
        assertTrue(finished,"launcher did not finish in time")
        assertEquals(0,process.exitValue())
        val output=process.inputStream.bufferedReader().readText()
        assertTrue(output.contains("usage: knowledge-cli chat"))
        assertFalse(output.contains("missing API key"))
    }
}
