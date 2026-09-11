package org.ftckb.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProfileArgumentsTest {
    @Test
    fun `generic profile is an explicit empty selection`() {
        val selection=ProfileArguments.extract(listOf("--team","20827","--generic-profile","--json"))

        assertEquals(listOf("--team","20827","--json"),selection.remaining)
        assertEquals(emptySet<String>(),selection.requiredProfiles())
    }

    @Test
    fun `repeated profiles are normalized deterministically`() {
        val selection=ProfileArguments.extract(
            listOf("--profile","ftclib-command","--season","2025-2026","--profile","ftclib-command")
        )

        assertEquals(listOf("--season","2025-2026"),selection.remaining)
        assertEquals(setOf("command-based","ftclib-command"),selection.requiredProfiles())
    }

    @Test
    fun `missing profile value is rejected`() {
        val exception=assertThrows(IllegalArgumentException::class.java) {
            ProfileArguments.extract(listOf("--profile","--json"))
        }

        assertEquals("--profile requires a value",exception.message)
    }

    @Test
    fun `duplicate generic profile is rejected`() {
        val exception=assertThrows(IllegalArgumentException::class.java) {
            ProfileArguments.extract(listOf("--generic-profile","--generic-profile"))
        }

        assertEquals("duplicate --generic-profile",exception.message)
    }

    @Test
    fun `generic and named profiles are mutually exclusive`() {
        val exception=assertThrows(IllegalArgumentException::class.java) {
            ProfileArguments.extract(listOf("--generic-profile","--profile","rookiebot"))
        }

        assertEquals("--profile and --generic-profile are mutually exclusive",exception.message)
    }

    @Test
    fun `unknown profile is a context error`() {
        val selection=ProfileArguments.extract(listOf("--profile","unknown"))

        val exception=assertThrows(IllegalArgumentException::class.java) { selection.requiredProfiles() }
        assertEquals("Unknown profiles: unknown",exception.message)
    }

    @Test
    fun `incompatible normalized profiles are rejected`() {
        val selection=ProfileArguments.extract(
            listOf("--profile","simple-opmode","--profile","ftclib-command")
        )

        val exception=assertThrows(IllegalArgumentException::class.java) { selection.requiredProfiles() }
        assertEquals("simple-opmode and command-based profiles are mutually exclusive",exception.message)
    }
}
