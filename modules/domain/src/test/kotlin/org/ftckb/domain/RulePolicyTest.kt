package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RulePolicyTest {
    private val evidence=GitRuleEvidence("repo","abcdef1","TeamCode/build.gradle",line=1)

    private fun rule(authority:RuleAuthority,policyLevel:PolicyLevel=RulePolicy.legacy(authority))=KnowledgeRule(
        id="shared.policy",topic="policy",title="Policy",instruction="Apply policy.",rationale="Test policy.",
        status=RuleStatus.CANDIDATE,authority=authority,applicability=RuleApplicability(teams=setOf("20827")),
        evidence=listOf(evidence),policyLevel=policyLevel
    )

    @Test
    fun `effective levels preserve all four priority ranks`() {
        val shared=RulePolicy.level(rule(RuleAuthority.SHARED,PolicyLevel.SHARED))
        val local=RulePolicy.level(rule(RuleAuthority.SHARED,PolicyLevel.LOCAL))
        val global=RulePolicy.level(rule(RuleAuthority.SHARED,PolicyLevel.GLOBAL))
        val official=RulePolicy.level(rule(RuleAuthority.OFFICIAL,PolicyLevel.GLOBAL))

        assertEquals(listOf(1,2,3,4),listOf(shared.rank,local.rank,global.rank,official.rank))
        assertTrue(shared.rank<local.rank)
        assertTrue(local.rank<global.rank)
        assertTrue(global.rank<official.rank)
    }

    @Test
    fun `policy and review trigger fields participate in value semantics and snapshot inputs`() {
        val paths=mutableListOf("TeamCode/**")
        val patterns=mutableListOf("Hardwares")
        val trigger=RuleReviewTrigger(paths,patterns)
        val triggers=mutableListOf(trigger)
        val original=rule(RuleAuthority.SHARED).copy(reviewTriggers=triggers)
        val copied=original.copy(policyLevel=PolicyLevel.GLOBAL,reviewTriggers=triggers)
        val authorityChanged=copied.copy(authority=RuleAuthority.TEAM)

        paths.clear()
        patterns.clear()
        triggers.clear()

        assertEquals(listOf("TeamCode/**"),trigger.paths)
        assertEquals(listOf("Hardwares"),trigger.addedLinePatterns)
        assertEquals(listOf(trigger),original.reviewTriggers)
        assertEquals(listOf(trigger),copied.reviewTriggers)
        assertEquals(PolicyLevel.GLOBAL,authorityChanged.policyLevel)
        assertEquals(listOf(trigger),authorityChanged.reviewTriggers)
        assertNotEquals(original,copied)
        assertNotEquals(original.hashCode(),copied.hashCode())
        assertTrue(copied.toString().contains("policyLevel=GLOBAL"))
        assertTrue(copied.toString().contains("reviewTriggers="))
    }
}
