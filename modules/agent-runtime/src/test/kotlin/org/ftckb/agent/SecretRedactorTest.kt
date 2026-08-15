package org.ftckb.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `merges overlapping exact and shaped spans in either containment direction`() {
        val bearer=listOf("Bear","er").joinToString("")
        val bearerValue=listOf("opaque","credential","value").joinToString("-")
        val shaped="$bearer $bearerValue"
        val containing=listOf("prefix",shaped,"suffix").joinToString("-")

        val exactInside=SecretRedactor.redact(shaped,setOf("credential"))
        val shapedInside=SecretRedactor.redact(containing,setOf(containing))

        assertEquals(RedactionResult("[REDACTED]",1),exactInside)
        assertEquals(RedactionResult("[REDACTED]",1),shapedInside)
    }

    @Test
    fun `leaves ordinary words and short hyphenated labels unchanged`() {
        val ordinary="authorization guide, bearer concept, apiKey naming, sketch, sk-short"

        val result=SecretRedactor.redact(ordinary)

        assertEquals(ordinary,result.text)
        assertEquals(0,result.redactionCount)
    }

    @Test
    fun `handles a large adversarial identifier without timing dependent assertions`() {
        val keyName=listOf("api","key").joinToString("_")
        val text="a".repeat(1024*1024)+" $keyName=tail-value"

        val result=SecretRedactor.redact(text)

        assertEquals(1,result.redactionCount)
        assertTrue(result.text.endsWith(" [REDACTED]"))
    }

    @Test
    fun `fails closed before dense exact matches amplify output`() {
        val text="x".repeat(4*1024*1024)

        val result=SecretRedactor.redact(text,setOf("x"))

        assertEquals(RedactionResult("[REDACTED]",1),result)
    }

    @Test
    fun `fails closed when contained candidates exceed the work budget`() {
        val coveredPrefix="a".repeat(5_000)
        val secrets=(listOf(coveredPrefix)+(1..63).map { length -> "a".repeat(length) }).toSet()

        val result=SecretRedactor.redact(coveredPrefix+"z",secrets)

        assertEquals(RedactionResult("[REDACTED]",1),result)
    }

    @Test
    fun `fails closed on a four MiB full exact match before scanning contained secrets`() {
        val text="a".repeat(4*1024*1024)
        val secrets=(listOf(text)+(1..63).map { length -> "a".repeat(length) }).toSet()

        val result=SecretRedactor.redact(text,secrets)

        assertEquals(RedactionResult("[REDACTED]",1),result)
    }
}
