package org.ftckb.agent

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.Test

class SecretRedactorTest {
    @Test
    fun `redacts exact secrets longest first and reports matched spans`() {
        val prefix=listOf("resolved","credential").joinToString("-")
        val longer=listOf(prefix,"suffix").joinToString("-")

        val result=SecretRedactor.redact("$longer then $prefix",setOf(prefix,longer))

        assertEquals("[REDACTED] then [REDACTED]",result.text)
        assertEquals(2,result.redactionCount)
        assertFalse(result.text.contains(prefix))
    }

    @Test
    fun `redacts supported credential shapes with one marker per span`() {
        val authorization=listOf("Author","ization").joinToString("")
        val bearer=listOf("Bear","er").joinToString("")
        val token=listOf("sk","synthetic","credential").joinToString("-")
        val underscoredKey=listOf("api","key").joinToString("_")
        val camelKey=listOf("api","Key").joinToString("")
        val text=listOf(
            "$authorization: $bearer opaque-credential-value",
            token,
            "$underscoredKey=assigned-value",
            "$camelKey: yaml-value"
        ).joinToString("\n")

        val result=SecretRedactor.redact(text)

        assertEquals("[REDACTED]\n[REDACTED]\n[REDACTED]\n[REDACTED]",result.text)
        assertEquals(4,result.redactionCount)
    }

    @Test
    fun `redacts multiple standalone and exact secrets without overlapping replacements`() {
        val bearer=listOf("Bear","er").joinToString("")
        val common=listOf("sk","render","credential").joinToString("-")
        val exactPrefix=listOf("resolved","value").joinToString("-")
        val exactLonger=listOf(exactPrefix,"longer").joinToString("-")
        val text="$bearer opaque-value $exactLonger $exactPrefix$common"

        val result=SecretRedactor.redact(text,setOf(exactPrefix,exactLonger))

        assertEquals("[REDACTED] [REDACTED] [REDACTED][REDACTED]",result.text)
        assertEquals(4,result.redactionCount)
    }

    @Test
    fun `leaves ordinary words and short hyphenated labels unchanged`() {
        val ordinary="authorization guide, bearer concept, apiKey naming, sketch, sk-short"

        val result=SecretRedactor.redact(ordinary)

        assertEquals(ordinary,result.text)
        assertEquals(0,result.redactionCount)
    }

    @Test
    fun `handles large adversarial input in bounded time`() {
        val keyName=listOf("api","key").joinToString("_")
        val text="a".repeat(1024*1024)+" $keyName=tail-value"

        val result=assertTimeout(Duration.ofSeconds(3),ThrowingSupplier { SecretRedactor.redact(text) })

        assertEquals(1,result.redactionCount)
        assertTrue(result.text.endsWith(" [REDACTED]"))
    }
}
