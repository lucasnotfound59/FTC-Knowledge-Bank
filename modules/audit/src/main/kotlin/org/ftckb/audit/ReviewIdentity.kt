package org.ftckb.audit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/** Keys are sorted recursively, while array order remains the actual input order. */
object ReviewFingerprint {
    private val mapper = ObjectMapper()
    fun sha256(parts: List<Any?>): String {
        val bytes = mapper.writeValueAsBytes(canonical(mapper.valueToTree(parts)))
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { out ->
            node.fieldNames().asSequence().sorted().forEach { out.set<JsonNode>(it, canonical(node.get(it))) }
        }
        node.isArray -> mapper.createArrayNode().also { out -> node.forEach { out.add(canonical(it)) } }
        else -> node
    }
}

/** Callers supply the safely decoded persisted record, never an unpersisted producer object. */
object ReviewIdentity {
    fun itemId(record: AuditRecord, stageIndex: Int, hit: AuditHit): String {
        require(stageIndex in record.stages.indices && hit in record.stages[stageIndex].hits)
        return ReviewFingerprint.sha256(listOf(record.invocationId, stageIndex, hit.kind, hit.ruleId, hit.check, hit.path, hit.line, hit.detail))
    }
    fun sourceDigest(record: AuditRecord, stageIndex: Int, hit: AuditHit): String = ReviewFingerprint.sha256(listOf(
        itemId(record, stageIndex, hit), record.metadata, record.knowledgeVersion,
        record.ruleSnapshots.filter { it.id == hit.ruleId }, record.stages[stageIndex]
    ))
}
