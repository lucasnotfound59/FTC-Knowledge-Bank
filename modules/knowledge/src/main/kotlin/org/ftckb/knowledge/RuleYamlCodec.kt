package org.ftckb.knowledge

import java.math.BigDecimal
import java.time.Instant
import org.ftckb.domain.Approval
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleApplicability
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleEvidence
import org.ftckb.domain.RuleStatus
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

object RuleYamlCodec {
    private val load=Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
    private val ruleKeys=setOf(
        "id","topic","title","instruction","rationale","status","authority","applicability",
        "evidence","approval","supersedes","positiveExample","negativeExample"
    )

    fun decode(text:String):List<KnowledgeRule> {
        val root=load.loadFromString(text).asMap("root")
        root.rejectUnknownFields(setOf("schemaVersion","rules"),"root")
        require(root.int("schemaVersion")==1) { "unsupported schemaVersion" }
        return root.requiredList("rules").mapIndexed { index,value ->
            val name="rules[$index]"
            decodeRule(value.asMap(name),name)
        }
    }

    private fun decodeRule(map:Map<String,Any?>,name:String):KnowledgeRule {
        map.rejectUnknownFields(ruleKeys,name)
        val applicability=map.optionalMap("applicability") ?: emptyMap()
        applicability.rejectUnknownFields(setOf("teams","seasons"),"$name.applicability")
        val approval=map.optionalMap("approval")?.let {
            it.rejectUnknownFields(setOf("approver","role","team","approvedAt"),"$name.approval")
            Approval(
                approver=it.string("approver"),
                role=ApproverRole.valueOf(it.string("role").uppercase()),
                team=it.optionalString("team"),
                approvedAt=Instant.parse(it.string("approvedAt"))
            )
        }
        return KnowledgeRule(
            id=map.string("id"),topic=map.string("topic"),title=map.string("title"),
            instruction=map.string("instruction"),rationale=map.string("rationale"),
            status=RuleStatus.valueOf(map.string("status").uppercase()),
            authority=RuleAuthority.valueOf(map.string("authority").uppercase()),
            applicability=RuleApplicability(
                teams=applicability.stringSet("teams"),seasons=applicability.stringSet("seasons")
            ),
            evidence=map.requiredList("evidence").mapIndexed { index,value ->
                val evidenceName="$name.evidence[$index]"
                val item=value.asMap(evidenceName)
                item.rejectUnknownFields(setOf("repository","commit","file","symbol","line"),evidenceName)
                RuleEvidence(
                    item.string("repository"),item.string("commit"),item.string("file"),
                    item.optionalString("symbol"),item.optionalInt("line")
                )
            },
            approval=approval,supersedes=map.optionalString("supersedes"),
            positiveExample=map.optionalString("positiveExample"),negativeExample=map.optionalString("negativeExample")
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(name:String)=this as? Map<String,Any?> ?: error("$name must be a map")
    private fun Map<String,Any?>.string(key:String)=this[key] as? String ?: error("$key must be a string")
    private fun Map<String,Any?>.optionalString(key:String):String? {
        if (key !in this) return null
        return this[key] as? String ?: error("$key must be a string")
    }
    private fun Map<String,Any?>.int(key:String)=this[key].strictInt(key)
    private fun Map<String,Any?>.optionalInt(key:String):Int? {
        if (key !in this) return null
        return this[key].strictInt(key)
    }
    private fun Map<String,Any?>.requiredList(key:String)=this[key] as? List<*>
        ?: error("$key must be a list")
    private fun Map<String,Any?>.optionalList(key:String):List<*> {
        if (key !in this) return emptyList<Any?>()
        return this[key] as? List<*> ?: error("$key must be a list")
    }
    private fun Map<String,Any?>.optionalMap(key:String):Map<String,Any?>? {
        if (key !in this) return null
        return this[key].asMap(key)
    }
    private fun Map<String,Any?>.stringSet(key:String)=optionalList(key).map {
        it as? String ?: error("$key values must be strings")
    }.toSet()
    private fun Any?.strictInt(key:String):Int {
        val number=this as? Number ?: error("$key must be an integer")
        return runCatching { BigDecimal(number.toString()).toBigIntegerExact().intValueExact() }
            .getOrElse { error("$key must be an integer") }
    }
    private fun Map<String,Any?>.rejectUnknownFields(allowed:Set<String>,name:String) {
        val unknown=(keys-allowed).sorted()
        if (unknown.isNotEmpty()) error("$name contains unknown fields: ${unknown.joinToString()}")
    }
}
