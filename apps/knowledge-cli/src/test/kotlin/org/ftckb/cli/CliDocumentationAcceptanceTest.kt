package org.ftckb.cli

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.ftckb.model.ProviderConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliDocumentationAcceptanceTest {
    @Test
    fun `public entry documentation uses v2 fields and explicit profile commands`() {
        val root=Path.of("..","..").normalize()
        for (file in listOf("docs/cli-agent.md","docs/handbook/installation.md")) {
            val text=Files.readString(root.resolve(file))
            listOf("--generic-profile","--profile command-based","不需要 API key","Robot Controller","Driver Station")
                .forEach { assertTrue(text.contains(it),"$file: $it") }
            val commands=text.lineSequence().filter { line ->
                line.contains("--team") && Regex("\\b(chat|serve|resolve|check)\\b").containsMatchIn(line)
            }.toList()
            assertTrue(commands.isNotEmpty(),file)
            commands.forEach { line ->
                assertTrue(line.contains("--profile ") || line.contains("--generic-profile"),"$file: $line")
            }
        }
        val cli=Files.readString(root.resolve("docs/cli-agent.md"))
        listOf("schemaVersion=2","profiles","excludedRules","overriddenRules","policyLevel",
            "effectiveLevel","authorities","winnerIds","context-required","invalid-context","ftckb check")
            .forEach { assertTrue(cli.contains(it),it) }
        listOf("schemaVersion（当前 1）","topic + authority + ruleIds")
            .forEach { assertFalse(cli.contains(it),it) }
    }

    @Test
    fun `installation profile counts match both teams current resolution`() {
        val root=Path.of("..","..").normalize()
        val text=Files.readString(root.resolve("docs/handbook/installation.md"))
        assertTrue(text.contains("46（40 已批准 + 6 候选）"))
        assertTrue(text.contains("validation=ok rules=46"))
        val mapper=ObjectMapper()
        for ((profile,count) in listOf("generic" to 24,"command-based" to 27,"rookiebot" to 36,"ftclib-command" to 28)) {
            assertTrue(text.contains("| $profile | $count |"),profile)
            val selections=if (profile=="generic") listOf("--generic-profile") else listOf("--profile",profile)
            val activeByTeam=listOf("20827","16093").map { team ->
                val out=ByteArrayOutputStream()
                assertEquals(0,runCli(listOf("resolve",root.resolve("knowledge").toString(),
                    "--team",team,"--season","2025-2026","--json")+selections,PrintStream(out)))
                val result=mapper.readTree(out.toString())
                assertEquals(2,result["schemaVersion"].asInt())
                assertEquals(count,result["activeRules"].size(),"$profile/$team")
                result["activeRules"].map { it["id"].asText() }
            }
            assertEquals(activeByTeam[0],activeByTeam[1],profile)
        }
        listOf("rules=43","37 条 active","16093 为 31")
            .forEach { assertFalse(text.contains(it),it) }
    }

    @Test
    fun `release documents publish all version axes and profile aware governance`() {
        val root=Path.of("..","..").normalize()
        val readme=Files.readString(root.resolve("README.md"))
        listOf("**版本：V0.4.0**","46（40 已批准 + 6 候选）","CLI 2.0.0","YAML v4","kernel JSON v2","项目接入协议 v2").forEach {
            assertTrue(readme.contains(it),it)
        }
        listOf("AGENTS.md","docs/kernel-contract.md","docs/handbook/resolution.md").forEach { file ->
            val text=Files.readString(root.resolve(file))
            listOf("OFFICIAL > GLOBAL > LOCAL > SHARED","--generic-profile","--profile command-based","excludedRules","overriddenRules").forEach {
                assertTrue(text.contains(it),"$file: $it")
            }
        }
        val schema=Files.readString(root.resolve("docs/handbook/rule-schema.md"))
        listOf("YAML v4","policyLevel","profiles","reviewTriggers").forEach { assertTrue(schema.contains(it),it) }
        val approval=Files.readString(root.resolve("docs/handbook/approval.md"))
        listOf("team→global","新的总软件负责人审批","旧审批","迁移台账").forEach { assertTrue(approval.contains(it),it) }
    }

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
    fun `kernel help marks explicit profile selection as required`() {
        for (args in listOf(listOf("--help"),listOf("resolve","--help"),listOf("check","--help"))) {
            val out=ByteArrayOutputStream()
            assertEquals(0,runCli(args,PrintStream(out)))
            assertTrue(out.toString().contains("(--profile NAME [--profile NAME ...] | --generic-profile)"))
            assertFalse(out.toString().contains("[--profile NAME ... | --generic-profile]"))
        }
    }

    @Test
    fun `installDist launcher runs help without credentials`() {
        val script=Path.of("build","install","ftckb","bin","ftckb").normalize()
        assertTrue(Files.isRegularFile(script),"The test task must build its installDist prerequisite: $script")

        val process=ProcessBuilder(script.toString(),"chat","--help").start()
        val finished=process.waitFor(60,TimeUnit.SECONDS)
        assertTrue(finished,"launcher did not finish in time")
        assertEquals(0,process.exitValue())
        val output=process.inputStream.bufferedReader().readText()
        assertTrue(output.contains("usage: knowledge-cli chat"))
        assertFalse(output.contains("missing API key"))

        val version=ProcessBuilder(script.toString(),"--version").redirectErrorStream(true).start()
        assertTrue(version.waitFor(60,TimeUnit.SECONDS),"version command did not finish in time")
        assertEquals(0,version.exitValue())
        assertEquals("ftckb 2.0.0 (kernel contract schemaVersion 2)\n",version.inputStream.bufferedReader().readText())
    }
}
