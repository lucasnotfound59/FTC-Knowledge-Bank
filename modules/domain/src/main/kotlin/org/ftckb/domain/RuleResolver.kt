package org.ftckb.domain

data class RuleContext(val team:String?,val season:String?)
data class RuleConflict(val topic:String,val authority:RuleAuthority,val ruleIds:Set<String>)
data class ResolutionResult(val activeRules:List<KnowledgeRule>,val conflicts:List<RuleConflict>)

object RuleResolver {
    private val priority=mapOf(RuleAuthority.OFFICIAL to 3,RuleAuthority.TEAM to 2,RuleAuthority.SHARED to 1)

    fun resolve(rules:List<KnowledgeRule>,context:RuleContext):ResolutionResult {
        val applicable=rules.filter { rule ->
            rule.status==RuleStatus.APPROVED &&
                (rule.applicability.teams.isEmpty() || context.team in rule.applicability.teams) &&
                (rule.applicability.seasons.isEmpty() || context.season in rule.applicability.seasons)
        }
        val active=mutableListOf<KnowledgeRule>()
        val conflicts=mutableListOf<RuleConflict>()
        applicable.groupBy { it.topic }.toSortedMap().forEach { (topic,topicRules) ->
            val winningPriority=topicRules.maxOf { priority.getValue(it.authority) }
            val winners=topicRules.filter { priority.getValue(it.authority)==winningPriority }.sortedBy { it.id }
            if (winners.size==1) active+=winners.single()
            else conflicts+=RuleConflict(topic,winners.first().authority,winners.map { it.id }.toSet())
        }
        return ResolutionResult(active.sortedBy { it.id },conflicts.sortedBy { it.topic })
    }
}
