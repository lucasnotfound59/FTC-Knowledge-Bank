package org.ftckb.agent

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
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
