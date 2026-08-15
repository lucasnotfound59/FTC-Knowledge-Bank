package org.ftckb.agent

import java.nio.file.Path
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelProviderException
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
        val secret=listOf("opaque","session","marker").joinToString(".")
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
        val saved=ConversationSaver(
            "deepseek","deepseek-chat",
            Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"),ZoneOffset.UTC)
        ).save(state,root.resolve("session.md"))
        assertTrue(!Files.readString(saved).contains(secret))
    }

    @Test
    fun `returns the ninth validated answer when only rolling summarization is rate limited`() {
        val provider=NinthTurnProvider()
        val agent=agent(provider)

        val answers=(1..9).map { turn -> agent.ask("question $turn") }

        assertEquals("validated answer 9",answers.last().claims.single().text)
        assertEquals(1,provider.summaryCalls)
        assertEquals(8,agent.conversation.context().recentTurns.size)
        assertTrue(agent.conversation.context().rollingSummary.orEmpty().contains("question 1"))
    }

    @Test
    fun `retains failed provider and citation submissions without inventing assistant answers`() {
        val provider=PendingFailureProvider()
        val agent=agent(provider)

        assertThrows(ModelProviderException.RateLimited::class.java) { agent.ask("provider failed question") }
        assertEquals(listOf("provider failed question"),agent.conversation.context().pendingQuestions)

        val recovered=agent.ask("recovery question")
        assertEquals("recovered",recovered.claims.single().text)
        assertTrue(provider.requests.any { request ->
            request.messages.any { "provider failed question" in it.content }
        })

        assertThrows(CitationValidationException::class.java) { agent.ask("citation failed question") }
        assertEquals(
            listOf("provider failed question","citation failed question"),
            agent.conversation.context().pendingQuestions
        )
        assertEquals(1,agent.conversation.context().recentTurns.size)
    }

    @Test
    fun `propagates a low planning token limit as a typed provider failure`() {
        val provider=LowLimitProvider(256)
        val agent=agent(provider)

        assertThrows(ModelProviderException.RequestLimit::class.java) { agent.ask("planning limit") }

        assertEquals(listOf(4096),provider.requests.map { it.maxOutputTokens })
        assertEquals(listOf("planning limit"),agent.conversation.context().pendingQuestions)
    }

    @Test
    fun `propagates a low answer token limit without converting it to citation failure`() {
        val provider=LowLimitProvider(512)
        val agent=agent(provider)

        assertThrows(ModelProviderException.RequestLimit::class.java) { agent.ask("answer limit") }

        assertEquals(listOf(4096),provider.requests.map { it.maxOutputTokens })
        assertEquals(listOf("answer limit"),agent.conversation.context().pendingQuestions)
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

    private fun plan()=PLAN
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

    private class NinthTurnProvider:ModelProvider {
        val requests=mutableListOf<ModelRequest>()
        var answerNumber=0
        var summaryCalls=0

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            val system=request.messages.first().content
            return when {
                system.startsWith("Return exactly one JSON object") -> ModelResponse(PLAN)
                system.startsWith("Answer only as JSON") -> {
                    answerNumber++
                    ModelResponse(
                        """{"claims":[{"kind":"model_inference","text":"validated answer $answerNumber","citations":[]}]}"""
                    )
                }
                system.startsWith("Produce a compact untrusted conversation summary") -> {
                    summaryCalls++
                    throw ModelProviderException.RateLimited()
                }
                else -> error("unexpected request")
            }
        }
    }

    private class PendingFailureProvider:ModelProvider {
        val requests=mutableListOf<ModelRequest>()
        private var firstPlanningCall=true

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            val system=request.messages.first().content
            return when {
                system.startsWith("Return exactly one JSON object") && firstPlanningCall -> {
                    firstPlanningCall=false
                    throw ModelProviderException.RateLimited()
                }
                system.startsWith("Return exactly one JSON object") -> ModelResponse(PLAN)
                system.startsWith("Answer only as JSON") && request.messages.last().content.startsWith("Question: recovery question\n") ->
                    ModelResponse("""{"claims":[{"kind":"model_inference","text":"recovered","citations":[]}]}""")
                system.startsWith("Answer only as JSON") ->
                    ModelResponse("""{"claims":[{"kind":"code_observation","text":"invalid","citations":["CODE:C99"]}]}""")
                else -> error("unexpected request")
            }
        }
    }

    private class LowLimitProvider(private val limit:Int):ModelProvider {
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            if (request.maxOutputTokens>limit) throw ModelProviderException.RequestLimit()
            return ModelResponse(PLAN)
        }
    }

    private companion object {
        const val PLAN="""{"concepts":["drive"],"symbols":[],"pathGlobs":[],"ruleTopics":[],"guideTopics":[]}"""
    }
}
