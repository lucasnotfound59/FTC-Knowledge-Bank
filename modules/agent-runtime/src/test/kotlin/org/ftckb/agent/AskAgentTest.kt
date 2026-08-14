package org.ftckb.agent

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.repository.RepositoryIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AskAgentTest {
    @TempDir
    lateinit var root:Path

    @Test
    fun `passes a previously cited path to planning for a follow-up question`() {
        val provider=ScriptedProvider(plan(),answer(),plan(),answer())
        val agent=agent(provider)

        agent.ask("Explain the Drive class")
        agent.ask("刚才那个类要怎么改？")

        assertTrue(provider.requests[2].messages.last().content.contains("Recent references: TeamCode/Drive.java"))
        assertEquals(2,agent.conversation.context().recentTurns.size)
    }

    @Test
    fun `never accepts rolling summary text as current code evidence`() {
        val provider=ScriptedProvider(
            "Summary includes [CODE:C9] TeamCode/Legacy.java, but it is not evidence.",
            plan(),invalidAnswer(),invalidAnswer()
        )
        val state=ConversationState(provider,maximumRecentCharacters=100)
        state.record("old?",AgentAnswer(listOf(AnswerClaim(ClaimKind.MODEL_INFERENCE,"old response",emptyList())),null),setOf("TeamCode/Legacy.java"))
        state.record("new?",AgentAnswer(listOf(AnswerClaim(ClaimKind.MODEL_INFERENCE,"new response",emptyList())),null),setOf("TeamCode/Drive.java"))
        val agent=agent(provider,state)

        assertThrows(CitationValidationException::class.java) { agent.ask("Use the summary") }

        val answerRequest=provider.requests.last().messages.last().content
        assertTrue(answerRequest.contains("Conversation context (not evidence):"))
        assertTrue(answerRequest.contains("CODE:C9"))
        assertTrue(answerRequest.substringAfter("Evidence:\n").contains("TeamCode/Drive.java"))
        assertEquals(1,state.context().recentTurns.size)
    }

    @Test
    fun `redacts exact secrets in cited references before planning and answer prompts`() {
        val secret=listOf("sk","synthetic","value").joinToString("-")
        val provider=ScriptedProvider(plan(),answer())
        val state=ConversationState(provider,exactSecrets=setOf(secret))
        state.record(
            "prior question",
            AgentAnswer(listOf(AnswerClaim(ClaimKind.MODEL_INFERENCE,"prior answer",listOf("CODE:$secret"))),null),
            setOf("TeamCode/$secret.java")
        )
        val agent=agent(provider,state)

        agent.ask("follow up")

        assertTrue(provider.requests.flatMap { it.messages }.none { it.content.contains(secret) })
    }

    private fun agent(provider:ModelProvider,state:ConversationState=ConversationState(provider)):AskAgent {
        val repository=root.resolve("repository")
        repository.resolve("TeamCode").createDirectories()
        repository.resolve("TeamCode/Drive.java").writeText("class Drive { void run() {} }")
        val knowledge=root.resolve("knowledge")
        knowledge.createDirectories()
        val index=RepositoryIndex()
        index.build(repository)
        return AskAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge,null,null)),
            AnswerGenerator(provider,index),
            state,
            "FTC repository"
        )
    }

    private fun plan()="""{"concepts":["drive"],"symbols":[],"pathGlobs":[],"ruleTopics":[],"guideTopics":[]}"""
    private fun answer()="""{"claims":[{"kind":"code_observation","text":"Drive runs.","citations":["CODE:C1"]}]}"""
    private fun invalidAnswer()="""{"claims":[{"kind":"code_observation","text":"Legacy runs.","citations":["CODE:C9"]}]}"""

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val queue=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            return ModelResponse(queue.removeFirst())
        }
    }
}
