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
import org.junit.jupiter.api.Assertions.assertNotNull
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

        state.record("Explain Drive",answer("The Drive class controls movement."),setOf("TeamCode/Drive.java"))
        state.record("What should I inspect next?",answer("Check the null guard before use."),setOf("TeamCode/Drive.java"))

        val context=state.context()
        assertEquals(listOf("What should I inspect next?"),context.recentTurns.map { it.question })
        assertEquals(setOf("TeamCode/Drive.java"),context.recentReferences)
        assertEquals("User goal: inspect Drive. Named file: TeamCode/Drive.java. Unresolved: null safety.",context.rollingSummary)
        assertEquals(1,provider.requests.size)
        assertTrue(provider.requests.single().messages.last().content.contains("Explain Drive"))
    }

    @Test
    fun `saves an explicit redacted markdown session without code bodies`() {
        val state=ConversationState(ScriptedProvider())
        state.record(
            "How does sk-secret-value work? Authorization: Bearer synthetic-bearer-value API_KEY=exact-synthetic-secret",
            AgentAnswer(listOf(AnswerClaim(ClaimKind.CODE_OBSERVATION,"Drive uses exact-synthetic-secret.",listOf("CODE:C1"))),null),
            setOf("TeamCode/Drive.java")
        )
        val saver=ConversationSaver(
            "deepseek","deepseek-chat",setOf("exact-synthetic-secret"),
            Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"),ZoneOffset.UTC)
        )

        val saved=saver.save(state,root.resolve("session.md"))
        val text=Files.readString(saved)

        assertTrue(text.contains("Provider: deepseek / deepseek-chat"))
        assertFalse(text.contains("sk-secret-value"))
        assertFalse(text.contains("Authorization:"))
        assertFalse(text.contains("synthetic-bearer-value"))
        assertFalse(text.contains("exact-synthetic-secret"))
        assertFalse(text.contains("class Drive"))
        assertThrows(FileAlreadyExistsException::class.java) { saver.save(state,saved) }
    }

    private fun answer(text:String)=AgentAnswer(listOf(AnswerClaim(ClaimKind.MODEL_INFERENCE,text,emptyList())),null)

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val queue=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            return ModelResponse(queue.removeFirst())
        }
    }
}
