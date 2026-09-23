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
    fun `resolve missing profile fails with JSON context error`() {
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf("resolve",Path.of("..","..","knowledge").toString(),"--team","20827","--season","2025-2026","--json"),
            PrintStream(out)
        )
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())

        assertEquals(2,code)
        assertEquals(2,node["schemaVersion"].asInt())
        assertEquals("resolve",node["command"].asText())
        assertEquals("context-required",node["error"]["code"].asText())
    }

    @Test
    fun `generic profile succeeds with an explicit empty profile array`() {
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf(
                "resolve",Path.of("..","..","knowledge").toString(),"--team","20827","--season","2025-2026",
                "--generic-profile","--json"
            ),
            PrintStream(out)
        )
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())

        assertEquals(0,code)
        assertEquals(2,node["schemaVersion"].asInt())
        assertTrue(node["profiles"].isArray)
        assertEquals(0,node["profiles"].size())
    }

    @Test
    fun `named profiles are normalized and sorted in command output`() {
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf(
                "resolve",Path.of("..","..","knowledge").toString(),"--team","20827","--season","2025-2026",
                "--profile","rookiebot","--json"
            ),
            PrintStream(out)
        )
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())

        assertEquals(0,code)
        assertEquals(listOf("rookiebot","simple-opmode"),node["profiles"].map { it.asText() })
    }

    @Test
    fun `unknown profile fails before loading knowledge`() {
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf(
                "resolve","does-not-exist","--team","20827","--season","2025-2026",
                "--profile","unknown","--json"
            ),
            PrintStream(out)
        )
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())

        assertEquals(2,code)
        assertEquals("invalid-context",node["error"]["code"].asText())
        assertEquals("Unknown profiles: unknown",node["error"]["message"].asText())
    }

    @Test
    fun `validate json reports the stable contract`() {
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf("validate",Path.of("..","..","knowledge").toString(),"--json"),
            PrintStream(out)
        )

        assertEquals(0,code)
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())
        assertEquals(2,node["schemaVersion"].asInt())
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
            listOf("resolve",Path.of("..","..","knowledge").toString(),"--team","20827","--season","2025-2026","--generic-profile","--json"),
            PrintStream(first)
        ))
        assertEquals(0,runCli(
            listOf("resolve",Path.of("..","..","knowledge").toString(),"--team","20827","--season","2025-2026","--generic-profile","--json"),
            PrintStream(second)
        ))
        assertEquals(first.toString(),second.toString())
        assertSchemaValid(first.toString())
        assertSchemaValid(second.toString())

        val node=mapper.readTree(first.toString())
        assertEquals(2,node["schemaVersion"].asInt())
        assertEquals("resolve",node["command"].asText())
        assertEquals("20827",node["team"].asText())
        assertEquals("2025-2026",node["season"].asText())
        assertEquals(0,node["profiles"].size())
        assertTrue(node["ok"].booleanValue())
        val rules=node["activeRules"]
        assertTrue(rules.size()>=1)
        val ids=rules.map { it["id"].asText() }
        assertEquals(ids.sorted(),ids)
        rules.forEach { rule ->
            listOf("id","topic","title","instruction","rationale","status","authority","policyLevel","applicability","evidence","checks","reviewTriggers").forEach { field ->
                assertTrue(rule.has(field),"missing $field")
            }
            assertTrue(rule["applicability"]["profiles"].isArray)
            assertTrue(rule["checks"].isArray)
            assertTrue(rule["status"].asText()=="approved")
        }
        assertTrue(node["conflicts"].isArray)
        assertTrue(node["excludedRules"].isArray)
        assertTrue(node["overriddenRules"].isArray)
        val excludedIds=node["excludedRules"].map { it["ruleId"].asText() }
        assertEquals(excludedIds.sorted(),excludedIds)
        node["excludedRules"].forEach { excluded ->
            val reasons=excluded["reasons"].map { it.asText() }
            assertEquals(reasons.sorted(),reasons)
        }
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
            listOf("resolve",knowledge.toString(),"--team","20827","--season","2025-2026","--generic-profile","--json"),
            PrintStream(out)
        )

        assertEquals(2,code)
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())
        assertFalse(node["ok"].booleanValue())
        assertEquals(1,node["conflicts"].size())
        assertEquals("same-topic",node["conflicts"][0]["topic"].asText())
        assertEquals(listOf("official.first","official.second"),node["conflicts"][0]["ruleIds"].map { it.asText() })
        assertEquals("official",node["conflicts"][0]["effectiveLevel"].asText())
        assertEquals("official",node["conflicts"][0]["authorities"]["official.first"].asText())
        assertEquals("official",node["conflicts"][0]["authorities"]["official.second"].asText())
    }

    @Test
    fun `resolve json publishes policy provenance and deterministic override details`(@TempDir root:Path) {
        Files.writeString(root.resolve("rules.yaml"),"""
            schemaVersion: 4
            rules:
              - id: shared.global-rule
                topic: naming-conventions
                title: Global naming
                instruction: Use the global naming rule.
                rationale: Global consistency.
                status: approved
                authority: shared
                policyLevel: global
                applicability:
                  teams: []
                  seasons: []
                  profiles: [rookiebot]
                reviewTriggers:
                  - paths: ["z/**", "a/**"]
                    addedLinePatterns: ["Zed", "Alpha"]
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: README.md
                    line: 1
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-09-11T00:00:00Z
              - id: shared.fallback-rule
                topic: naming-conventions
                title: Shared naming
                instruction: Use the shared naming rule.
                rationale: Shared fallback.
                status: approved
                authority: shared
                policyLevel: shared
                applicability:
                  teams: []
                  seasons: []
                  profiles: []
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef2
                    file: README.md
                    line: 2
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-09-11T00:00:00Z
        """.trimIndent())
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf(
                "resolve",root.toString(),"--team","20827","--season","2025-2026",
                "--profile","rookiebot","--json"
            ),
            PrintStream(out)
        )
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())

        assertEquals(0,code)
        assertEquals("global",node["activeRules"][0]["policyLevel"].asText())
        assertEquals(listOf("a/**","z/**"),node["activeRules"][0]["reviewTriggers"][0]["paths"].map { it.asText() })
        assertEquals(
            listOf("Alpha","Zed"),
            node["activeRules"][0]["reviewTriggers"][0]["addedLinePatterns"].map { it.asText() }
        )
        assertEquals("shared.fallback-rule",node["overriddenRules"][0]["ruleId"].asText())
        assertEquals(listOf("shared.global-rule"),node["overriddenRules"][0]["winnerIds"].map { it.asText() })
        assertEquals("global",node["overriddenRules"][0]["effectiveLevel"].asText())
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
        assertEquals(2,mapper.readTree(KernelJson.validateJson(48))["schemaVersion"].asInt())
        Files.list(base).use { paths ->
            val fixtures=paths.filter { it.toString().endsWith(".json") }.sorted().toList()
            assertEquals(12,fixtures.size)
            fixtures.forEach { fixture -> assertSchemaValid(Files.readString(fixture)) }
        }

        val validate=mapper.readTree(Files.readString(base.resolve("validate-ok.json")))
        assertEquals(2,validate["schemaVersion"].asInt())
        assertEquals("validate",validate["command"].asText())
        assertTrue(validate["ok"].booleanValue())
        assertEquals(48,validate["ruleCount"].asInt())

        val resolve=mapper.readTree(Files.readString(base.resolve("resolve-ok.json")))
        assertEquals("resolve",resolve["command"].asText())
        assertTrue(resolve["activeRules"].isArray)
        assertEquals(26,resolve["activeRules"].size())
        assertTrue(resolve["conflicts"].isArray)
        val resolveById=resolve["activeRules"].associateBy { it["id"].asText() }
        val layout=resolveById.getValue("global.test-utility-layout")
        assertEquals("approved",layout["status"].asText())
        assertEquals("shared",layout["authority"].asText())
        assertEquals("global",layout["policyLevel"].asText())
        listOf("teams","seasons","profiles").forEach { field ->
            assertEquals(0,layout["applicability"][field].size(),field)
        }
        assertTrue(layout["checks"].size()>0)
        val evidence=layout["evidence"]
        assertEquals(1,evidence.size())
        assertEquals("lucasnotfound59/FTC-Knowledge-Bank",evidence[0]["repository"].asText())
        assertEquals("6aa385d75484b22b3f73c3b493a695e753ccc76e",evidence[0]["commit"].asText())
        assertEquals("docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md",evidence[0]["file"].asText())
        assertEquals(8,evidence[0]["line"].asInt())
        setOf(
            "shared.limelight-check-result-validity",
            "shared.limelight-enforce-freshness-policy"
        ).forEach { id ->
            assertEquals(0,resolveById.getValue(id)["checks"].size(),id)
            assertEquals(1,resolveById.getValue(id)["reviewTriggers"].size(),id)
        }
        val vendor=resolveById.getValue("global.vendor-documented-build-dependencies")
        assertEquals("approved",vendor["status"].asText())
        assertEquals("shared",vendor["authority"].asText())
        assertEquals("global",vendor["policyLevel"].asText())
        listOf("teams","seasons","profiles").forEach { field ->
            assertEquals(0,vendor["applicability"][field].size(),field)
        }
        assertEquals(0,vendor["checks"].size())
        assertEquals(1,vendor["reviewTriggers"].size())
        assertEquals(listOf("build.dependencies.gradle"),vendor["reviewTriggers"][0]["paths"].map { it.asText() })
        assertEquals(0,vendor["reviewTriggers"][0]["addedLinePatterns"].size())
        assertEquals(4,vendor["evidence"].size())

        val error=mapper.readTree(Files.readString(base.resolve("error-usage.json")))
        assertFalse(error["ok"].booleanValue())
        assertEquals("usage",error["error"]["code"].asText())

        val testMode=mapper.readTree(Files.readString(base.resolve("resolve-test.json")))
        assertEquals("test",testMode["workMode"].asText())
        assertEquals(2,testMode["schemaVersion"].asInt())
        assertEquals(
            listOf("global.command-responsibilities","shared.ftclib-command-candidate"),
            testMode["excludedRules"].filter {
                it["reasons"].any { reason -> reason.asText()=="work-mode-test" }
            }.map { it["ruleId"].asText() }
        )

        val devMode=mapper.readTree(Files.readString(base.resolve("check-dev.json")))
        assertEquals("dev",devMode["workMode"].asText())
        assertEquals(2,devMode["schemaVersion"].asInt())
        assertTrue(devMode["ok"].booleanValue())
        assertEquals(0,devMode["violations"].size())
        assertEquals(4,devMode["soft"].size())
        devMode["soft"].forEach { note ->
            assertTrue(note["note"].asText().isNotEmpty(),note.toString())
        }
        assertEquals(
            3,
            devMode["soft"].count { it["note"].asText().startsWith("work-mode=dev; downgraded hard check=") }
        )

        val conflict=mapper.readTree(Files.readString(base.resolve("resolve-conflict.json")))
        assertEquals("resolve",conflict["command"].asText())
        assertFalse(conflict["ok"].booleanValue())
        assertTrue(conflict["conflicts"].size()>=1)
        assertEquals("same-topic",conflict["conflicts"][0]["topic"].asText())

        val invalid=mapper.readTree(Files.readString(base.resolve("error-invalid-knowledge.json")))
        assertFalse(invalid["ok"].booleanValue())
        assertEquals("invalid-knowledge",invalid["error"]["code"].asText())
        assertTrue(invalid["violations"].size()>=1)
        assertEquals("shared.invalid-commit",invalid["violations"][0]["ruleId"].asText())
    }

    @Test
    fun `json mode usage errors keep the stable error shape`() {
        val out=ByteArrayOutputStream()

        val code=runCli(
            listOf("resolve","does-not-exist","--team","20827","--json"),
            PrintStream(out)
        )

        assertEquals(64,code)
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())
        assertEquals(2,node["schemaVersion"].asInt())
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
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())
        assertEquals(2,node["schemaVersion"].asInt())
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
        assertSchemaValid(out.toString())
        val node=mapper.readTree(out.toString())
        assertFalse(node["ok"].booleanValue())
        assertEquals("invalid-knowledge",node["error"]["code"].asText())
        assertEquals(1,node["violations"].size())
        assertEquals("shared.invalid-commit",node["violations"][0]["ruleId"].asText())
        assertEquals("evidence[0].commit",node["violations"][0]["field"].asText())
    }

    private fun assertSchemaValid(json:String) {
        val schema=Path.of("..","..","docs","kernel-contract.schema.json").toAbsolutePath().normalize()
        val process=ProcessBuilder(
            "python3","-c",
            "import json,jsonschema,sys; jsonschema.validate(json.load(sys.stdin),json.load(open(sys.argv[1])))",
            schema.toString()
        ).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(json) }
        val diagnostic=process.inputStream.bufferedReader().readText()
        assertEquals(0,process.waitFor(),"$diagnostic\n$json")
    }
}
