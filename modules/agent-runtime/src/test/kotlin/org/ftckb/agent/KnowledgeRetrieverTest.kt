package org.ftckb.agent

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleViolation
import org.ftckb.knowledge.FileKnowledgeRepository
import org.ftckb.knowledge.KnowledgeSnapshot
import org.ftckb.knowledge.KnowledgeSourceError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KnowledgeRetrieverTest {
    @TempDir
    lateinit var tempDir:Path

    @Test
    fun `missing profile fails and explicit generic succeeds`() {
        writeKnowledge()
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRetriever(tempDir.resolve("knowledge"),"20827","2025-2026")
        }
        KnowledgeRetriever(tempDir.resolve("knowledge"),"20827","2025-2026",ruleProfiles=emptySet())
    }

    @Test
    fun `conflicts fail construction with topic and rule ids`() {
        writeKnowledge()
        val rules=listOf("one","two").joinToString("\n") { suffix ->
            """
              - id: official.conflict-$suffix
                topic: conflict-topic
                title: Conflict $suffix
                instruction: Do $suffix.
                rationale: Test conflict.
                status: approved
                authority: official
                applicability: {teams: [], seasons: []}
                evidence:
                  - {repository: example/repo, commit: abcdef1, file: Source.java, symbol: Source}
                approval: {approver: lead, role: overall_software_lead, approvedAt: 2026-08-15T00:00:00Z}
            """.trimIndent().prependIndent("  ")
        }
        tempDir.resolve("knowledge/rules.yaml").writeText("schemaVersion: 1\nrules:\n$rules\n")
        val failure=assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRetriever(tempDir.resolve("knowledge"),null,null,ruleProfiles=emptySet())
        }
        assertEquals("rule conflicts: conflict-topic: official.conflict-one,official.conflict-two",failure.message)
    }

    @Test
    fun `empty intent terms retrieve no guide sections`() {
        writeKnowledge()
        val retriever=KnowledgeRetriever(tempDir.resolve("knowledge"),null,null,ruleProfiles=emptySet())

        val guides=retriever.retrieveGuides(
            RetrievalIntent(emptySet(),emptySet(),emptySet(),emptySet(),emptySet())
        )

        assertEquals(emptyList<GuideEvidence>(),guides)
    }

    @Test
    fun `caps guide sections and prefers heading matches`() {
        writeKnowledge()
        val retriever=KnowledgeRetriever(tempDir.resolve("knowledge"),null,null,ruleProfiles=emptySet())

        val guides=retriever.retrieveGuides(
            RetrievalIntent(emptySet(),emptySet(),emptySet(),emptySet(),setOf("tune"))
        )

        assertTrue(guides.size<=2)
        assertTrue(guides.all { it.heading.contains("tune",ignoreCase=true) })
    }

    @Test
    fun `snapshot retrieval keeps captured rules after live YAML becomes invalid`() {
        writeKnowledge()
        val root=tempDir.resolve("knowledge")
        val rule="""
            schemaVersion: 1
            rules:
              - id: official.captured
                topic: captured-topic
                title: Captured rule
                instruction: Use captured instruction.
                rationale: Captured evidence.
                status: approved
                authority: official
                applicability: {teams: [], seasons: []}
                evidence:
                  - {repository: example/repo, commit: abcdef1, file: Source.java, symbol: Source}
                approval: {approver: lead, role: overall_software_lead, approvedAt: 2026-08-15T00:00:00Z}
        """.trimIndent()
        root.resolve("rules.yaml").writeText(rule)
        val loaded=FileKnowledgeRepository.load(root)
        val capturedRules=loaded.rules.toMutableList()
        val snapshot=KnowledgeSnapshot(root,"captured",capturedRules,emptyList(),emptyList(),emptyMap(),emptyMap())
        root.resolve("rules.yaml").writeText("this is invalid yaml: [")
        val retriever=KnowledgeRetriever.fromSnapshot(snapshot,RuleContext(null,null,emptySet()))
        capturedRules.clear()

        val rules=retriever.retrieveRules(RetrievalIntent(emptySet(),emptySet(),emptySet(),setOf("captured-topic"),emptySet()))

        assertEquals(listOf("official.captured"),rules.map { it.id })
        assertEquals("Use captured instruction.",rules.single().instruction)
    }

    @Test
    fun `snapshot factory rejects validation and load errors`() {
        writeKnowledge()
        val root=tempDir.resolve("knowledge")
        val snapshot=KnowledgeSnapshot(root,"bad",emptyList(),emptyList(),emptyList(),emptyMap(),emptyMap())
        val context=RuleContext(null,null,emptySet())

        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRetriever.fromSnapshot(snapshot.copy(violations=listOf(RuleViolation("bad","id","invalid"))),context)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRetriever.fromSnapshot(snapshot.copy(loadErrors=listOf(KnowledgeSourceError("bad.yaml","malformed"))),context)
        }
    }

    @Test
    fun `snapshot factory retains resolver conflict and explicit profile protections`() {
        writeKnowledge()
        val root=tempDir.resolve("knowledge")
        val empty=KnowledgeSnapshot(root,"empty",emptyList(),emptyList(),emptyList(),emptyMap(),emptyMap())
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRetriever.fromSnapshot(empty,RuleContext(null,null))
        }
        KnowledgeRetriever.fromSnapshot(empty,RuleContext(null,null,emptySet()))

        val rule=org.ftckb.domain.KnowledgeRule(
            "official.one","conflict-topic","One","Use one.","Evidence.",
            org.ftckb.domain.RuleStatus.APPROVED,org.ftckb.domain.RuleAuthority.OFFICIAL,
            org.ftckb.domain.RuleApplicability(),
            listOf(org.ftckb.domain.GitRuleEvidence("example/repo","abcdef1","Source.java","Source")),
            org.ftckb.domain.Approval("lead",org.ftckb.domain.ApproverRole.OVERALL_SOFTWARE_LEAD,
                approvedAt=java.time.Instant.parse("2026-08-15T00:00:00Z"))
        )
        val failure=assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRetriever.fromSnapshot(empty.copy(rules=listOf(rule,rule.copy(id="official.two"))),RuleContext(null,null,emptySet()))
        }
        assertEquals("rule conflicts: conflict-topic: official.one,official.two",failure.message)
    }

    @Test
    fun `real command framework guide is discoverable for representative architecture queries`() {
        val root=Path.of("..","..","knowledge")
        val retriever=KnowledgeRetriever(root,"20287","2026-2027",ruleProfiles=setOf("command-based"))
        listOf("Command-Based","FTCLib","类 FTCLib","自有实现").forEach { query ->
            val guides=retriever.retrieveGuides(RetrievalIntent(emptySet(),emptySet(),emptySet(),emptySet(),setOf(query)))
            assertTrue(guides.size<=2,"existing section budget: $query")
            assertTrue(guides.any { it.path=="guides/architecture/command-based.md" },
                "query=$query returned="+guides.joinToString { "${it.path}:${it.heading}" })
        }
    }

    private fun writeKnowledge() {
        val root=tempDir.resolve("knowledge")
        root.resolve("rules.yaml").apply {
            parent.createDirectories()
            writeText("schemaVersion: 1\nrules: []\n")
        }
        root.resolve("guides/tools/drive.md").apply {
            parent.createDirectories()
            writeText(
                "# Tune drive\nfirst section\n\n# Other heading\nsecond section\n\n"+
                    "# Yet another\nthird section\n\n# Fourth\nfourth section\n"
            )
        }
    }
}
