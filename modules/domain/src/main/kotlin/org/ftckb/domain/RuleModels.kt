package org.ftckb.domain

import java.time.Instant
import java.util.Collections

enum class RuleStatus { CANDIDATE,APPROVED,DEPRECATED,REJECTED }
enum class RuleAuthority { OFFICIAL,SHARED,TEAM }
enum class ApproverRole { OVERALL_SOFTWARE_LEAD,TEAM_SOFTWARE_LEAD }

data class RuleEvidence(
    val repository:String,
    val commit:String,
    val file:String,
    val symbol:String?=null,
    val line:Int?=null
)

class RuleApplicability(
    teams:Set<String> =emptySet(),
    seasons:Set<String> =emptySet()
) {
    val teams:Set<String> =immutableSetSnapshot(teams)
    val seasons:Set<String> =immutableSetSnapshot(seasons)

    fun copy(
        teams:Set<String> =this.teams,
        seasons:Set<String> =this.seasons
    )=RuleApplicability(teams,seasons)

    override fun equals(other:Any?):Boolean=
        this===other || other is RuleApplicability && teams==other.teams && seasons==other.seasons

    override fun hashCode():Int=31*teams.hashCode()+seasons.hashCode()

    override fun toString()="RuleApplicability(teams=$teams, seasons=$seasons)"
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
    val negativeExample:String?=null
) {
    val evidence:List<RuleEvidence> =immutableListSnapshot(evidence)

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
        negativeExample:String?=this.negativeExample
    )=KnowledgeRule(
        id,topic,title,instruction,rationale,status,authority,applicability,evidence,approval,
        supersedes,positiveExample,negativeExample
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
        negativeExample==other.negativeExample

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
        return result
    }

    override fun toString()="KnowledgeRule(id=$id, topic=$topic, title=$title, instruction=$instruction, "+
        "rationale=$rationale, status=$status, authority=$authority, applicability=$applicability, "+
        "evidence=$evidence, approval=$approval, supersedes=$supersedes, positiveExample=$positiveExample, "+
        "negativeExample=$negativeExample)"
}

private fun <T> immutableSetSnapshot(values:Set<T>):Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <T> immutableListSnapshot(values:List<T>):List<T> =
    Collections.unmodifiableList(ArrayList(values))
