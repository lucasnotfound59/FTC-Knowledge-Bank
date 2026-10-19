package org.ftckb.agent.edit

data class EditGateNotice(
    val kind:String,val ruleId:String?,val check:String?,val path:String?,
    val line:Int?,val detail:String
)

data class EditGateResult(val allowed:Boolean,val notices:List<EditGateNotice> =emptyList())

/** Request-scoped policy inspection supplied by the application assembly. */
interface EditPrewriteGate {
    fun inspect(preview:ValidatedEditBatch):EditGateResult

    /** Optional lease serializing knowledge changes with the full edit transaction. */
    fun beginWrite(preview:ValidatedEditBatch):AutoCloseable=AutoCloseable {}

    /** Must only verify freshness; called before apply and at every mutation boundary. */
    fun verifyCurrent(preview:ValidatedEditBatch)
}

class EditGateException(
    val code:String,val notices:List<EditGateNotice>,message:String
):RuntimeException(message)

class EditBaselineValidationException(message:String):IllegalArgumentException(message)

/** Stable bounded classification; callers must not persist arbitrary exception text. */
fun editFailureCategory(failure:Throwable):String?=when (failure) {
    is EditValidationException -> failure.category
    is EditPathValidationException -> "path_guard"
    is EditBaselineValidationException,is EditContentRaceException,is EditPermissionRaceException -> "stale_baseline"
    is FileEditApplyException -> editFailureCategory(failure.originalFailure)
    is EditGateException -> failure.code
    else -> null
}
