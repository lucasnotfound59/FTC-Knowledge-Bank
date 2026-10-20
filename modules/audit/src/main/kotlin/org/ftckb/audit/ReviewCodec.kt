package org.ftckb.audit

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID

/** Independent schema: no source body or arbitrary caller fields enter review history. */
internal object ReviewCodec {
    const val MAX_LINE_BYTES = 64 * 1024
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val hash = Regex("[0-9a-f]{64}")
    private val secret = Regex("(?i)(bearer\\s+)[^\\s]+|(?:sk-(?:proj-)?|sk-ant-)[A-Za-z0-9_-]{8,}|((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|authorization|token)\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+")
    private val sensitiveKeys = setOf("token", "apikey", "accesstoken", "refreshtoken", "password", "authorization", "secret", "clientsecret")
    fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    fun digest(value: String) { require(hash.matches(value)) }
    private fun controls(value: String) { require(value.none { it.isISOControl() && it != '\n' && it != '\t' }) }
    private fun text(value: String, max: Int): String {
        controls(value)
        val trimmed = value.trim()
        require(trimmed.length in 1..max)
        return secret.replace(trimmed) {
            if (it.groups[1] != null) "Bearer [REDACTED]" else if (it.groups[2] != null) "${it.groups[2]!!.value}[REDACTED]" else "[REDACTED]"
        }.also { require(it.length <= max) }
    }
    fun target(target: ReviewTarget) { digest(target.itemId); uuid(target.invocationId); digest(target.sourceDigest) }
    private fun url(value: String) {
        require(value.length in 1..2048 && value.none { it.isISOControl() })
        val uri = URI(value)
        require(uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        listOfNotNull(uri.rawQuery, uri.rawFragment).forEach { component ->
            component.split('&', ';', '?').forEach { part ->
                val key = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8).lowercase().replace("_", "").replace("-", "")
                require(key !in sensitiveKeys)
            }
        }
    }
    fun safe(input: ReviewInput): Pair<ReviewInput, Boolean> {
        uuid(input.reviewId); target(input.target); require(input.expectedRevision >= 0)
        require(input.decision in setOf("verified", "needs_changes", "not_applicable"))
        require(input.evidenceUrls.size <= 8); input.evidenceUrls.forEach(::url)
        val reason = text(input.reason, 4000); val reviewer = text(input.reviewer, 120)
        // A defensive copy prevents callers mutating a list between validation and append.
        return input.copy(reason = reason, reviewer = reviewer, evidenceUrls = java.util.List.copyOf(input.evidenceUrls)) to
            (reason != input.reason.trim() || reviewer != input.reviewer.trim())
    }
    fun encode(record: ReviewRecord): ByteArray {
        require(record.schemaVersion == 1 && record.revision > 0 && Instant.parse(record.createdAt).toString() == record.createdAt)
        val (safe, _) = safe(ReviewInput(record.reviewId, record.target, 0, record.decision, record.reason, record.evidenceUrls, record.reviewer))
        require(safe.reason == record.reason && safe.reviewer == record.reviewer)
        return mapper.writeValueAsBytes(record).also { require(it.size <= MAX_LINE_BYTES) }
    }
    private fun JsonNode.fields(allowed: Set<String>) { require(isObject && fieldNames().asSequence().all { it in allowed }) }
    private fun JsonNode.text(key: String): String = get(key)?.takeIf { it.isTextual }?.asText() ?: error("Missing text")
    private fun JsonNode.long(key: String): Long = get(key)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue() ?: error("Missing integer")
    fun decode(bytes: ByteArray): ReviewRecord {
        require(bytes.size <= MAX_LINE_BYTES)
        val n = mapper.readTree(bytes)
        n.fields(setOf("reviewId", "target", "revision", "decision", "reason", "evidenceUrls", "reviewer", "createdAt", "sanitized", "schemaVersion"))
        require(n.long("schemaVersion") == 1L)
        val t = requireNotNull(n.get("target")); t.fields(setOf("itemId", "invocationId", "sourceDigest"))
        val urls = requireNotNull(n.get("evidenceUrls")); require(urls.isArray && urls.all { it.isTextual })
        val sanitized = requireNotNull(n.get("sanitized")); require(sanitized.isBoolean)
        val record = ReviewRecord(n.text("reviewId"), ReviewTarget(t.text("itemId"), t.text("invocationId"), t.text("sourceDigest")), n.long("revision"), n.text("decision"), n.text("reason"), java.util.List.copyOf(urls.map { it.asText() }), n.text("reviewer"), n.text("createdAt"), sanitized.booleanValue())
        encode(record)
        return record
    }
}
