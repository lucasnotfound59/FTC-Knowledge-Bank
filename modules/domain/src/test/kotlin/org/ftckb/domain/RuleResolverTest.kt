package org.ftckb.domain

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RuleResolverTest {
    private val evidence=GitRuleEvidence("repo","abcdef1","TeamCode/build.gradle",line=1)
    private val overall=Approval("overall",ApproverRole.OVERALL_SOFTWARE_LEAD,approvedAt=Instant.EPOCH)
    private val team=Approval("lead-20827",ApproverRole.TEAM_SOFTWARE_LEAD,"20827",Instant.EPOCH)

    private fun rule(id:String,topic:String,authority:RuleAuthority,teams:Set<String> = emptySet(),approval:Approval=overall)=KnowledgeRule(
        id=id,topic=topic,title=id,instruction=id,rationale="test",status=RuleStatus.APPROVED,
        authority=authority,applicability=RuleApplicability(teams=teams,seasons=setOf("2025-2026")),
        evidence=listOf(evidence),approval=approval
    )

    @Test fun `global beats local and keeps unrelated topics`() {
        val global=rule("shared.global","naming",RuleAuthority.SHARED).copy(policyLevel=PolicyLevel.GLOBAL)
        val local=rule("team.local","naming",RuleAuthority.TEAM,setOf("20827"),team)
        val other=rule("shared.other","telemetry",RuleAuthority.SHARED)
        val result=RuleResolver.resolve(listOf(local,other,global),RuleContext("20827","2025-2026",emptySet()))
        assertEquals(listOf("shared.global","shared.other"),result.activeRules.map { it.id })
        assertEquals(listOf("team.local"),result.overriddenRules.map { it.ruleId })
    }

    @Test fun `official beats global`() {
        val official=rule("official.naming","naming",RuleAuthority.OFFICIAL)
        val global=rule("shared.global","naming",RuleAuthority.SHARED).copy(policyLevel=PolicyLevel.GLOBAL)

        val result=RuleResolver.resolve(listOf(global,official),RuleContext("20827","2025-2026",emptySet()))

        assertEquals(listOf("official.naming"),result.activeRules.map { it.id })
        assertEquals(listOf("shared.global"),result.overriddenRules.map { it.ruleId })
        assertEquals(EffectivePolicyLevel.OFFICIAL,result.overriddenRules.single().effectiveLevel)
    }

    @Test fun `same effective level conflicts across authorities`() {
        val shared=rule("shared.local","naming",RuleAuthority.SHARED).copy(
            policyLevel=PolicyLevel.LOCAL,
            applicability=RuleApplicability(teams=setOf("20827"))
        )
        val teamRule=rule("team.local","naming",RuleAuthority.TEAM,setOf("20827"),team)

        val result=RuleResolver.resolve(listOf(teamRule,shared),RuleContext("20827","2025-2026",emptySet()))

        assertTrue(result.activeRules.isEmpty())
        assertEquals(listOf(setOf("shared.local","team.local")),result.conflicts.map { it.ruleIds })
        assertEquals(EffectivePolicyLevel.LOCAL,result.conflicts.single().effectiveLevel)
        assertEquals(mapOf("shared.local" to RuleAuthority.SHARED,"team.local" to RuleAuthority.TEAM),result.conflicts.single().authorities)
    }

    @Test fun `candidate global is excluded`() {
        val candidate=rule("shared.candidate","naming",RuleAuthority.SHARED).copy(
            status=RuleStatus.CANDIDATE,
            approval=null,
            policyLevel=PolicyLevel.GLOBAL
        )

        val result=RuleResolver.resolve(listOf(candidate),RuleContext("20827","2025-2026",emptySet()))

        assertTrue(result.activeRules.isEmpty())
        assertEquals(listOf(ExcludedRule("shared.candidate",listOf("status"))),result.excludedRules)
    }

    @Test fun `season and command profile applicability exclude and include rules`() {
        val command=rule("shared.command","architecture",RuleAuthority.SHARED).copy(
            applicability=RuleApplicability(seasons=setOf("2025-2026"),profiles=setOf("command-based"))
        )

        val matching=RuleResolver.resolve(listOf(command),RuleContext("20827","2025-2026",setOf("ftclib-command")))
        assertEquals(listOf("shared.command"),matching.activeRules.map { it.id })
        assertEquals(setOf("command-based","ftclib-command"),matching.profiles)

        val excluded=RuleResolver.resolve(listOf(command),RuleContext("20827","2026-2027",emptySet()))
        assertEquals(listOf(ExcludedRule("shared.command",listOf("profile","season"))),excluded.excludedRules)
    }

    @Test fun `missing and invalid profile contexts fail closed`() {
        val missing=assertThrows(RuleContextException::class.java) {
            RuleResolver.resolve(emptyList(),RuleContext("20827","2025-2026"))
        }
        assertEquals("context-required",missing.code)
        val invalid=assertThrows(RuleContextException::class.java) {
            RuleResolver.resolve(emptyList(),RuleContext("20827","2025-2026",setOf("unknown")))
        }
        assertEquals("invalid-context",invalid.code)
    }

    @Test fun `resolution is deterministic across shuffled input`() {
        val rules=listOf(
            rule("shared.global","naming",RuleAuthority.SHARED).copy(policyLevel=PolicyLevel.GLOBAL),
            rule("team.local","naming",RuleAuthority.TEAM,setOf("20827"),team),
            rule("shared.other","telemetry",RuleAuthority.SHARED),
            rule("shared.candidate","telemetry",RuleAuthority.SHARED).copy(status=RuleStatus.CANDIDATE,approval=null)
        )
        val context=RuleContext("20827","2025-2026",emptySet())
        val expected=RuleResolver.resolve(rules,context)

        repeat(100) { seed->
            assertEquals(expected,RuleResolver.resolve(rules.shuffled(Random(seed)),context))
        }
    }

    @Test
    fun `team rule overrides shared rule for matching team`() {
        val result=RuleResolver.resolve(
            listOf(
                rule("shared.pathing","pathing",RuleAuthority.SHARED),
                rule("team.pathing","pathing",RuleAuthority.TEAM,setOf("20827"),team)
            ),
            RuleContext("20827","2025-2026",emptySet())
        )

        assertEquals(listOf("team.pathing"),result.activeRules.map { it.id })
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `official rule cannot be overridden`() {
        val result=RuleResolver.resolve(
            listOf(
                rule("official.safe","deployment-safety",RuleAuthority.OFFICIAL),
                rule("team.unsafe","deployment-safety",RuleAuthority.TEAM,setOf("20827"),team)
            ),
            RuleContext("20827","2025-2026",emptySet())
        )

        assertEquals(listOf("official.safe"),result.activeRules.map { it.id })
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `official rule cannot be overridden or conflicted by shared rule`() {
        val result=RuleResolver.resolve(
            listOf(
                rule("official.safe","deployment-safety",RuleAuthority.OFFICIAL),
                rule("shared.unsafe","deployment-safety",RuleAuthority.SHARED)
            ),
            RuleContext("20827","2025-2026",emptySet())
        )

        assertEquals(listOf("official.safe"),result.activeRules.map { it.id })
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `same authority same topic blocks resolution`() {
        val result=RuleResolver.resolve(
            listOf(
                rule("shared.one","naming",RuleAuthority.SHARED),
                rule("shared.two","naming",RuleAuthority.SHARED)
            ),
            RuleContext("20827","2025-2026",emptySet())
        )

        assertTrue(result.activeRules.isEmpty())
        assertEquals(listOf(setOf("shared.one","shared.two")),result.conflicts.map { it.ruleIds })
    }

    @Test
    fun `candidate is never active`() {
        val candidate=rule("shared.candidate","naming",RuleAuthority.SHARED).copy(status=RuleStatus.CANDIDATE,approval=null)
        assertTrue(RuleResolver.resolve(listOf(candidate),RuleContext("20827","2025-2026",emptySet())).activeRules.isEmpty())
    }

    @Test
    fun `deprecated and rejected rules are never active`() {
        val deprecated=rule("shared.deprecated","naming",RuleAuthority.SHARED).copy(status=RuleStatus.DEPRECATED,approval=null)
        val rejected=rule("shared.rejected","naming",RuleAuthority.SHARED).copy(status=RuleStatus.REJECTED,approval=null)

        assertTrue(RuleResolver.resolve(listOf(deprecated,rejected),RuleContext("20827","2025-2026",emptySet())).activeRules.isEmpty())
    }

    @Test
    fun `direct resolution rejects an approved rule without approval metadata`() {
        val unapproved=rule("shared.unapproved","naming",RuleAuthority.SHARED).copy(approval=null)

        val exception=assertThrows(IllegalArgumentException::class.java) {
            RuleResolver.resolve(listOf(unapproved),RuleContext("20827","2025-2026",emptySet()))
        }

        assertEquals(
            "invalid rule set: rule=shared.unapproved field=approval message=approved rule requires approval",
            exception.message
        )
    }

    @Test
    fun `direct resolution rejects an approval without authority`() {
        val unauthorized=rule(
            "team.unauthorized",
            "naming",
            RuleAuthority.TEAM,
            setOf("20827"),
            overall
        )

        assertThrows(IllegalArgumentException::class.java) {
            RuleResolver.resolve(listOf(unauthorized),RuleContext("20827","2025-2026",emptySet()))
        }
    }

    @Test
    fun `direct resolution rejects noncanonical team context`() {
        listOf(" \t","team-20827").forEach { team ->
            val exception=assertThrows(IllegalArgumentException::class.java) {
                RuleResolver.resolve(emptyList(),RuleContext(team,"2025-2026",emptySet()))
            }
            assertEquals("invalid rule context: team must contain digits only",exception.message)
        }
    }

    @Test
    fun `direct resolution rejects noncanonical season context but permits null context values`() {
        listOf(" \t","2025-26").forEach { season ->
            val exception=assertThrows(IllegalArgumentException::class.java) {
                RuleResolver.resolve(emptyList(),RuleContext("20827",season,emptySet()))
            }
            assertEquals("invalid rule context: season must use YYYY-YYYY",exception.message)
        }

        val result=RuleResolver.resolve(emptyList(),RuleContext(null,null,emptySet()))
        assertTrue(result.activeRules.isEmpty())
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `applicability snapshots mutable teams at construction and copy`() {
        val constructorTeams=linkedSetOf("20827")
        val constructed=RuleApplicability(teams=constructorTeams,seasons=setOf("2025-2026"))
        val copyTeams=linkedSetOf("20827")
        val copied=constructed.copy(teams=copyTeams)
        val approved=rule("team.snapshot","naming",RuleAuthority.TEAM,setOf("20827"),team).copy(
            applicability=copied
        )
        assertTrue(RuleValidator.validate(approved).isEmpty())

        constructorTeams.clear()
        copyTeams.clear()
        copyTeams.add("16093")

        assertEquals(setOf("20827"),constructed.teams)
        assertEquals(setOf("20827"),copied.teams)
        assertEquals(
            listOf("team.snapshot"),
            RuleResolver.resolve(listOf(approved),RuleContext("20827","2025-2026",emptySet())).activeRules.map { it.id }
        )
    }

    @Test
    fun `applicability snapshots mutable profiles at construction and copy`() {
        val constructorProfiles=linkedSetOf("rookiebot")
        val constructed=RuleApplicability(profiles=constructorProfiles)
        val copyProfiles=linkedSetOf("ftclib-command")
        val copied=constructed.copy(profiles=copyProfiles)

        constructorProfiles.clear()
        copyProfiles.clear()

        assertEquals(setOf("rookiebot"),constructed.profiles)
        assertEquals(setOf("ftclib-command"),copied.profiles)
        assertTrue(constructed.toString().contains("profiles=[rookiebot]"))
    }
}
