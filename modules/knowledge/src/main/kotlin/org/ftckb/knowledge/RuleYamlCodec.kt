package org.ftckb.knowledge

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.ftckb.domain.Approval
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.GitRuleEvidence
import org.ftckb.domain.RuleApplicability
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleEvidence
import org.ftckb.domain.RuleStatus
import org.ftckb.domain.WebRuleEvidence
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

object RuleYamlCodec {
    private val localDatePattern=Regex("""\d{4}-\d{2}-\d{2}""")
    private val load=Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
    private val ruleKeys=setOf(
        "id","topic","title","instruction","rationale","status","authority","applicability",
        "evidence","approval","supersedes","positiveExample","negativeExample"
    )

    fun decode(text:String):List<KnowledgeRule> {
        val root=load.loadFromString(text).asMap("root")
        root.rejectUnknownFields(setOf("schemaVersion","rules"),"root")
        val schemaVersion=root.int("schemaVersion")
        require(schemaVersion in 1..2) { "unsupported schemaVersion" }
        return root.requiredList("rules").mapIndexed { index,value ->
            val name="rules[$index]"
            decodeRule(value.asMap(name),name,schemaVersion)
        }
    }

    private fun decodeRule(map:Map<String,Any?>,name:String,schemaVersion:Int):KnowledgeRule {
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
                decodeEvidence(item,evidenceName,schemaVersion)
            },
            approval=approval,supersedes=map.optionalString("supersedes"),
            positiveExample=map.optionalString("positiveExample"),negativeExample=map.optionalString("negativeExample")
        )
    }

    private fun decodeEvidence(map:Map<String,Any?>,name:String,schemaVersion:Int):RuleEvidence {
        if (schemaVersion==1) {
            map.rejectUnknownFields(setOf("repository","commit","file","symbol","line"),name)
            return decodeGitEvidence(map)
        }
        return when (val type=map.string("type")) {
            "git" -> {
                map.rejectUnknownFields(setOf("type","repository","commit","file","symbol","line"),name)
                decodeGitEvidence(map)
            }
            "web" -> {
                map.rejectUnknownFields(
                    setOf("type","url","title","publisher","accessedAt","section","version","product","sku"),
                    name
                )
                WebRuleEvidence(
                    url=map.string("url"),title=map.string("title"),publisher=map.string("publisher"),
                    accessedAt=map.localDate("accessedAt"),section=map.string("section"),
                    version=map.optionalString("version"),product=map.optionalString("product"),
                    sku=map.optionalString("sku")
                )
            }
            else -> error("unsupported evidence type: $type")
        }
    }

    private fun decodeGitEvidence(map:Map<String,Any?>)=GitRuleEvidence(
        map.string("repository"),map.string("commit"),map.string("file"),
        map.optionalString("symbol"),map.optionalInt("line")
    )

    private fun Map<String,Any?>.localDate(key:String):LocalDate=runCatching {
        val value=string(key)
        require(localDatePattern.matches(value))
        LocalDate.parse(value)
    }.getOrElse { error("$key must use YYYY-MM-DD") }

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
