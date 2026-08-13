package org.ftckb.knowledge

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileKnowledgeRepositoryTest {
    @Test
    fun `loads yaml files in stable path order and reports violations`() {
        val root=Files.createTempDirectory("ftckb-rules")
        Files.writeString(root.resolve("b.yaml"),invalidApprovedRule("b.rule"))
        Files.writeString(root.resolve("a.yaml"),invalidApprovedRule("a.rule"))

        val result=FileKnowledgeRepository.load(root)

        assertEquals(listOf("a.rule","b.rule"),result.rules.map { it.id })
        assertEquals(2,result.violations.size)
        assertEquals(
            listOf("approved rule requires approval","approved rule requires approval"),
            result.violations.map { it.message }
        )
    }

    @Test
    fun `enforces approval roles while loading`() {
        val root=Files.createTempDirectory("ftckb-rules")
        Files.writeString(root.resolve("rule.yaml"),approvedSharedRuleWithTeamLead())

        val result=FileKnowledgeRepository.load(root)

        assertEquals(
            listOf("approval is not authorized for rule authority and teams"),
            result.violations.map { it.message }
        )
    }

    @Test
    fun `loads recursively and ignores yaml example files`() {
        val root=Files.createTempDirectory("ftckb-rules")
        Files.writeString(root.resolve("ignored.yaml.example"),"not valid yaml")
        val nested=Files.createDirectories(root.resolve("nested"))
        Files.writeString(nested.resolve("rule.yml"),candidateRule("shared.nested"))

        val result=FileKnowledgeRepository.load(root)

        assertEquals(listOf("shared.nested"),result.rules.map { it.id })
        assertEquals(emptyList<String>(),result.violations.map { it.message })
    }

    @Test
    fun `reports duplicate rule ids deterministically`() {
        val root=Files.createTempDirectory("ftckb-rules")
        Files.writeString(root.resolve("b.yaml"),candidateRule("shared.duplicate"))
        Files.writeString(root.resolve("a.yaml"),candidateRule("shared.duplicate"))

        val result=FileKnowledgeRepository.load(root)

        assertEquals(listOf("duplicate rule id"),result.violations.map { it.message })
    }

    private fun invalidApprovedRule(id:String)="""
        schemaVersion: 1
        rules:
          - id: $id
            topic: test
            title: Test
            instruction: Test instruction.
            rationale: Test rationale.
            status: approved
            authority: shared
            applicability: {}
            evidence:
              - repository: owner/repo
                commit: abcdef1
                file: TeamCode/build.gradle
                line: 1
    """.trimIndent()

    private fun candidateRule(id:String)="""
        schemaVersion: 1
        rules:
          - id: $id
            topic: test
            title: Test
            instruction: Test instruction.
            rationale: Test rationale.
            status: candidate
            authority: shared
            applicability: {}
            evidence:
              - repository: owner/repo
                commit: abcdef1
                file: TeamCode/build.gradle
                line: 1
    """.trimIndent()

    private fun approvedSharedRuleWithTeamLead()="""
        schemaVersion: 1
        rules:
          - id: shared.approval
            topic: test
            title: Test
            instruction: Test instruction.
            rationale: Test rationale.
            status: approved
            authority: shared
            applicability: {}
            evidence:
              - repository: owner/repo
                commit: abcdef1
                file: TeamCode/build.gradle
                line: 1
            approval:
              approver: lead-20827
              role: team_software_lead
              team: "20827"
              approvedAt: 1970-01-01T00:00:00Z
    """.trimIndent()
}
