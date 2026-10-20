package org.ftckb.audit

import com.fasterxml.jackson.annotation.JsonValue
import java.nio.file.Path

enum class AuditEventType { STARTED, SNAPSHOT, STAGE, FINISHED;
    @JsonValue fun wireValue(): String = name.lowercase(java.util.Locale.ROOT)
}
enum class AuditRunStatus { STARTED, COMPLETED, FAILED;
    @JsonValue fun wireValue(): String = name.lowercase(java.util.Locale.ROOT)
}
enum class AuditStageStatus { NOT_RUN, PASSED, BLOCKED, NOT_APPLICABLE;
    @JsonValue fun wireValue(): String = name.lowercase(java.util.Locale.ROOT)
}
data class AuditContext(val team: String?, val season: String?, val profiles: List<String>?, val workMode: String = "normal")
data class AuditMetadata(
    val entrypoint: String, val command: String, val knowledgeRoot: String?,
    val context: AuditContext? = null, val parentInvocationId: String? = null, val model: String? = null
)
data class AuditRuleSnapshot(
    val id: String, val topic: String, val title: String, val instruction: String,
    val authority: String, val policyLevel: String, val teams: List<String>,
    val seasons: List<String>, val profiles: List<String>
)
data class AuditHit(val kind: String, val ruleId: String?, val check: String?, val path: String?, val line: Int?, val detail: String)
data class AuditCodeVersion(
    val kind: String,
    val subjectId: String?,
    val inputDigest: String,
    val head: String? = null,
    val predecessorInvocationId: String? = null,
    val schemaVersion: Int = 1
)
data class AuditStage(
    val name: String, val status: AuditStageStatus, val category: String? = null,
    val detail: String? = null, val hits: List<AuditHit> = emptyList(),
    val codeVersion: AuditCodeVersion? = null
)
data class AuditEvent(
    val invocationId: String, val type: AuditEventType, val timestamp: String,
    val metadata: AuditMetadata? = null, val knowledgeVersion: String? = null,
    val ruleSnapshots: List<AuditRuleSnapshot> = emptyList(), val stage: AuditStage? = null,
    val status: AuditRunStatus? = null, val exitCode: Int? = null,
    val durationMillis: Long? = null, val inputTokens: Int? = null, val outputTokens: Int? = null
)
data class AuditRecord(
    val invocationId: String, val startedAt: String, val endedAt: String?,
    val metadata: AuditMetadata, val knowledgeVersion: String?, val ruleSnapshots: List<AuditRuleSnapshot>,
    val stages: List<AuditStage>, val status: AuditRunStatus, val exitCode: Int?,
    val durationMillis: Long?, val inputTokens: Int?, val outputTokens: Int?
)
data class AuditHealth(val ok: Boolean, val warnings: List<String>)
data class AuditPage(val items: List<AuditRecord>, val nextCursor: String?, val revision: String, val health: AuditHealth)
data class AuditSummary(
    val revision: String, val total: Long, val unfinished: Long, val diffChecked: Long,
    val diffBlocked: Long, val diffNotRun: Long, val diffNotApplicable: Long, val health: AuditHealth
)
interface AuditStore {
    fun append(event: AuditEvent): Boolean
    fun page(cursor: String? = null, limit: Int = 50): AuditPage
    fun find(invocationId: String): AuditRecord?
    fun children(parentInvocationId: String): List<AuditRecord>
    fun summary(): AuditSummary
}

/** One resolution path for all entrypoints. Explicit constructor injection takes precedence. */
fun defaultAuditDirectory(environment: Map<String, String> = System.getenv()): Path =
    environment["FTCKB_AUDIT_DIR"]?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".ftckb", "audit")
