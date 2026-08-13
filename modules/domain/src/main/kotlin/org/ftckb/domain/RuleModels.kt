package org.ftckb.domain

import java.time.Instant

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

data class RuleApplicability(
    val teams:Set<String> = emptySet(),
    val seasons:Set<String> = emptySet()
)

data class Approval(
    val approver:String,
    val role:ApproverRole,
    val team:String?=null,
    val approvedAt:Instant
)

data class KnowledgeRule(
    val id:String,
    val topic:String,
    val title:String,
    val instruction:String,
    val rationale:String,
    val status:RuleStatus,
    val authority:RuleAuthority,
    val applicability:RuleApplicability,
    val evidence:List<RuleEvidence>,
    val approval:Approval?=null,
    val supersedes:String?=null,
    val positiveExample:String?=null,
    val negativeExample:String?=null
)
