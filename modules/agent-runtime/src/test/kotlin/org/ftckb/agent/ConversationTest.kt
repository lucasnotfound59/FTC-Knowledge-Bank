package org.ftckb.agent

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConversationTest {
    @TempDir
    lateinit var root:Path

    @Test
    fun `keeps recent references and summarizes older turns beyond the character budget`() {
        val provider=ScriptedProvider("User goal: inspect Drive. Named file: TeamCode/Drive.java. Unresolved: null safety.")
        val state=ConversationState(provider,maximumRecentCharacters=100)

        state.record("Drive?",answer("Moves."),setOf("TeamCode/Drive.java"))
        state.record("Next?",answer("Check."),setOf("TeamCode/Drive.java"))

        val context=state.context()
        assertEquals(listOf("Next?"),context.recentTurns.map { it.question })
        assertEquals(setOf("TeamCode/Drive.java"),context.recentReferences)
        assertEquals("User goal: inspect Drive. Named file: TeamCode/Drive.java. Unresolved: null safety.",context.rollingSummary)
        assertEquals(1,provider.requests.size)
        assertTrue(provider.requests.single().messages.last().content.contains("Drive?"))
    }

    @Test
    fun `saves an explicit redacted markdown session without code bodies`() {
        val secret=syntheticSecret()
        val authorizationLabel=listOf("Author","ization").joinToString("")
        val bearer=listOf("Bear","er").joinToString("")
        val apiKeyName=listOf("API","KEY").joinToString("_")
        val bearerValue="synthetic-bearer-value"
        val state=ConversationState(ScriptedProvider(),exactSecrets=setOf(secret))
        state.record(
            "How does $secret work? $authorizationLabel: $bearer $bearerValue $apiKeyName=$secret",
            AgentAnswer(listOf(AnswerClaim(ClaimKind.CODE_OBSERVATION,"Drive uses $secret.",listOf("CODE:$secret"))),null),
            setOf("TeamCode/$secret.java")
        )
        val saver=ConversationSaver(
            "deepseek","deepseek-chat",setOf(secret),
            Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"),ZoneOffset.UTC)
        )

        val saved=saver.save(state,root.resolve("session.md"))
        val text=Files.readString(saved)

        assertTrue(text.contains("Provider: deepseek / deepseek-chat"))
        assertFalse(text.contains(secret))
        assertFalse(text.contains("$authorizationLabel:"))
        assertFalse(text.contains(bearerValue))
        assertFalse(text.contains("class Drive"))
        assertThrows(FileAlreadyExistsException::class.java) { saver.save(state,saved) }
    }

    @Test
    fun `redacts exact secrets in citation and reference fields before summary prompts`() {
        val secret=syntheticSecret()
        val provider=ScriptedProvider("Goal: inspect the drive.","Goal: inspect the drive.")
        val state=ConversationState(provider,exactSecrets=setOf(secret),maximumRecentCharacters=100)

        state.record("Drive?",answer("Moves."),setOf("TeamCode/$secret.java"))
        state.record("Next?",AgentAnswer(listOf(AnswerClaim(ClaimKind.MODEL_INFERENCE,"Check.",listOf("CODE:$secret"))),null),setOf("TeamCode/Drive.java"))

        assertTrue(provider.requests.flatMap { it.messages }.none { it.content.contains(secret) })
    }

    @Test
    fun `compacts an oversized newest turn so recent context stays under 24000 characters`() {
        val state=ConversationState(ScriptedProvider("Goal: inspect Drive."))

        state.record("Drive?",answer("x".repeat(25_000)),emptySet())

        assertTrue(state.recentTurnCharacters()<=24_000)
        assertTrue(state.context().recentTurns.isEmpty())
        assertEquals("Goal: inspect Drive.",state.context().rollingSummary)
    }

    private fun answer(text:String)=AgentAnswer(listOf(AnswerClaim(ClaimKind.MODEL_INFERENCE,text,emptyList())),null)

    private fun syntheticSecret()=listOf("sk","synthetic","value").joinToString("-")

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val queue=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            return ModelResponse(queue.removeFirst())
        }
    }
}
