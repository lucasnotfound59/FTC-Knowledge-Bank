package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun `domain module runs on Java 21`() {
        assertEquals(21,Runtime.version().feature())
    }
}
