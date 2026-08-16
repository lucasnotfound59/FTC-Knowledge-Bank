package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import org.ftckb.domain.GitRuleEvidence
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleConflict
import org.ftckb.domain.RuleViolation
import org.ftckb.domain.WebRuleEvidence

/**
 * Stable, versioned JSON contract for external agents that consume the policy kernel.
 * Consumers rely on schemaVersion=1 and on deterministic ordering (rules and conflicts
 * sorted by id/topic); breaking changes must bump schemaVersion.
 */
object KernelJson {
    const val SCHEMA_VERSION=1
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
        if (command in setOf("validate","resolve")) root.put("command",command)
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
        activeRules:List<KnowledgeRule>,
        conflicts:List<RuleConflict>
    ):String {
        val root=mapper.createObjectNode()
        root.put("schemaVersion",SCHEMA_VERSION)
        root.put("command","resolve")
        root.put("team",team)
        root.put("season",season)
        root.put("ok",conflicts.isEmpty())
        val rules=root.putArray("activeRules")
        activeRules.sortedBy { it.id }.forEach { rule -> rules.add(ruleNode(rule)) }
        val conflictNodes=root.putArray("conflicts")
        conflicts.sortedBy { it.topic }.forEach { conflict ->
            val node=conflictNodes.addObject()
            node.put("topic",conflict.topic)
            node.put("authority",conflict.authority.name.lowercase())
            val ids=node.putArray("ruleIds")
            conflict.ruleIds.sorted().forEach { ids.add(it) }
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
        putObject("applicability").apply {
            putArray("teams").apply { rule.applicability.teams.sorted().forEach { add(it) } }
            putArray("seasons").apply { rule.applicability.seasons.sorted().forEach { add(it) } }
        }
        putArray("evidence").apply {
            rule.evidence.forEach { evidence ->
                add(evidenceNode(evidence))
            }
        }
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
