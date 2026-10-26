package org.ftckb.audit

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/** The audit format is deliberately independent of kernel JSON. No arbitrary maps are persisted. */
internal object AuditCodec {
    const val MAX_LINE_BYTES = 4 * 1024 * 1024
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val entrypoints = setOf("cli", "chat", "serve", "dashboard")
    private val commands = setOf("validate", "resolve", "check", "ask", "edit", "undo", "discard", "model", "rule_create", "rule_edit", "rule_approve", "rule_reject")
    private val stages = setOf("parameters", "knowledge_load", "knowledge_validate", "resolve", "diff_read", "standardizer", "path_guard", "branch_guard", "citation_guard", "baseline_guard", "write", "restore", "model")
    private val categories = setOf("hard", "soft", "dev_downgrade", "usage", "load_error", "invalid_knowledge", "conflict", "path_guard", "branch_guard", "citation_guard", "stale_baseline", "stale_knowledge", "model_failure", "storage_error")
    private val secret = Regex("(?i)(bearer\\s+)[^\\s]+|(?:sk-(?:proj-)?|sk-ant-)[A-Za-z0-9_-]{8,}|((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|authorization|token)\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+")
    private fun text(value: String, limit: Int = 1024): String = secret.replace(value.take(limit)) {
        if (it.groups[1] != null) "Bearer [REDACTED]" else if (it.groups[2] != null) "${it.groups[2]!!.value}[REDACTED]" else "[REDACTED]"
    }.filter { it == '\n' || it == '\t' || !it.isISOControl() }
    private fun detail(value: String): String = if (value.contains("diff --git") || value.contains("@@ ")) "[diff content omitted]" else text(value.lineSequence().firstOrNull().orEmpty())
    private fun uuid(value: String): String = UUID.fromString(value).toString().also { require(it == value) }

    fun safe(event: AuditEvent): AuditEvent {
        uuid(event.invocationId)
        require(Instant.parse(event.timestamp).toString() == event.timestamp)
        require(event.durationMillis == null || event.durationMillis >= 0)
        require(event.inputTokens == null || event.inputTokens >= 0)
        require(event.outputTokens == null || event.outputTokens >= 0)
        require(event.type != AuditEventType.STARTED || event.metadata != null)
        require(event.type != AuditEventType.STAGE || event.stage != null)
        require(event.type != AuditEventType.FINISHED || event.status in setOf(AuditRunStatus.COMPLETED, AuditRunStatus.FAILED))
        val metadata = event.metadata?.let { m ->
            require(m.entrypoint in entrypoints && m.command in commands)
            require(m.context?.workMode == null || m.context.workMode in setOf("normal", "test", "dev"))
            m.copy(knowledgeRoot = m.knowledgeRoot?.let { text(it, 2048) }, parentInvocationId = m.parentInvocationId?.let(::uuid), model = m.model?.let { text(it, 120) }, context = m.context?.let {
                it.copy(team = it.team?.let { s -> text(s, 32) }, season = it.season?.let { s -> text(s, 32) }, profiles = it.profiles?.take(16)?.map { s -> text(s, 80) })
            })
        }
        val stage = event.stage?.let { s ->
            require(s.name in stages && (s.category == null || s.category in categories))
            s.codeVersion?.let { version ->
                require(version.schemaVersion == 1 && version.kind in setOf("worktree", "supplied_patch", "edit_preview"))
                ReviewCodec.digest(version.inputDigest)
                version.subjectId?.let(ReviewCodec::digest)
                version.head?.let { require(Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})").matches(it)) }
                version.predecessorInvocationId?.let(ReviewCodec::uuid)
            }
            s.copy(detail = s.detail?.let(::detail), hits = s.hits.take(256).map { h ->
                h.copy(kind = text(h.kind, 64), ruleId = h.ruleId?.let { text(it, 160) }, check = h.check?.let { text(it, 120) }, path = h.path?.let { text(it, 2048) }, detail = detail(h.detail))
            })
        }
        return event.copy(metadata = metadata, stage = stage, knowledgeVersion = event.knowledgeVersion?.let { text(it, 160) }, ruleSnapshots = event.ruleSnapshots.take(256).map { r ->
            r.copy(id = text(r.id, 160), topic = text(r.topic, 160), title = text(r.title, 256), instruction = text(r.instruction, 8192), authority = text(r.authority, 40), policyLevel = text(r.policyLevel, 40), teams = r.teams.take(128).map { text(it, 32) }, seasons = r.seasons.take(64).map { text(it, 32) }, profiles = r.profiles.take(16).map { text(it, 80) })
        })
    }

    fun encode(event: AuditEvent): ByteArray {
        val node = mapper.valueToTree<ObjectNode>(safe(event))
        node.put("schemaVersion", 1)
        return mapper.writeValueAsBytes(node).also { require(it.size <= MAX_LINE_BYTES) }
    }

    private fun JsonNode.string(key: String): String = get(key)?.takeIf { it.isTextual }?.asText() ?: error("Missing text")
    private fun JsonNode.nullableString(key: String): String? = get(key)?.takeUnless { it.isNull }?.let { require(it.isTextual); it.asText() }
    private fun JsonNode.list(key: String): List<JsonNode> = get(key)?.let { require(it.isArray); it.toList() } ?: emptyList()
    private fun JsonNode.strings(key: String): List<String> = list(key).map { require(it.isTextual); it.asText() }
    private fun JsonNode.number(key: String): Long? = get(key)?.takeUnless { it.isNull }?.let { require(it.isIntegralNumber && it.canConvertToLong()); it.longValue() }
    private fun JsonNode.integer(key: String): Int? = number(key)?.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }?.toInt()
    private inline fun <reified T : Enum<T>> enum(value: String): T = enumValues<T>().first { it.name.lowercase(java.util.Locale.ROOT) == value }

    fun decode(bytes: ByteArray): AuditEvent {
        val n = mapper.readTree(bytes)
        require(n.isObject && n.get("schemaVersion")?.isIntegralNumber == true && n.get("schemaVersion").intValue() == 1)
        val m = n.get("metadata")?.takeUnless { it.isNull }?.let { m ->
            val c = m.get("context")?.takeUnless { it.isNull }?.let { c -> AuditContext(c.nullableString("team"), c.nullableString("season"), c.get("profiles")?.takeUnless { it.isNull }?.let { c.strings("profiles") }, c.string("workMode")) }
            AuditMetadata(m.string("entrypoint"), m.string("command"), m.nullableString("knowledgeRoot"), c, m.nullableString("parentInvocationId"), m.nullableString("model"))
        }
        val rules = n.list("ruleSnapshots").map { AuditRuleSnapshot(it.string("id"), it.string("topic"), it.string("title"), it.string("instruction"), it.string("authority"), it.string("policyLevel"), it.strings("teams"), it.strings("seasons"), it.strings("profiles")) }
        val s = n.get("stage")?.takeUnless { it.isNull }?.let { s ->
            AuditStage(s.string("name"), enum(s.string("status")), s.nullableString("category"), s.nullableString("detail"), s.list("hits").map { AuditHit(it.string("kind"), it.nullableString("ruleId"), it.nullableString("check"), it.nullableString("path"), it.integer("line"), it.string("detail")) }, s.get("codeVersion")?.takeUnless { it.isNull }?.let { v ->
                require(v.isObject && v.fieldNames().asSequence().all { it in setOf("kind", "subjectId", "inputDigest", "head", "predecessorInvocationId", "schemaVersion") })
                AuditCodeVersion(v.string("kind"), v.nullableString("subjectId"), v.string("inputDigest"), v.nullableString("head"), v.nullableString("predecessorInvocationId"), requireNotNull(v.integer("schemaVersion")))
            })
        }
        return safe(AuditEvent(n.string("invocationId"), enum(n.string("type")), n.string("timestamp"), m, n.nullableString("knowledgeVersion"), rules, s, n.nullableString("status")?.let { enum<AuditRunStatus>(it) }, n.integer("exitCode"), n.number("durationMillis"), n.integer("inputTokens"), n.integer("outputTokens")))
    }
}
