package org.ftckb.domain

import java.util.Collections
import java.util.LinkedHashSet

class RuleContext(val team:String?,val season:String?,profiles:Set<String>?=null) {
    val profiles:Set<String>?=profiles?.let { Collections.unmodifiableSet(LinkedHashSet(it)) }
}

data class ExcludedRule(val ruleId:String,val reasons:List<String>)
data class OverriddenRule(val ruleId:String,val topic:String,val winnerIds:List<String>,val effectiveLevel:EffectivePolicyLevel)
data class RuleConflict(
    val topic:String,val effectiveLevel:EffectivePolicyLevel,val ruleIds:Set<String>,
    val authorities:Map<String,RuleAuthority>
)
data class ResolutionResult(
    val activeRules:List<KnowledgeRule>,val conflicts:List<RuleConflict>,val profiles:Set<String>,
    val excludedRules:List<ExcludedRule>,val overriddenRules:List<OverriddenRule>
)

object RuleResolver {
    fun resolve(rules:List<KnowledgeRule>,context:RuleContext):ResolutionResult {
        require(context.team==null || RuleIdentity.isCanonicalTeam(context.team)) {
            "invalid rule context: team must contain digits only"
        }
        require(context.season==null || RuleIdentity.isCanonicalSeason(context.season)) {
            "invalid rule context: season must use YYYY-YYYY"
        }
        val violations=rules.flatMap(RuleValidator::validate)
            .sortedWith(compareBy({ it.ruleId },{ it.field },{ it.message }))
        require(violations.isEmpty()) {
            "invalid rule set: "+violations.joinToString("; ") {
                "rule=${it.ruleId} field=${it.field} message=${it.message}"
            }
        }
        val profiles=RuleProfiles.normalize(context.profiles)
        val excluded=mutableListOf<ExcludedRule>()
        val applicable=rules.sortedBy { it.id }.filter { rule ->
            val reasons=buildList {
                if (rule.status!=RuleStatus.APPROVED) add("status")
                if (rule.applicability.teams.isNotEmpty() && context.team !in rule.applicability.teams) add("team")
                if (rule.applicability.seasons.isNotEmpty() && context.season !in rule.applicability.seasons) add("season")
                if (!profiles.containsAll(rule.applicability.profiles)) add("profile")
            }.sorted()
            if (reasons.isNotEmpty()) excluded+=ExcludedRule(rule.id,reasons)
            reasons.isEmpty()
        }
        val active=mutableListOf<KnowledgeRule>()
        val conflicts=mutableListOf<RuleConflict>()
        val overridden=mutableListOf<OverriddenRule>()
        applicable.groupBy { it.topic }.toSortedMap().forEach { (topic,group) ->
            val level=group.map(RulePolicy::level).maxBy { it.rank }
            val winners=group.filter { RulePolicy.level(it)==level }.sortedBy { it.id }
            val winnerIds=winners.map { it.id }
            if (winners.size==1) active+=winners.single()
            else conflicts+=RuleConflict(topic,level,winnerIds.toSet(),winners.associate { it.id to it.authority })
            group.filter { it.id !in winnerIds }.forEach {
                overridden+=OverriddenRule(it.id,topic,winnerIds,level)
            }
        }
        return ResolutionResult(active.sortedBy { it.id },conflicts,profiles,excluded,overridden.sortedBy { it.ruleId })
    }
}
