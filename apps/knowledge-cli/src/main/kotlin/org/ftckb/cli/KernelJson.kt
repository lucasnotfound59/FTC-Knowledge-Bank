package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import org.ftckb.domain.GitRuleEvidence
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.ResolutionResult
import org.ftckb.domain.RuleViolation
import org.ftckb.domain.WebRuleEvidence
import org.ftckb.standardizer.Standardizer

/**
 * Stable, versioned JSON contract for external agents that consume the policy kernel.
 * Consumers rely on schemaVersion=2 and on deterministic ordering (rules and conflicts
 * sorted by id/topic); breaking changes must bump schemaVersion.
 */
object KernelJson {
    const val SCHEMA_VERSION=2
    private val mapper=JsonMapper.builder().build()

    fun validateJson(ruleCount:Int):String {
        val root=mapper.createObjectNode()
        root.put("schemaVersion",SCHEMA_VERSION)
        root.put("command","validate")
        root.put("ok",true)
        root.put("ruleCount",ruleCount)
        root.putArray("violations")
        return mapper.writeValueAsString(root)
    }

    fun errorJson(command:String?,code:String,message:String):String {
        val root=mapper.createObjectNode()
        root.put("schemaVersion",SCHEMA_VERSION)
        if (command in setOf("validate","resolve","check")) root.put("command",command)
        root.put("ok",false)
        root.putObject("error").apply {
            put("code",code)
            put("message",message)
        }
        return mapper.writeValueAsString(root)
    }

    fun violationsJson(command:String,violations:List<RuleViolation>):String {
        val root=mapper.createObjectNode()
        root.put("schemaVersion",SCHEMA_VERSION)
        root.put("command",command)
        root.put("ok",false)
        val array=root.putArray("violations")
        violations.sortedWith(compareBy({ it.ruleId },{ it.field })).forEach { violation ->
            array.addObject().apply {
                put("ruleId",violation.ruleId)
                put("field",violation.field)
                put("message",violation.message)
            }
        }
        root.putObject("error").apply {
            put("code","invalid-knowledge")
            put("message","${violations.size} rule violation(s)")
        }
        return mapper.writeValueAsString(root)
    }

    fun resolveJson(
        team:String,
        season:String,
        result:ResolutionResult
    ):String {
        val root=mapper.createObjectNode()
        root.put("schemaVersion",SCHEMA_VERSION)
        root.put("command","resolve")
        root.put("team",team)
        root.put("season",season)
        putSortedStrings(root.putArray("profiles"),result.profiles)
        root.put("ok",result.conflicts.isEmpty())
        val rules=root.putArray("activeRules")
        result.activeRules.sortedBy { it.id }.forEach { rule -> rules.add(ruleNode(rule)) }
        val excluded=root.putArray("excludedRules")
        result.excludedRules.sortedBy { it.ruleId }.forEach { rule ->
            excluded.addObject().apply {
                put("ruleId",rule.ruleId)
                putSortedStrings(putArray("reasons"),rule.reasons)
            }
        }
        val overridden=root.putArray("overriddenRules")
        result.overriddenRules.sortedBy { it.ruleId }.forEach { rule ->
            overridden.addObject().apply {
                put("ruleId",rule.ruleId)
                put("topic",rule.topic)
                putSortedStrings(putArray("winnerIds"),rule.winnerIds)
                put("effectiveLevel",rule.effectiveLevel.name.lowercase())
            }
        }
        val conflictNodes=root.putArray("conflicts")
        result.conflicts.sortedBy { it.topic }.forEach { conflict ->
            val node=conflictNodes.addObject()
            node.put("topic",conflict.topic)
            node.put("effectiveLevel",conflict.effectiveLevel.name.lowercase())
            putSortedStrings(node.putArray("ruleIds"),conflict.ruleIds)
            val authorities=node.putObject("authorities")
            conflict.authorities.toSortedMap().forEach { (ruleId,authority) ->
                authorities.put(ruleId,authority.name.lowercase())
            }
        }
        return mapper.writeValueAsString(root)
    }

    fun checkJson(team:String,season:String,profiles:Set<String>,outcome:Standardizer.Outcome):String {
        val root=mapper.createObjectNode()
        root.put("schemaVersion",SCHEMA_VERSION)
        root.put("command","check")
        root.put("team",team)
        root.put("season",season)
        putSortedStrings(root.putArray("profiles"),profiles)
        root.put("ok",outcome.violations.isEmpty())
        val violations=root.putArray("violations")
        outcome.violations.sortedWith(compareBy({ it.ruleId },{ it.path.orEmpty() },{ it.line ?: 0 })).forEach { violation ->
            violations.addObject().apply {
                put("ruleId",violation.ruleId)
                put("check",violation.check)
                violation.path?.let { put("path",it) }
                violation.line?.let { put("line",it) }
                put("pattern",violation.pattern)
                put("detail",violation.detail)
            }
        }
        val soft=root.putArray("soft")
        outcome.soft.sortedBy { it.first }.forEach { (ruleId,note) ->
            soft.addObject().apply {
                put("ruleId",ruleId)
                put("note",note)
            }
        }
        return mapper.writeValueAsString(root)
    }

    private fun ruleNode(rule:KnowledgeRule)=mapper.createObjectNode().apply {
        put("id",rule.id)
        put("topic",rule.topic)
        put("title",rule.title)
        put("instruction",rule.instruction)
        put("rationale",rule.rationale)
        put("status",rule.status.name.lowercase())
        put("authority",rule.authority.name.lowercase())
        put("policyLevel",rule.policyLevel.name.lowercase())
        putObject("applicability").apply {
            putSortedStrings(putArray("teams"),rule.applicability.teams)
            putSortedStrings(putArray("seasons"),rule.applicability.seasons)
            putSortedStrings(putArray("profiles"),rule.applicability.profiles)
        }
        putArray("evidence").apply {
            rule.evidence.forEach { evidence ->
                add(evidenceNode(evidence))
            }
        }
        putArray("checks").apply {
            rule.checks.forEach { check ->
                addObject().apply {
                    put("kind",check.kind.name.lowercase())
                    put("pattern",check.pattern)
                    check.appliesTo?.let { put("appliesTo",it) }
                    put("note",check.note)
                }
            }
        }
        putArray("reviewTriggers").apply {
            rule.reviewTriggers.forEach { trigger ->
                addObject().apply {
                    putSortedStrings(putArray("paths"),trigger.paths)
                    putSortedStrings(putArray("addedLinePatterns"),trigger.addedLinePatterns)
                }
            }
        }
    }

    private fun putSortedStrings(node:com.fasterxml.jackson.databind.node.ArrayNode,values:Iterable<String>) {
        values.sorted().forEach { node.add(it) }
    }

    private fun evidenceNode(evidence:org.ftckb.domain.RuleEvidence)=mapper.createObjectNode().apply {
        when (evidence) {
            is GitRuleEvidence -> {
                put("type","git")
                put("repository",evidence.repository)
                put("commit",evidence.commit)
                put("file",evidence.file)
                evidence.symbol?.let { put("symbol",it) }
                evidence.line?.let { put("line",it) }
            }
            is WebRuleEvidence -> {
                put("type","web")
                put("url",evidence.url)
                put("title",evidence.title)
                put("publisher",evidence.publisher)
                put("accessedAt",evidence.accessedAt.toString())
                put("section",evidence.section)
                evidence.version?.let { put("version",it) }
                evidence.product?.let { put("product",it) }
                evidence.sku?.let { put("sku",it) }
            }
        }
    }
}
