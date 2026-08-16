package org.ftckb.agent

import org.ftckb.model.MessageRole
import org.ftckb.model.ModelMessage
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.model.TokenUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialRedactorTest {
    @Test
    fun `redacts quoted property and indexed API key assignments`() {
        val value=listOf("synthetic","runtime","credential").joinToString("-")
        val quotedKey=listOf("api","key").joinToString("_")
        val indexedKey=listOf("api","Key").joinToString("")
        val text="\"$quotedKey\": \"$value\" and config[\"$indexedKey\"]='$value'"

        val redacted=CredentialRedactor.redact(text)

        assertFalse(redacted.contains(value))
        assertFalse(redacted.contains(quotedKey))
        assertFalse(redacted.contains(indexedKey))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacting provider sanitizes exact and common secrets in model responses`() {
        val exact=listOf("exact","response","credential").joinToString("-")
        val common=listOf("sk","response","credential").joinToString("-")
        val usage=TokenUsage(3,5)
        val provider=RedactingModelProvider(
            ModelProvider { ModelResponse("answer $exact $common SERVICE_API_KEY=response-value",usage) },
            setOf(exact)
        )

        val response=provider.complete(ModelRequest(
            listOf(ModelMessage(MessageRole.USER,"safe request")),64
        ))

        assertFalse(response.content.contains(exact))
        assertFalse(response.content.contains(common))
        assertFalse(response.content.contains("response-value"))
        assertTrue(response.content.contains("[REDACTED"))
        assertEquals(usage,response.usage)
    }
}
