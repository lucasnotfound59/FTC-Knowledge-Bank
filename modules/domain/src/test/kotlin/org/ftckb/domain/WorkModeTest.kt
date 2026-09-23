package org.ftckb.domain

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class WorkModeTest {
    private val evidence=GitRuleEvidence("repo","abcdef1","TeamCode/build.gradle",line=1)
    private val overall=Approval("overall",ApproverRole.OVERALL_SOFTWARE_LEAD,approvedAt=Instant.EPOCH)
    private val architectureIds=setOf("global.command-responsibilities","shared.ftclib-command-candidate")

    private fun rule(
        id:String,
        topic:String,
        authority:RuleAuthority,
        level:PolicyLevel=PolicyLevel.SHARED,
        profiles:Set<String> =emptySet()
    )=KnowledgeRule(
        id=id,topic=topic,title=id,instruction=id,rationale="test",status=RuleStatus.APPROVED,
        authority=authority,policyLevel=level,
        applicability=RuleApplicability(seasons=setOf("2025-2026"),profiles=profiles),
        evidence=listOf(evidence),approval=overall
    )

    private val rules=listOf(
        rule("global.command-responsibilities","command-responsibilities",RuleAuthority.SHARED,
            PolicyLevel.GLOBAL,setOf("command-based")),
        rule("shared.ftclib-command-candidate","command-framework",RuleAuthority.SHARED,
            PolicyLevel.SHARED,setOf("ftclib-command")),
        rule("global.command-live-input","command-live-input",RuleAuthority.SHARED,
            PolicyLevel.GLOBAL,setOf("command-based")),
        rule("global.command-requirements-cleanup","command-requirements-cleanup",RuleAuthority.SHARED,
            PolicyLevel.GLOBAL,setOf("command-based")),
        rule("global.test-utility-layout","test-utility-layout",RuleAuthority.SHARED,PolicyLevel.GLOBAL),
        rule("shared.unrelated","unrelated-topic",RuleAuthority.SHARED)
    )

    @Test
    fun `work mode parses only the three explicit identifiers`() {
        assertEquals(WorkMode.NORMAL,WorkMode.parse("normal"))
        assertEquals(WorkMode.TEST,WorkMode.parse("test"))
        assertEquals(WorkMode.DEV,WorkMode.parse("dev"))
        listOf("","NORMAL","Test","fast","normal "," test").forEach { value ->
            assertNull(WorkMode.parse(value),"value=$value")
        }
        assertEquals(listOf("normal","test","dev"),WorkMode.entries.map { it.id })
    }

    @Test
    fun `rule context defaults to normal work mode`() {
        assertEquals(WorkMode.NORMAL,RuleContext("20827","2025-2026",emptySet()).workMode)
        assertEquals(WorkMode.TEST,RuleContext("20827","2025-2026",emptySet(),WorkMode.TEST).workMode)
    }

    @Test
    fun `test mode excludes only the two architecture mandate rules`() {
        val normal=RuleResolver.resolve(rules,RuleContext("20827","2025-2026",setOf("ftclib-command")))
        val test=RuleResolver.resolve(
            rules,RuleContext("20827","2025-2026",setOf("ftclib-command"),WorkMode.TEST)
        )

        assertEquals(architectureIds,normal.activeRules.map { it.id }.toSet()-test.activeRules.map { it.id }.toSet())
        assertTrue(test.activeRules.map { it.id }.toSet()==normal.activeRules.map { it.id }.toSet()-architectureIds)
        assertEquals(
            architectureIds.sorted().map { ExcludedRule(it,listOf("work-mode-test")) },
            test.excludedRules.filter { it.ruleId in architectureIds }
        )
        listOf("global.command-live-input","global.command-requirements-cleanup","global.test-utility-layout")
            .forEach { id -> assertTrue(test.activeRules.any { it.id==id },id) }
        assertTrue(normal.excludedRules.none { it.ruleId in architectureIds })
    }

    @Test
    fun `test mode keeps every existing exclusion reason alongside the work mode reason`() {
        val test=RuleResolver.resolve(rules,RuleContext("20827","2025-2026",emptySet(),WorkMode.TEST))

        assertEquals(
            listOf(ExcludedRule("global.command-responsibilities",listOf("profile","work-mode-test"))),
            test.excludedRules.filter { it.ruleId=="global.command-responsibilities" }
        )
        assertEquals(
            listOf(ExcludedRule("shared.ftclib-command-candidate",listOf("profile","work-mode-test"))),
            test.excludedRules.filter { it.ruleId=="shared.ftclib-command-candidate" }
        )
    }

    @Test
    fun `dev mode leaves resolution unchanged`() {
        val context=RuleContext("20827","2025-2026",setOf("ftclib-command"))
        val normal=RuleResolver.resolve(rules,context)

        assertEquals(normal,RuleResolver.resolve(rules,RuleContext("20827","2025-2026",setOf("ftclib-command"),WorkMode.DEV)))
        assertEquals(normal.excludedRules,RuleResolver.resolve(
            rules,RuleContext("20827","2025-2026",setOf("ftclib-command"),WorkMode.DEV)
        ).excludedRules)
    }

    @Test
    fun `test and dev resolution stays deterministic across shuffled input`() {
        listOf(WorkMode.TEST,WorkMode.DEV).forEach { mode ->
            val context=RuleContext("20827","2025-2026",setOf("ftclib-command"),mode)
            val expected=RuleResolver.resolve(rules,context)
            repeat(100) { seed ->
                assertEquals(expected,RuleResolver.resolve(rules.shuffled(Random(seed)),context),mode.id)
            }
        }
    }

    @Test
    fun `work mode does not bypass knowledge validation or context requirements`() {
        val unapproved=rule("shared.unapproved","unapproved",RuleAuthority.SHARED).copy(approval=null)
        listOf(WorkMode.TEST,WorkMode.DEV).forEach { mode ->
            try {
                RuleResolver.resolve(listOf(unapproved),RuleContext("20827","2025-2026",emptySet(),mode))
                error("expected invalid rule set for ${mode.id}")
            } catch (exception:IllegalArgumentException) {
                assertEquals(
                    "invalid rule set: rule=shared.unapproved field=approval message=approved rule requires approval",
                    exception.message
                )
            }
            try {
                RuleResolver.resolve(rules,RuleContext("20827","2025-2026",null,mode))
                error("expected context-required for ${mode.id}")
            } catch (exception:RuleContextException) {
                assertEquals("context-required",exception.code)
            }
        }
    }
}
