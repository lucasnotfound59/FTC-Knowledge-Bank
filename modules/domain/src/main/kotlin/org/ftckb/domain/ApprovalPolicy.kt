package org.ftckb.domain

data class Approver(val id:String,val role:ApproverRole,val team:String?=null)

object ApprovalPolicy {
    fun authorize(authority:RuleAuthority,teams:Set<String>,approver:Approver):Boolean = when (authority) {
        RuleAuthority.OFFICIAL,RuleAuthority.SHARED -> approver.role==ApproverRole.OVERALL_SOFTWARE_LEAD
        RuleAuthority.TEAM -> approver.role==ApproverRole.TEAM_SOFTWARE_LEAD && approver.team!=null && teams==setOf(approver.team)
    }
}
