package org.ftckb.domain

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleResolverTest {
    private val evidence=RuleEvidence("repo","abcdef1","TeamCode/build.gradle",line=1)
    private val overall=Approval("overall",ApproverRole.OVERALL_SOFTWARE_LEAD,approvedAt=Instant.EPOCH)
    private val team=Approval("lead-20827",ApproverRole.TEAM_SOFTWARE_LEAD,"20827",Instant.EPOCH)

    private fun rule(id:String,topic:String,authority:RuleAuthority,teams:Set<String> = emptySet(),approval:Approval=overall)=KnowledgeRule(
        id=id,topic=topic,title=id,instruction=id,rationale="test",status=RuleStatus.APPROVED,
        authority=authority,applicability=RuleApplicability(teams=teams,seasons=setOf("2025-2026")),
        evidence=listOf(evidence),approval=approval
    )

    @Test
    fun `team rule overrides shared rule for matching team`() {
        val result=RuleResolver.resolve(
            listOf(
                rule("shared.pathing","pathing",RuleAuthority.SHARED),
                rule("team.pathing","pathing",RuleAuthority.TEAM,setOf("20827"),team)
            ),
            RuleContext("20827","2025-2026")
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
            RuleContext("20827","2025-2026")
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
            RuleContext("20827","2025-2026")
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
            RuleContext("20827","2025-2026")
        )

        assertTrue(result.activeRules.isEmpty())
        assertEquals(listOf(setOf("shared.one","shared.two")),result.conflicts.map { it.ruleIds })
    }

    @Test
    fun `candidate is never active`() {
        val candidate=rule("shared.candidate","naming",RuleAuthority.SHARED).copy(status=RuleStatus.CANDIDATE,approval=null)
        assertTrue(RuleResolver.resolve(listOf(candidate),RuleContext("20827","2025-2026")).activeRules.isEmpty())
    }

    @Test
    fun `deprecated and rejected rules are never active`() {
        val deprecated=rule("shared.deprecated","naming",RuleAuthority.SHARED).copy(status=RuleStatus.DEPRECATED,approval=null)
        val rejected=rule("shared.rejected","naming",RuleAuthority.SHARED).copy(status=RuleStatus.REJECTED,approval=null)

        assertTrue(RuleResolver.resolve(listOf(deprecated,rejected),RuleContext("20827","2025-2026")).activeRules.isEmpty())
    }

    @Test
    fun `direct resolution rejects an approved rule without approval metadata`() {
        val unapproved=rule("shared.unapproved","naming",RuleAuthority.SHARED).copy(approval=null)

        val exception=assertThrows(IllegalArgumentException::class.java) {
            RuleResolver.resolve(listOf(unapproved),RuleContext("20827","2025-2026"))
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
            RuleResolver.resolve(listOf(unauthorized),RuleContext("20827","2025-2026"))
        }
    }

    @Test
    fun `direct resolution rejects noncanonical team context`() {
        listOf(" \t","team-20827").forEach { team ->
            val exception=assertThrows(IllegalArgumentException::class.java) {
                RuleResolver.resolve(emptyList(),RuleContext(team,"2025-2026"))
            }
            assertEquals("invalid rule context: team must contain digits only",exception.message)
        }
    }

    @Test
    fun `direct resolution rejects noncanonical season context but permits null context values`() {
        listOf(" \t","2025-26").forEach { season ->
            val exception=assertThrows(IllegalArgumentException::class.java) {
                RuleResolver.resolve(emptyList(),RuleContext("20827",season))
            }
            assertEquals("invalid rule context: season must use YYYY-YYYY",exception.message)
        }

        val result=RuleResolver.resolve(emptyList(),RuleContext(null,null))
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
            RuleResolver.resolve(listOf(approved),RuleContext("20827","2025-2026")).activeRules.map { it.id }
        )
    }
}
