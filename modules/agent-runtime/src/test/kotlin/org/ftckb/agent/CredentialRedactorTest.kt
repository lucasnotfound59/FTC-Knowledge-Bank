package org.ftckb.agent

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
        assertTrue(redacted.contains("[REDACTED_API_KEY]"))
    }
}
