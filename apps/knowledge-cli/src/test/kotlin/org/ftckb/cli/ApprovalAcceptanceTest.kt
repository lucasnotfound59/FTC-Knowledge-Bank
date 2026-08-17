package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApprovalAcceptanceTest {
    @Test
    fun `approve flips a team candidate to approved with an authorization-valid approval block`(@TempDir root:Path) {
        val knowledge=writeKnowledge(root)
        val output=ByteArrayOutputStream()

        val code=runCli(listOf(
            "approve",knowledge.toString(),"--id","team-20827.fsm-pattern","--approver","lead-20827",
            "--role","team_software_lead","--team","20827"
        ),PrintStream(output),StringReader("").buffered())

        assertEquals(0,code,output.toString())
        assertEquals("approved=team-20827.fsm-pattern\n",output.toString())
        val yaml=Files.readString(knowledge.resolve("rules.yaml"))
        assertTrue(yaml.contains("status: approved"),yaml)
        assertTrue(yaml.contains("approver: \"lead-20827\""),yaml)
        assertTrue(yaml.contains("role: team_software_lead"),yaml)
        assertTrue(yaml.contains("team: \"20827\""),yaml)
        assertTrue(yaml.contains("approvedAt: "),yaml)
        val loaded=FileKnowledgeRepository.load(knowledge)
        assertTrue(loaded.violations.isEmpty())
        val rule=loaded.rules.first { it.id=="team-20827.fsm-pattern" }
        assertEquals(RuleStatus.APPROVED,rule.status)
        assertEquals("lead-20827",rule.approval?.approver)
    }

    @Test
    fun `approve refuses an unauthorized approver and leaves the file untouched`(@TempDir root:Path) {
        val knowledge=writeKnowledge(root)
        val before=Files.readString(knowledge.resolve("rules.yaml"))
        val cases=listOf(
            listOf("approve",knowledge.toString(),"--id","team-20827.fsm-pattern","--approver","overall","--role","overall_software_lead"),
            listOf("approve",knowledge.toString(),"--id","team-20827.fsm-pattern","--approver","lead-16093","--role","team_software_lead","--team","16093"),
            listOf("approve",knowledge.toString(),"--id","shared.cleanup-helper","--approver","lead-20827","--role","team_software_lead","--team","20827")
        )
        cases.forEach { args ->
            val output=ByteArrayOutputStream()
            val code=runCli(args,PrintStream(output),StringReader("").buffered())
            assertEquals(2,code,"args=$args")
            assertTrue(output.toString().contains("approve refused: approval is not authorized"),output.toString())
            assertEquals(before,Files.readString(knowledge.resolve("rules.yaml")),"file must stay untouched")
        }
    }

    @Test
    fun `shared candidate requires the overall software lead`(@TempDir root:Path) {
        val knowledge=writeKnowledge(root)
        val output=ByteArrayOutputStream()

        val code=runCli(listOf(
            "approve",knowledge.toString(),"--id","shared.cleanup-helper","--approver","overall-lead",
            "--role","overall_software_lead"
        ),PrintStream(output),StringReader("").buffered())

        assertEquals(0,code,output.toString())
        val yaml=Files.readString(knowledge.resolve("rules.yaml"))
        assertTrue(yaml.contains("approver: \"overall-lead\""),yaml)
        assertFalse(yaml.contains("team: \"20827\"\n    approval"),yaml)
        val loaded=FileKnowledgeRepository.load(knowledge)
        assertTrue(loaded.violations.isEmpty())
        assertEquals(RuleStatus.APPROVED,loaded.rules.first { it.id=="shared.cleanup-helper" }.status)
    }

    @Test
    fun `reject flips a candidate to rejected without an approval block`(@TempDir root:Path) {
        val knowledge=writeKnowledge(root)
        val output=ByteArrayOutputStream()

        val code=runCli(listOf(
            "reject",knowledge.toString(),"--id","team-20827.fsm-pattern","--approver","lead-20827",
            "--role","team_software_lead","--team","20827"
        ),PrintStream(output),StringReader("").buffered())

        assertEquals(0,code,output.toString())
        assertEquals("rejected=team-20827.fsm-pattern\n",output.toString())
        val yaml=Files.readString(knowledge.resolve("rules.yaml"))
        assertTrue(yaml.contains("status: rejected"),yaml)
        val fsmBlock=yaml.substringAfter("- id: team-20827.fsm-pattern","")
            .substringBefore("- id: shared.cleanup-helper")
        assertFalse(fsmBlock.contains("approval:"),"rejected rule block must not gain an approval block")
        val loaded=FileKnowledgeRepository.load(knowledge)
        assertTrue(loaded.violations.isEmpty())
        assertEquals(RuleStatus.REJECTED,loaded.rules.first { it.id=="team-20827.fsm-pattern" }.status)
    }

    @Test
    fun `approve refuses missing and non-candidate rules`(@TempDir root:Path) {
        val knowledge=writeKnowledge(root)
        val missing=ByteArrayOutputStream()
        assertEquals(2,runCli(listOf("approve",knowledge.toString(),"--id","team-20827.nope","--approver","lead-20827","--role","team_software_lead","--team","20827"),PrintStream(missing),StringReader("").buffered()))
        assertTrue(missing.toString().contains("approve refused: rule not found"),missing.toString())
        val approved=ByteArrayOutputStream()
        assertEquals(2,runCli(listOf("approve",knowledge.toString(),"--id","official.keep","--approver","overall-lead","--role","overall_software_lead"),PrintStream(approved),StringReader("").buffered()))
        assertTrue(approved.toString().contains("approve refused: rule is not a candidate"),approved.toString())
    }

    @Test
    fun `candidates lists candidate rules in text and json mode`(@TempDir root:Path) {
        val knowledge=writeKnowledge(root)
        val text=ByteArrayOutputStream()
        assertEquals(0,runCli(listOf("candidates",knowledge.toString()),PrintStream(text),StringReader("").buffered()))
        val textOut=text.toString()
        assertTrue(textOut.contains("candidates=2"),textOut)
        assertTrue(textOut.contains("candidate team-20827.fsm-pattern"),textOut)
        assertTrue(textOut.contains("candidate shared.cleanup-helper"),textOut)
        assertFalse(textOut.contains("official.keep"),textOut)

        val json=ByteArrayOutputStream()
        assertEquals(0,runCli(listOf("candidates",knowledge.toString(),"--json"),PrintStream(json),StringReader("").buffered()))
        val node=com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(json.toString())
        assertEquals("candidates",node["command"].asText())
        assertTrue(node["ok"].booleanValue())
        assertEquals(listOf("shared.cleanup-helper","team-20827.fsm-pattern"),node["candidates"].map { it["id"].asText() })
    }

    @Test
    fun `approve parse failures exit sixty four`() {
        val output=ByteArrayOutputStream()
        assertEquals(64,runCli(listOf("approve","k","--id","x","--approver","a"),PrintStream(output),StringReader("").buffered()))
        assertEquals("missing --role\n",output.toString())
        val badRole=ByteArrayOutputStream()
        assertEquals(64,runCli(listOf("approve","k","--id","x","--approver","a","--role","admin"),PrintStream(badRole),StringReader("").buffered()))
        assertEquals("invalid value for --role: expected team_software_lead or overall_software_lead\n",badRole.toString())
    }

    private fun writeKnowledge(root:Path):Path {
        val knowledge=Files.createDirectories(root.resolve("knowledge"))
        Files.writeString(knowledge.resolve("rules.yaml"),"""
            schemaVersion: 1
            rules:
              - id: team-20827.fsm-pattern
                topic: fsm-pattern
                title: Use an FSM for autonomous phases
                instruction: Keep autonomous phases in one state machine.
                rationale: Predictable transitions.
                status: candidate
                authority: team
                applicability:
                  teams: ["20827"]
                  seasons: []
                evidence:
                  - repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/src/main/java/example/FsmRunner.java
                    symbol: FsmRunner
              - id: shared.cleanup-helper
                topic: cleanup-helper
                title: Add cleanup helpers
                instruction: Add a cleanup helper for TeleOp stop.
                rationale: Avoid leaking resources.
                status: candidate
                authority: shared
                applicability:
                  teams: []
                  seasons: []
                evidence:
                  - repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/src/main/java/example/SampleTeleOp.java
                    symbol: SampleTeleOp
              - id: official.keep
                topic: keep
                title: Keep existing
                instruction: Keep existing behavior.
                rationale: Official.
                status: approved
                authority: official
                applicability:
                  teams: []
                  seasons: []
                evidence:
                  - repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/src/main/java/example/SampleTeleOp.java
                    symbol: SampleTeleOp
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
        """.trimIndent()+"\n")
        return knowledge
    }
}
