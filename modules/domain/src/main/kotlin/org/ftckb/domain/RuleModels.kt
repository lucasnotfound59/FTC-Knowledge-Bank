package org.ftckb.domain

import java.time.Instant
import java.time.LocalDate
import java.util.Collections

enum class RuleStatus { CANDIDATE,APPROVED,DEPRECATED,REJECTED }
enum class RuleAuthority { OFFICIAL,SHARED,TEAM }
enum class ApproverRole { OVERALL_SOFTWARE_LEAD,TEAM_SOFTWARE_LEAD }
enum class RuleCheckKind { PATH_FORBIDDEN,PATH_REQUIRED,REGEX_REQUIRED,REGEX_FORBIDDEN }

/** A machine-enforceable check attached to a rule (ftckb check).
 *  pattern is a glob for PATH_* kinds and a regex for REGEX_* kinds;
 *  appliesTo is an optional path glob limiting REGEX_* checks. */
class RuleCheck(
    val kind:RuleCheckKind,
    val pattern:String,
    val appliesTo:String?,
    val note:String
) {
    override fun equals(other:Any?):Boolean=this===other || other is RuleCheck &&
        kind==other.kind && pattern==other.pattern && appliesTo==other.appliesTo && note==other.note

    override fun hashCode():Int {
        var result=kind.hashCode()
        result=31*result+pattern.hashCode()
        result=31*result+(appliesTo?.hashCode() ?: 0)
        result=31*result+note.hashCode()
        return result
    }

    override fun toString()="RuleCheck(kind=$kind, pattern=$pattern, appliesTo=$appliesTo, note=$note)"
}

sealed interface RuleEvidence

data class GitRuleEvidence(
    val repository:String,
    val commit:String,
    val file:String,
    val symbol:String?=null,
    val line:Int?=null
):RuleEvidence

data class WebRuleEvidence(
    val url:String,
    val title:String,
    val publisher:String,
    val accessedAt:LocalDate,
    val section:String,
    val version:String?=null,
    val product:String?=null,
    val sku:String?=null
):RuleEvidence

class RuleApplicability(
    teams:Set<String> =emptySet(),
    seasons:Set<String> =emptySet(),
    profiles:Set<String> =emptySet()
) {
    val teams:Set<String> =immutableSetSnapshot(teams)
    val seasons:Set<String> =immutableSetSnapshot(seasons)
    val profiles:Set<String> =immutableSetSnapshot(profiles)

    fun copy(
        teams:Set<String> =this.teams,
        seasons:Set<String> =this.seasons,
        profiles:Set<String> =this.profiles
    )=RuleApplicability(teams,seasons,profiles)

    override fun equals(other:Any?):Boolean=
        this===other || other is RuleApplicability && teams==other.teams && seasons==other.seasons && profiles==other.profiles

    override fun hashCode():Int=31*(31*teams.hashCode()+seasons.hashCode())+profiles.hashCode()

    override fun toString()="RuleApplicability(teams=$teams, seasons=$seasons, profiles=$profiles)"
}

class RuleReviewTrigger(paths:List<String>,addedLinePatterns:List<String>) {
    val paths:List<String> =immutableListSnapshot(paths)
    val addedLinePatterns:List<String> =immutableListSnapshot(addedLinePatterns)

    override fun equals(other:Any?):Boolean=this===other || other is RuleReviewTrigger &&
        paths==other.paths && addedLinePatterns==other.addedLinePatterns

    override fun hashCode():Int=31*paths.hashCode()+addedLinePatterns.hashCode()

    override fun toString():String="RuleReviewTrigger(paths=$paths, addedLinePatterns=$addedLinePatterns)"
}

data class Approval(
    val approver:String,
    val role:ApproverRole,
    val team:String?=null,
    val approvedAt:Instant
)

class KnowledgeRule(
    val id:String,
    val topic:String,
    val title:String,
    val instruction:String,
    val rationale:String,
    val status:RuleStatus,
    val authority:RuleAuthority,
    val applicability:RuleApplicability,
    evidence:List<RuleEvidence>,
    val approval:Approval?=null,
    val supersedes:String?=null,
    val positiveExample:String?=null,
    val negativeExample:String?=null,
    checks:List<RuleCheck> =emptyList(),
    val policyLevel:PolicyLevel=RulePolicy.legacy(authority),
    reviewTriggers:List<RuleReviewTrigger> =emptyList()
) {
    val evidence:List<RuleEvidence> =immutableListSnapshot(evidence)
    val checks:List<RuleCheck> =immutableListSnapshot(checks)
    val reviewTriggers:List<RuleReviewTrigger> =immutableListSnapshot(reviewTriggers)

    fun copy(
        id:String=this.id,
        topic:String=this.topic,
        title:String=this.title,
        instruction:String=this.instruction,
        rationale:String=this.rationale,
        status:RuleStatus=this.status,
        authority:RuleAuthority=this.authority,
        applicability:RuleApplicability=this.applicability,
        evidence:List<RuleEvidence> =this.evidence,
        approval:Approval?=this.approval,
        supersedes:String?=this.supersedes,
        positiveExample:String?=this.positiveExample,
        negativeExample:String?=this.negativeExample,
        checks:List<RuleCheck> =this.checks,
        policyLevel:PolicyLevel=this.policyLevel,
        reviewTriggers:List<RuleReviewTrigger> =this.reviewTriggers
    )=KnowledgeRule(
        id,topic,title,instruction,rationale,status,authority,applicability,evidence,approval,
        supersedes,positiveExample,negativeExample,checks,policyLevel,reviewTriggers
    )

    override fun equals(other:Any?):Boolean=this===other || other is KnowledgeRule &&
        id==other.id &&
        topic==other.topic &&
        title==other.title &&
        instruction==other.instruction &&
        rationale==other.rationale &&
        status==other.status &&
        authority==other.authority &&
        applicability==other.applicability &&
        evidence==other.evidence &&
        approval==other.approval &&
        supersedes==other.supersedes &&
        positiveExample==other.positiveExample &&
        negativeExample==other.negativeExample &&
        checks==other.checks &&
        policyLevel==other.policyLevel &&
        reviewTriggers==other.reviewTriggers

    override fun hashCode():Int {
        var result=id.hashCode()
        result=31*result+topic.hashCode()
        result=31*result+title.hashCode()
        result=31*result+instruction.hashCode()
        result=31*result+rationale.hashCode()
        result=31*result+status.hashCode()
        result=31*result+authority.hashCode()
        result=31*result+applicability.hashCode()
        result=31*result+evidence.hashCode()
        result=31*result+(approval?.hashCode() ?: 0)
        result=31*result+(supersedes?.hashCode() ?: 0)
        result=31*result+(positiveExample?.hashCode() ?: 0)
        result=31*result+(negativeExample?.hashCode() ?: 0)
        result=31*result+checks.hashCode()
        result=31*result+policyLevel.hashCode()
        result=31*result+reviewTriggers.hashCode()
        return result
    }

    override fun toString()="KnowledgeRule(id=$id, topic=$topic, title=$title, instruction=$instruction, "+
        "rationale=$rationale, status=$status, authority=$authority, applicability=$applicability, "+
        "evidence=$evidence, approval=$approval, supersedes=$supersedes, positiveExample=$positiveExample, "+
        "negativeExample=$negativeExample, checks=$checks, policyLevel=$policyLevel, reviewTriggers=$reviewTriggers)"
}

private fun <T> immutableSetSnapshot(values:Set<T>):Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <T> immutableListSnapshot(values:List<T>):List<T> =
    Collections.unmodifiableList(ArrayList(values))
