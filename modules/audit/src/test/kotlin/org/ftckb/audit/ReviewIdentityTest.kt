package org.ftckb.audit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

internal fun reviewFixture(): AuditRecord = AuditRecord(
    UUID.randomUUID().toString(), "2026-10-20T00:00:00Z", "2026-10-20T00:00:01Z",
    AuditMetadata("cli", "check", "/knowledge", AuditContext("20827", "2025-2026", listOf("generic"))),
    "version", emptyList(), listOf(AuditStage("parameters", AuditStageStatus.PASSED),
        AuditStage("standardizer", AuditStageStatus.PASSED, hits = listOf(AuditHit("soft", "rule", null, null, null, "Review this rule")))),
    AuditRunStatus.COMPLETED, 0, 1, null, null
)
internal fun reviewTarget(record: AuditRecord = reviewFixture()): ReviewTarget {
    val hit = record.stages[1].hits.single()
    return ReviewTarget(ReviewIdentity.itemId(record, 1, hit), record.invocationId, ReviewIdentity.sourceDigest(record, 1, hit))
}
class ReviewIdentityTest {
    @Test fun `canonical object keys ignore insertion order but lists preserve check order`() {
        assertEquals(ReviewFingerprint.sha256(listOf(linkedMapOf("b" to 2, "a" to 1))), ReviewFingerprint.sha256(listOf(linkedMapOf("a" to 1, "b" to 2))))
        assertNotEquals(ReviewFingerprint.sha256(listOf(1, 2)), ReviewFingerprint.sha256(listOf(2, 1)))
    }
    @Test fun `identity preserves stage index and kind while source covers code version`() {
        val r = reviewFixture(); val h = r.stages[1].hits.single(); val first = reviewTarget(r)
        val changed = r.copy(stages = r.stages.mapIndexed { i, s -> if (i == 1) s.copy(codeVersion = AuditCodeVersion("worktree", "a".repeat(64), "b".repeat(64))) else s })
        assertEquals(first.itemId, reviewTarget(changed).itemId)
        assertNotEquals(first.sourceDigest, reviewTarget(changed).sourceDigest)
        assertNotEquals(first.itemId, ReviewIdentity.itemId(r.copy(stages = r.stages + r.stages[1]), 2, h))
    }
}
