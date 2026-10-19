package org.ftckb.agent

enum class RuntimeStageStatus { PASSED,BLOCKED,NOT_APPLICABLE }

data class RuntimeStageEvidence(
    val name:String,val status:RuntimeStageStatus,val category:String?=null,val detail:String?=null
)

fun interface RuntimeStageObserver {
    fun observe(evidence:RuntimeStageEvidence)
}

/** Observability failures must not alter authorization or transaction outcomes. */
internal fun RuntimeStageObserver.record(evidence:RuntimeStageEvidence) {
    runCatching { observe(evidence) }
}
