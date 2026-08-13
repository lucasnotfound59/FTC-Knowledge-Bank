package org.ftckb.domain

data class RuleViolation(val ruleId:String,val field:String,val message:String)

object RuleValidator {
    private val idPattern=Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)*$")
    private val commitPattern=Regex("^[0-9a-fA-F]{7,64}$")

    fun validate(rule:KnowledgeRule):List<RuleViolation> = buildList {
        fun reject(field:String,message:String)=add(RuleViolation(rule.id,field,message))
        if (!idPattern.matches(rule.id)) reject("id","invalid rule id")
        if (rule.topic.isBlank()) reject("topic","topic must not be blank")
        if (rule.title.isBlank()) reject("title","title must not be blank")
        if (rule.instruction.isBlank()) reject("instruction","instruction must not be blank")
        if (rule.rationale.isBlank()) reject("rationale","rationale must not be blank")
        if (rule.evidence.isEmpty()) reject("evidence","rule requires evidence")
        if (rule.status==RuleStatus.APPROVED && rule.approval==null) reject("approval","approved rule requires approval")
        if (rule.status!=RuleStatus.APPROVED && rule.approval!=null) reject("approval","only approved rule may contain approval")
        if (rule.authority==RuleAuthority.TEAM && rule.applicability.teams.isEmpty()) reject("applicability.teams","team rule requires an applicable team")
        rule.evidence.forEachIndexed { index,evidence ->
            if (evidence.repository.isBlank()) reject("evidence[$index].repository","repository must not be blank")
            if (!commitPattern.matches(evidence.commit)) reject("evidence[$index].commit","commit must be a Git SHA")
            if (evidence.file.isBlank() || evidence.file.startsWith("/") || ".." in evidence.file.split('/')) reject("evidence[$index].file","file must be a safe relative path")
            if (evidence.symbol.isNullOrBlank() && evidence.line==null) reject("evidence[$index]","evidence requires a symbol or line")
            if (evidence.line!=null && evidence.line<1) reject("evidence[$index].line","line must be positive")
        }
    }
}
