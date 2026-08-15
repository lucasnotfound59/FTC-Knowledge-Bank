package org.ftckb.agent.edit

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.agent.ContextRetriever
import org.ftckb.agent.ConversationState
import org.ftckb.agent.KnowledgeRetriever
import org.ftckb.agent.RetrievalPlanner
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.repository.LocalQuery
import org.ftckb.repository.RepositoryIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditAgentTest {
    @Test
    fun `valid edit records history refreshes index and persists only a compact summary`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(
            retrievalPlan(),
            """{"summary":"Add safety marker","operations":[{"kind":"create","path":"TeamCode/Safety.java","expectedAbsent":true,"content":"class Safety { String marker=\"fresh-index-text\"; }\n","reason":"Add the requested safety marker.","citations":["CODE:C1"]}]}"""
        )
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository)
        val history=EditHistory(repository,engine)
        val conversation=ConversationState(provider)
        val agent=EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge(root),null,null)),
            provider,index,engine,history,conversation,"supported FTC repository"
        )

        val report=agent.edit("Add a safety marker")

        assertEquals(setOf("TeamCode/Safety.java"),report.changedPaths)
        assertTrue(report.diff.contains("+++ b/TeamCode/Safety.java"))
        assertEquals(
            listOf("TeamCode/Safety.java"),
            index.search(LocalQuery(setOf("fresh-index-text")),4).map { it.path }
        )
        assertEquals(setOf("TeamCode/Safety.java"),history.changes().mapTo(linkedSetOf()) { it.path })
        val savedTurn=conversation.context().recentTurns.single()
        assertTrue(savedTurn.answer.claims.single().text.contains("Add safety marker"))
        assertFalse(savedTurn.answer.claims.single().text.contains("fresh-index-text"))
        assertFalse(savedTurn.answer.claims.single().text.contains("Add the requested safety marker"))
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `invalid edit JSON receives exactly one bounded repair`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(
            retrievalPlan(),"not json",
            """{"summary":"repaired","operations":[{"kind":"create","path":"TeamCode/Repaired.java","expectedAbsent":true,"content":"class Repaired {}\n","reason":"repair","citations":[]}]}"""
        )
        val agent=agent(root,repository,provider)

        val report=agent.edit("Create repaired file")

        assertEquals(setOf("TeamCode/Repaired.java"),report.changedPaths)
        assertEquals(3,provider.requests.size)
        val repair=provider.requests.last().messages.last().content.substringAfter("Repair the previous response: ")
        assertTrue(repair.isNotBlank())
        assertTrue(repair.length<=500)
    }

    @Test
    fun `invalid citations receive one repair then reject with zero writes`(@TempDir root:Path) {
        val repository=repository(root)
        val invalid="""{"summary":"invalid","operations":[{"kind":"create","path":"TeamCode/Unsafe.java","expectedAbsent":true,"content":"class Unsafe {}\n","reason":"unsupported","citations":["RULE:R99"]}]}"""
        val provider=ScriptedProvider(retrievalPlan(),invalid,invalid)
        val agent=agent(root,repository,provider)

        assertThrows(EditValidationException::class.java) { agent.edit("Create unsupported file") }

        assertTrue(Files.notExists(repository.resolve("TeamCode/Unsafe.java")))
        assertEquals(3,provider.requests.size)
        assertTrue(provider.requests.last().messages.last().content.contains("Repair the previous response"))
    }

    @Test
    fun `preview failures receive one repair then reject protected paths with zero writes`(@TempDir root:Path) {
        val repository=repository(root)
        val invalid="""{"summary":"invalid","operations":[{"kind":"create","path":".env","expectedAbsent":true,"content":"secret=value\n","reason":"unsafe","citations":[]}]}"""
        val provider=ScriptedProvider(retrievalPlan(),invalid,invalid)
        val agent=agent(root,repository,provider)

        assertThrows(EditValidationException::class.java) { agent.edit("Create protected file") }

        assertTrue(Files.notExists(repository.resolve(".env")))
        assertEquals(3,provider.requests.size)
        assertTrue(provider.requests.last().messages.last().content.contains("edit path is protected"))
    }

    @Test
    fun `apply failure is never sent back to the model for retry`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(
            retrievalPlan(),
            """{"summary":"write once","operations":[{"kind":"create","path":"TeamCode/Once.java","expectedAbsent":true,"content":"class Once {}\n","reason":"once","citations":[]}]}"""
        )
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository,beforeMutation={ _,_->throw IOException("synthetic write failure") })
        val history=EditHistory(repository,engine)
        val agent=EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge(root),null,null)),
            provider,index,engine,history,ConversationState(provider),"supported FTC repository"
        )

        assertThrows(FileEditApplyException::class.java) { agent.edit("Write once") }

        assertEquals(2,provider.requests.size)
        assertTrue(Files.notExists(repository.resolve("TeamCode/Once.java")))
        assertTrue(history.changes().isEmpty())
    }

    @Test
    fun `conflicting rules cannot authorize an edit`(@TempDir root:Path) {
        val repository=repository(root)
        val knowledge=Files.createDirectories(root.resolve("conflicting-knowledge/official"))
        Files.writeString(knowledge.resolve("rules.yaml"),conflictingRules())
        val invalid="""{"summary":"conflict","operations":[{"kind":"create","path":"TeamCode/Conflict.java","expectedAbsent":true,"content":"class Conflict {}\n","reason":"claimed conflict rule","citations":["RULE:R1"]}]}"""
        val provider=ScriptedProvider(
            """{"concepts":[],"symbols":[],"pathGlobs":[],"ruleTopics":["conflict-topic"],"guideTopics":[]}""",
            invalid,invalid
        )
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository)
        val agent=EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge.parent,null,null)),
            provider,index,engine,EditHistory(repository,engine),ConversationState(provider),"supported FTC repository"
        )

        assertThrows(EditValidationException::class.java) { agent.edit("Apply conflicting rule") }

        assertTrue(Files.notExists(repository.resolve("TeamCode/Conflict.java")))
        assertEquals(3,provider.requests.size)
    }

    private fun repository(root:Path):Path=root.resolve("repository").also { repository ->
        Files.createDirectories(repository.resolve("TeamCode"))
        Files.writeString(repository.resolve("TeamCode/Drive.java"),"class Drive {}\n")
    }

    private fun knowledge(root:Path):Path=root.resolve("knowledge").also(Files::createDirectories)

    private fun agent(root:Path,repository:Path,provider:ModelProvider):EditAgent {
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository)
        return EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge(root),null,null)),
            provider,index,engine,EditHistory(repository,engine),ConversationState(provider),"supported FTC repository"
        )
    }

    private fun retrievalPlan()=
        """{"concepts":["Drive"],"symbols":[],"pathGlobs":["TeamCode/**"],"ruleTopics":[],"guideTopics":[]}"""

    private fun conflictingRules()="""
        schemaVersion: 1
        rules:
          - id: official.conflict-one
            topic: conflict-topic
            title: Conflict one
            instruction: Do one thing.
            rationale: Test conflict.
            status: approved
            authority: official
            applicability: {teams: [], seasons: []}
            evidence:
              - {repository: example/repo, commit: abcdef1, file: Source.java, symbol: Source}
            approval: {approver: lead, role: overall_software_lead, approvedAt: 2026-08-15T00:00:00Z}
          - id: official.conflict-two
            topic: conflict-topic
            title: Conflict two
            instruction: Do another thing.
            rationale: Test conflict.
            status: approved
            authority: official
            applicability: {teams: [], seasons: []}
            evidence:
              - {repository: example/repo, commit: abcdef2, file: Source.java, symbol: Source}
            approval: {approver: lead, role: overall_software_lead, approvedAt: 2026-08-15T00:00:00Z}
    """.trimIndent()

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val responses=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            return ModelResponse(responses.removeFirst())
        }
    }
}
