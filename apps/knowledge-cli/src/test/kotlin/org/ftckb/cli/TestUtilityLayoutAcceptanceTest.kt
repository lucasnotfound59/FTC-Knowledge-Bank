package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestUtilityLayoutAcceptanceTest {
    private val root=Path.of("..","..").normalize()
    private val reapproved=setOf(
        "shared.rookiebot-java-imports",
        "shared.rookiebot-verification-evidence"
    )

    private fun rules():List<KnowledgeRule> {
        val loaded=FileKnowledgeRepository.load(root.resolve("knowledge"))
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        return loaded.rules
    }

    @Test
    fun `RookieBot guidance no longer recommends JUnit test source sets`() {
        val selected=rules().filter { it.id in reapproved }
        assertEquals(reapproved,selected.map { it.id }.toSet())
        val forbidden=listOf("org.junit","junit.","testDebugUnitTest","TeamCode/src/test","单元测试")
        selected.forEach { rule ->
            assertEquals(RuleStatus.APPROVED,rule.status,rule.id)
            assertNotNull(rule.approval,rule.id)
            assertEquals("lucasnotfound59",rule.approval!!.approver,rule.id)
            assertEquals(ApproverRole.OVERALL_SOFTWARE_LEAD,rule.approval!!.role,rule.id)
            assertEquals(Instant.parse("2026-09-13T09:12:38Z"),rule.approval!!.approvedAt,rule.id)
            val current=listOf(rule.title,rule.instruction,rule.positiveExample.orEmpty(),rule.negativeExample.orEmpty())
                .joinToString("\n")
            forbidden.forEach { assertFalse(current.contains(it,ignoreCase=true),"${rule.id}: $it") }
        }
        val guide=Files.readString(root.resolve("knowledge/guides/practices/rookiebot-tutorial.md"))
        forbidden.forEach { assertFalse(guide.contains(it,ignoreCase=true),"guide: $it") }
        assertTrue(guide.contains("使用正确的项目类与 FTC API 导入"))
        assertTrue(guide.contains("分别报告构建、机器人测试和真机验证"))
        assertFalse(guide.contains("使用正确的项目类与测试断言导入"))
        assertFalse(guide.contains("分别报告编译、测试和真机验证"))
        assertTrue(guide.contains("teamcode/tests/"))
        assertTrue(guide.contains("teamcode/utils/"))
        assertTrue(guide.contains(":TeamCode:assembleDebug"))
    }
}
