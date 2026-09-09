package org.ftckb.domain

enum class PolicyLevel { GLOBAL,LOCAL,SHARED }
enum class EffectivePolicyLevel(val rank:Int) { SHARED(1),LOCAL(2),GLOBAL(3),OFFICIAL(4) }

object RulePolicy {
    fun legacy(authority:RuleAuthority):PolicyLevel=when (authority) {
        RuleAuthority.OFFICIAL -> PolicyLevel.GLOBAL
        RuleAuthority.TEAM -> PolicyLevel.LOCAL
        RuleAuthority.SHARED -> PolicyLevel.SHARED
    }

    fun level(rule:KnowledgeRule):EffectivePolicyLevel=when {
        rule.authority==RuleAuthority.OFFICIAL -> EffectivePolicyLevel.OFFICIAL
        rule.policyLevel==PolicyLevel.GLOBAL -> EffectivePolicyLevel.GLOBAL
        rule.policyLevel==PolicyLevel.LOCAL -> EffectivePolicyLevel.LOCAL
        else -> EffectivePolicyLevel.SHARED
    }
}
