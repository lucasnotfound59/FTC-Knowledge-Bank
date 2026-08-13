package org.ftckb.domain

data class RuleViolation(val ruleId:String,val field:String,val message:String)

object RuleIdentity {
    private val topicPattern=Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val teamPattern=Regex("^[0-9]+$")
    private val seasonPattern=Regex("^[0-9]{4}-[0-9]{4}$")

    fun isCanonicalTopic(value:String)=topicPattern.matches(value)
    fun isCanonicalTeam(value:String)=teamPattern.matches(value)
    fun isCanonicalSeason(value:String)=seasonPattern.matches(value)
}

object RuleValidator {
    private val idPattern=Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)*$")
    private val commitPattern=Regex("^[0-9a-fA-F]{7,64}$")
    private val windowsDrivePattern=Regex("^[A-Za-z]:")

    fun validate(rule:KnowledgeRule):List<RuleViolation> = buildList {
        fun reject(field:String,message:String)=add(RuleViolation(rule.id,field,message))
        if (!idPattern.matches(rule.id)) reject("id","invalid rule id")
        if (!RuleIdentity.isCanonicalTopic(rule.topic)) reject("topic","topic must be a canonical slug")
        if (rule.title.isBlank()) reject("title","title must not be blank")
        if (rule.instruction.isBlank()) reject("instruction","instruction must not be blank")
        if (rule.rationale.isBlank()) reject("rationale","rationale must not be blank")
        if (rule.evidence.isEmpty()) reject("evidence","rule requires evidence")
        if (rule.status==RuleStatus.APPROVED && rule.approval==null) reject("approval","approved rule requires approval")
        if (rule.status!=RuleStatus.APPROVED && rule.approval!=null) reject("approval","only approved rule may contain approval")
        if (rule.approval?.approver?.isBlank()==true) reject("approval.approver","approver must not be blank")
        rule.applicability.teams.filterNot(RuleIdentity::isCanonicalTeam).sorted().forEach {
            reject("applicability.teams","applicable team must contain digits only")
        }
        rule.applicability.seasons.filterNot(RuleIdentity::isCanonicalSeason).sorted().forEach {
            reject("applicability.seasons","season must use YYYY-YYYY")
        }
        if (rule.approval?.team?.let(RuleIdentity::isCanonicalTeam)==false) {
            reject("approval.team","approval team must contain digits only")
        }
        if (rule.authority==RuleAuthority.TEAM && rule.applicability.teams.isEmpty()) reject("applicability.teams","team rule requires an applicable team")
        if (rule.status==RuleStatus.APPROVED && rule.approval!=null) {
            val approval=rule.approval
            val approver=Approver(approval.approver,approval.role,approval.team)
            if (!ApprovalPolicy.authorize(rule.authority,rule.applicability.teams,approver)) {
                reject("approval","approval is not authorized for rule authority and teams")
            }
        }
        rule.evidence.forEachIndexed { index,evidence ->
            if (evidence.repository.isBlank()) reject("evidence[$index].repository","repository must not be blank")
            if (!commitPattern.matches(evidence.commit)) reject("evidence[$index].commit","commit must be a Git SHA")
            if (!isSafeEvidencePath(evidence.file)) {
                reject("evidence[$index].file","file must be a safe repository relative path using / separators")
            }
            if (evidence.symbol.isNullOrBlank() && evidence.line==null) reject("evidence[$index]","evidence requires a symbol or line")
            if (evidence.line!=null && evidence.line<1) reject("evidence[$index].line","line must be positive")
        }
    }

    private fun isSafeEvidencePath(file:String):Boolean {
        if (file.isBlank() || '\u0000' in file || '\\' in file) return false
        if (file.startsWith('/') || windowsDrivePattern.containsMatchIn(file)) return false
        if (file.endsWith('/') || "//" in file) return false
        return file.split('/').none { it=="." || it==".." }
    }
}
