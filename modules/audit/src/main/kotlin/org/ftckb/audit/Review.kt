package org.ftckb.audit

data class ReviewTarget(val itemId: String, val invocationId: String, val sourceDigest: String)
data class ReviewInput(
    val reviewId: String, val target: ReviewTarget, val expectedRevision: Long,
    val decision: String, val reason: String, val evidenceUrls: List<String>, val reviewer: String
)
data class ReviewRecord(
    val reviewId: String, val target: ReviewTarget, val revision: Long,
    val decision: String, val reason: String, val evidenceUrls: List<String>, val reviewer: String,
    val createdAt: String, val sanitized: Boolean = false, val schemaVersion: Int = 1
)
data class ReviewHistory(val items: List<ReviewRecord>, val revision: String, val health: AuditHealth)
data class ReviewWriteResult(val record: ReviewRecord, val replayed: Boolean)
class ReviewProblem(val code: String) : RuntimeException(code)
interface ReviewStore {
    fun history(itemId: String): ReviewHistory
    fun health(): AuditHealth
    fun record(input: ReviewInput, validateSource: (ReviewTarget) -> Boolean): ReviewWriteResult
}
