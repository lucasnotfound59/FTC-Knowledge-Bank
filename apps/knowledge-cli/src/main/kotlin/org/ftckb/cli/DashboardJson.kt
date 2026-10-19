package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import org.ftckb.audit.*
import org.ftckb.domain.*
import org.ftckb.knowledge.*

/** Dashboard wire contract is independent of the stable kernel JSON contract. */
internal object DashboardJson {
    val mapper=JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    fun rule(r:KnowledgeRule):Map<String,Any?> = linkedMapOf(
        "id" to r.id,"topic" to r.topic,"title" to r.title,"instruction" to r.instruction,
        "rationale" to r.rationale,"status" to r.status.name.lowercase(java.util.Locale.ROOT),
        "authority" to r.authority.name.lowercase(java.util.Locale.ROOT),"policyLevel" to r.policyLevel.name.lowercase(java.util.Locale.ROOT),
        "applicability" to mapOf("teams" to r.applicability.teams.sorted(),"seasons" to r.applicability.seasons.sorted(),"profiles" to r.applicability.profiles.sorted()),
        "evidence" to r.evidence.map { e -> when(e) {
            is GitRuleEvidence -> mapOf("type" to "git","repository" to e.repository,"commit" to e.commit,"file" to e.file,"symbol" to e.symbol,"line" to e.line)
            is WebRuleEvidence -> mapOf("type" to "web","url" to e.url,"title" to e.title,"publisher" to e.publisher,"accessedAt" to e.accessedAt.toString(),"section" to e.section,"version" to e.version,"product" to e.product,"sku" to e.sku)
        } },
        "approval" to r.approval?.let { mapOf("approver" to it.approver,"role" to it.role.name.lowercase(java.util.Locale.ROOT),"team" to it.team,"approvedAt" to it.approvedAt.toString()) },
        "supersedes" to r.supersedes,"positiveExample" to r.positiveExample,"negativeExample" to r.negativeExample,
        "checks" to r.checks.map { mapOf("kind" to it.kind.name.lowercase(java.util.Locale.ROOT),"pattern" to it.pattern,"appliesTo" to it.appliesTo,"note" to it.note) },
        "reviewTriggers" to r.reviewTriggers.map { mapOf("paths" to it.paths.sorted(),"addedLinePatterns" to it.addedLinePatterns.sorted()) },
        "checkMode" to when { r.checks.isNotEmpty() -> "hard"; r.reviewTriggers.isNotEmpty() -> "conditional_soft"; else -> "unconditional_soft" }
    )
    fun resolution(r:ResolutionResult):Map<String,Any?> = mapOf(
        "activeRules" to r.activeRules.sortedBy { it.id }.map(::rule),"profiles" to r.profiles.sorted(),
        "excludedRules" to r.excludedRules.map { mapOf("ruleId" to it.ruleId,"reasons" to it.reasons.sorted()) },
        "overriddenRules" to r.overriddenRules.map { mapOf("ruleId" to it.ruleId,"topic" to it.topic,"winnerIds" to it.winnerIds.sorted(),"effectiveLevel" to it.effectiveLevel.name.lowercase(java.util.Locale.ROOT)) },
        "conflicts" to r.conflicts.map { c -> mapOf("topic" to c.topic,"effectiveLevel" to c.effectiveLevel.name.lowercase(java.util.Locale.ROOT),"ruleIds" to c.ruleIds.sorted(),"authorities" to c.authorities.toSortedMap().mapValues { it.value.name.lowercase(java.util.Locale.ROOT) }) }
    )
    fun preview(p:RuleMutationPreview):Map<String,Any?> = mapOf(
        "previewId" to p.previewId,"action" to p.action.name.lowercase(java.util.Locale.ROOT),"ruleId" to p.ruleId,
        "baseVersion" to p.baseVersion,"targetPath" to p.targetPath,"beforeYaml" to p.beforeYaml,
        "afterYaml" to p.afterYaml,"violations" to p.violations,"loadErrors" to p.loadErrors,
        "resolution" to p.resolution?.let(::resolution),"approvalReset" to p.approvalReset,"canApply" to p.canApply
    )
    fun summary(s:AuditSummary):Map<String,Any?> = mapOf(
        "revision" to s.revision,"total" to s.total,"unfinished" to s.unfinished,
        "diffChecked" to s.diffChecked,"diffBlocked" to s.diffBlocked,"diffNotRun" to s.diffNotRun,
        "diffNotApplicable" to s.diffNotApplicable,"health" to s.health
    )
    fun record(r:AuditRecord):Map<String,Any?> = mapOf(
        "invocationId" to r.invocationId,"startedAt" to r.startedAt,"endedAt" to r.endedAt,
        "metadata" to r.metadata,"knowledgeVersion" to r.knowledgeVersion,"ruleSnapshots" to r.ruleSnapshots,
        "stages" to r.stages.map { mapOf("name" to it.name,"status" to it.status.name.lowercase(java.util.Locale.ROOT),"category" to it.category,"detail" to it.detail,"hits" to it.hits) },
        "status" to r.status.name.lowercase(java.util.Locale.ROOT),"exitCode" to r.exitCode,"durationMillis" to r.durationMillis,
        "inputTokens" to r.inputTokens,"outputTokens" to r.outputTokens
    )
    fun context(n:JsonNode):RuleContext {
        fields(n,setOf("team","season","profiles","workMode"))
        fun required(key:String):String=n.get(key)?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            ?: throw DashboardProblem(422,"invalid_context","Missing or invalid context $key")
        val team=required("team"); val season=required("season")
        if (!RuleIdentity.isCanonicalTeam(team) || !RuleIdentity.isCanonicalSeason(season))
            throw DashboardProblem(422,"invalid_context","Context requires canonical team and season")
        val p=n.get("profiles") ?: throw DashboardProblem(422,"invalid_context","Select profiles explicitly; [] selects generic")
        if (!p.isArray || p.any { !it.isTextual }) throw DashboardProblem(422,"invalid_context","profiles must be an array of strings")
        val profiles=try { RuleProfiles.normalize(p.map { it.asText() }.toSet()) }
            catch (_:IllegalArgumentException) { throw DashboardProblem(422,"invalid_context","Unsupported or incompatible profiles") }
        val mode=if (n.has("workMode")) WorkMode.parse(required("workMode"))
            ?: throw DashboardProblem(422,"invalid_context","Unknown work mode") else WorkMode.NORMAL
        return RuleContext(team,season,profiles,mode)
    }
    fun fields(n:JsonNode,allowed:Set<String>) {
        if (!n.isObject || n.fieldNames().asSequence().any { it !in allowed })
            throw DashboardProblem(400,"malformed_request","Expected an object with only supported fields")
    }
    fun text(n:JsonNode,key:String):String {
        val v=n.get(key)
        if (v==null || !v.isTextual || v.asText().isBlank()) throw DashboardProblem(400,"malformed_request","Missing or invalid $key")
        return v.asText()
    }
}
internal class DashboardProblem(val status:Int,val code:String,override val message:String):RuntimeException(message)
