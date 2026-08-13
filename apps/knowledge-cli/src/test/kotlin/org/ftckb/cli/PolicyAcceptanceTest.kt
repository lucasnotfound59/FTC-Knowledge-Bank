package org.ftckb.cli

import java.nio.file.Files
import org.ftckb.domain.*
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyAcceptanceTest {
    @Test
    fun `approved team override replaces shared rule but cannot replace official rule`() {
        val root=Files.createTempDirectory("ftckb-policy")
        Files.writeString(root.resolve("rules.yaml"),javaClass.getResource("/policy-acceptance.yaml")!!.readText())
        val loaded=FileKnowledgeRepository.load(root)
        assertTrue(loaded.violations.isEmpty())

        val result=RuleResolver.resolve(loaded.rules,RuleContext("20827","2025-2026"))

        assertEquals(setOf("team.pathing","official.deploy"),result.activeRules.map { it.id }.toSet())
        assertFalse(result.activeRules.any { it.id=="shared.pathing" || it.id=="team.deploy" })
        assertTrue(result.conflicts.isEmpty())
    }
}
