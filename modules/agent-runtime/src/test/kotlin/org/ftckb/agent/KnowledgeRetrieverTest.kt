package org.ftckb.agent

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KnowledgeRetrieverTest {
    @TempDir
    lateinit var tempDir:Path

    @Test
    fun `empty intent terms retrieve no guide sections`() {
        writeKnowledge()
        val retriever=KnowledgeRetriever(tempDir.resolve("knowledge"),null,null)

        val guides=retriever.retrieveGuides(
            RetrievalIntent(emptySet(),emptySet(),emptySet(),emptySet(),emptySet())
        )

        assertEquals(emptyList<GuideEvidence>(),guides)
    }

    @Test
    fun `caps guide sections and prefers heading matches`() {
        writeKnowledge()
        val retriever=KnowledgeRetriever(tempDir.resolve("knowledge"),null,null)

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
