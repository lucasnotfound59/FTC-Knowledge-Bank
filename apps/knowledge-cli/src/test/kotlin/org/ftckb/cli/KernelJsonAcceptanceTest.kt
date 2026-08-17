package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KernelJsonAcceptanceTest {
    private val mapper=JsonMapper.builder().build()

    @Test
    fun `validate json reports the stable contract`() {
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf("validate",Path.of("..","..","knowledge").toString(),"--json"),
            PrintStream(out)
        )

        assertEquals(0,code)
        val node=mapper.readTree(out.toString())
        assertEquals(1,node["schemaVersion"].asInt())
        assertEquals("validate",node["command"].asText())
        assertTrue(node["ok"].booleanValue())
        assertTrue(node["ruleCount"].asInt()>=1)
        assertEquals(0,node["violations"].size())
    }

    @Test
    fun `resolve json is deterministic and machine readable`() {
        val first=ByteArrayOutputStream()
        val second=ByteArrayOutputStream()

        assertEquals(0,runCli(
            listOf("resolve",Path.of("..","..","knowledge").toString(),"--team","20827","--season","2025-2026","--json"),
            PrintStream(first)
        ))
        assertEquals(0,runCli(
            listOf("resolve",Path.of("..","..","knowledge").toString(),"--team","20827","--season","2025-2026","--json"),
            PrintStream(second)
        ))
        assertEquals(first.toString(),second.toString())

        val node=mapper.readTree(first.toString())
        assertEquals(1,node["schemaVersion"].asInt())
        assertEquals("resolve",node["command"].asText())
        assertEquals("20827",node["team"].asText())
        assertEquals("2025-2026",node["season"].asText())
        assertTrue(node["ok"].booleanValue())
        val rules=node["activeRules"]
        assertTrue(rules.size()>=1)
        val ids=rules.map { it["id"].asText() }
        assertEquals(ids.sorted(),ids)
        rules.forEach { rule ->
            listOf("id","topic","title","instruction","rationale","status","authority","applicability","evidence").forEach { field ->
                assertTrue(rule.has(field),"missing $field")
            }
            assertTrue(rule["status"].asText()=="approved")
        }
        assertTrue(node["conflicts"].isArray)
    }

    @Test
    fun `resolve json reports conflicts with exit two`(@TempDir root:Path) {
        val knowledge=root.resolve("knowledge")
        knowledge.resolve("rules.yaml").apply {
            parent.createDirectories()
            Files.writeString(this,"""
                schemaVersion: 1
                rules:
                  - id: official.first
                    topic: same-topic
                    title: First
                    instruction: First rule.
                    rationale: Because.
                    status: approved
                    authority: official
                    applicability: {}
                    evidence:
                      - repository: owner/repo
                        commit: abcdef1
                        file: README.md
                        line: 1
                    approval:
                      approver: overall-lead
                      role: overall_software_lead
                      approvedAt: 2026-08-14T00:00:00Z
                  - id: official.second
                    topic: same-topic
                    title: Second
                    instruction: Second rule.
                    rationale: Because.
                    status: approved
                    authority: official
                    applicability: {}
                    evidence:
                      - repository: owner/repo
                        commit: abcdef1
                        file: README.md
                        line: 1
                    approval:
                      approver: overall-lead
                      role: overall_software_lead
                      approvedAt: 2026-08-14T00:00:00Z
            """.trimIndent())
        }
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf("resolve",knowledge.toString(),"--team","20827","--season","2025-2026","--json"),
            PrintStream(out)
        )

        assertEquals(2,code)
        val node=mapper.readTree(out.toString())
        assertFalse(node["ok"].booleanValue())
        assertEquals(1,node["conflicts"].size())
        assertEquals("same-topic",node["conflicts"][0]["topic"].asText())
        assertEquals(listOf("official.first","official.second"),node["conflicts"][0]["ruleIds"].map { it.asText() })
        assertEquals("official",node["conflicts"][0]["authority"].asText())
    }

    @Test
    fun `text mode output is unchanged without the json flag`() {
        val out=ByteArrayOutputStream()

        assertEquals(0,runCli(listOf("validate",Path.of("..","..","knowledge").toString()),PrintStream(out)))

        val text=out.toString()
        assertTrue(text.startsWith("validation=ok rules="))
        assertFalse(text.contains("{"))
    }

    @Test
    fun `kernel contract fixtures parse and carry the expected shapes`() {
        val base=Path.of("..","..","fixtures","kernel")

        val validate=mapper.readTree(Files.readString(base.resolve("validate-ok.json")))
        assertEquals(1,validate["schemaVersion"].asInt())
        assertEquals("validate",validate["command"].asText())
        assertTrue(validate["ok"].booleanValue())
        assertTrue(validate["ruleCount"].asInt()>=1)

        val resolve=mapper.readTree(Files.readString(base.resolve("resolve-ok.json")))
        assertEquals("resolve",resolve["command"].asText())
        assertTrue(resolve["activeRules"].isArray)
        assertTrue(resolve["activeRules"].size()>=1)
        assertTrue(resolve["conflicts"].isArray)

        val error=mapper.readTree(Files.readString(base.resolve("error-usage.json")))
        assertFalse(error["ok"].booleanValue())
        assertEquals("usage",error["error"]["code"].asText())
    }

    @Test
    fun `json mode usage errors keep the stable error shape`() {
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf("resolve","does-not-exist","--team","20827","--json"),
            PrintStream(out)
        )

        assertEquals(64,code)
        val node=mapper.readTree(out.toString())
        assertEquals(1,node["schemaVersion"].asInt())
        assertEquals("resolve",node["command"].asText())
        assertFalse(node["ok"].booleanValue())
        assertEquals("usage",node["error"]["code"].asText())
        assertEquals("missing --season",node["error"]["message"].asText())
    }

    @Test
    fun `json mode load errors keep the stable error shape`(@TempDir root:Path) {
        Files.writeString(root.resolve("invalid.yaml"),"not-a-map")
        val out=ByteArrayOutputStream()

        val code=runCli(listOf("validate",root.toString(),"--json"),PrintStream(out))

        assertEquals(2,code)
        val node=mapper.readTree(out.toString())
        assertEquals(1,node["schemaVersion"].asInt())
        assertEquals("validate",node["command"].asText())
        assertFalse(node["ok"].booleanValue())
        assertEquals("load-error",node["error"]["code"].asText())
        assertTrue(node["error"]["message"].asText().startsWith("error loading knowledge:"))
    }

    @Test
    fun `json mode violations return a machine readable violations array`(@TempDir root:Path) {
        Files.writeString(root.resolve("rules.yaml"),"""
            schemaVersion: 1
            rules:
              - id: shared.invalid-commit
                topic: test-topic
                title: Test rule
                instruction: Test instruction.
                rationale: Test rationale.
                status: candidate
                authority: shared
                applicability:
                  teams: []
                  seasons: []
                evidence:
                  - repository: owner/repository
                    commit: invalid
                    file: TeamCode/Test.java
                    symbol: Test
        """.trimIndent())
        val out=ByteArrayOutputStream()

        val code=runCli(listOf("validate",root.toString(),"--json"),PrintStream(out))

        assertEquals(2,code)
        val node=mapper.readTree(out.toString())
        assertFalse(node["ok"].booleanValue())
        assertEquals("invalid-knowledge",node["error"]["code"].asText())
        assertEquals(1,node["violations"].size())
        assertEquals("shared.invalid-commit",node["violations"][0]["ruleId"].asText())
        assertEquals("evidence[0].commit",node["violations"][0]["field"].asText())
    }
}
