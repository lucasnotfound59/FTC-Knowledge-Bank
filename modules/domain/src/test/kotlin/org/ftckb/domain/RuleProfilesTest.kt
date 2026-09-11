package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RuleProfilesTest {
    @Test fun `ftclib is command based and simple opmode is incompatible`() {
        assertEquals(setOf("command-based","ftclib-command"),RuleProfiles.normalize(setOf("ftclib-command")))
        assertThrows(RuleContextException::class.java) {
            RuleProfiles.normalize(setOf("simple-opmode","command-based"))
        }
    }

    @Test fun normalization() {
        assertEquals(setOf("rookiebot","simple-opmode"),RuleProfiles.normalize(setOf("rookiebot")))
        assertEquals(emptySet<String>(),RuleProfiles.normalize(emptySet()))
        assertThrows(RuleContextException::class.java) { RuleProfiles.normalize(null) }
        assertThrows(RuleContextException::class.java) { RuleProfiles.normalize(setOf("rookiebto")) }
        assertThrows(RuleContextException::class.java) {
            RuleProfiles.normalize(setOf("rookiebot","ftclib-command"))
        }
    }
}
