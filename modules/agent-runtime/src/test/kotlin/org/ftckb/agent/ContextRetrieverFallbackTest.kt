package org.ftckb.agent

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.ftckb.repository.RepositoryIndex

class ContextRetrieverFallbackTest {
    @Test
    fun `includes repository sources when planned retrieval matches nothing`(@TempDir tempDir:Path) {
        val repositoryRoot=Files.createDirectories(tempDir.resolve("repository"))
        val source=repositoryRoot.resolve("TeamCode/src/main/java/example/SampleTeleOp.java")
        Files.createDirectories(source.parent)
        Files.writeString(source,"class SampleTeleOp { void run(){ motor.setPower(1.0); } }")
        val knowledgeRoot=Files.createDirectories(tempDir.resolve("knowledge"))
        val index=RepositoryIndex()
        index.build(repositoryRoot)

        val context=ContextRetriever(index,KnowledgeRetriever(knowledgeRoot,null,null))
            .retrieve(RetrievalIntent(setOf("pedro","localization"),emptySet(),emptySet(),emptySet(),emptySet()))

        val code=context.evidence.filterIsInstance<CodeEvidence>()
        assertTrue(code.isNotEmpty(),"fallback must include source evidence" )
        assertEquals("TeamCode/src/main/java/example/SampleTeleOp.java",code.single().path)
    }

    @Test
    fun `keeps planned code evidence when it already matches`(@TempDir tempDir:Path) {
        val repositoryRoot=Files.createDirectories(tempDir.resolve("repository"))
        val source=repositoryRoot.resolve("TeamCode/Drive.java")
        Files.createDirectories(source.parent)
        Files.writeString(source,"class Drive {}")
        val knowledgeRoot=Files.createDirectories(tempDir.resolve("knowledge"))
        val index=RepositoryIndex()
        index.build(repositoryRoot)

        val context=ContextRetriever(index,KnowledgeRetriever(knowledgeRoot,null,null))
            .retrieve(RetrievalIntent(setOf("drive"),emptySet(),emptySet(),emptySet(),emptySet()))

        val code=context.evidence.filterIsInstance<CodeEvidence>()
        assertEquals(listOf("TeamCode/Drive.java"),code.map { it.path })
    }
}
