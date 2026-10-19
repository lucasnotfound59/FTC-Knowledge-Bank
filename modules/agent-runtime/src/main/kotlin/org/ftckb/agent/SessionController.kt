package org.ftckb.agent

import java.nio.file.Path
import org.ftckb.agent.edit.EditAgent
import org.ftckb.agent.edit.EditHistory
import org.ftckb.agent.edit.EditReport
import org.ftckb.agent.edit.EditValidationException
import org.ftckb.agent.edit.FileEditApplyException
import org.ftckb.agent.edit.HistoryResult
import org.ftckb.agent.edit.editFailureCategory
import org.ftckb.git.AgentDiffRenderer
import org.ftckb.git.GitBranchState
import org.ftckb.git.GitWorkspace
import org.ftckb.git.TextChange
import org.ftckb.repository.RepositoryIndex

enum class AgentMode { ASK,EDIT }

sealed interface SessionResult
data class AskResult(val answer:AgentAnswer):SessionResult
data class EditResult(val report:EditReport):SessionResult
sealed interface HistoryCommandResult
data class HistoryAppliedResult(
    val result:HistoryResult,val warnings:List<String> =emptyList()
):HistoryCommandResult
data class RejectedResult(val message:String,val category:String?=null):SessionResult,HistoryCommandResult

private class EditAuthorizationException:RuntimeException()

class SessionController(
    private var askAgent:AskAgent,
    private var editAgent:EditAgent,
    private val history:EditHistory,
    repositoryRoot:Path,
    private val repositoryIndex:RepositoryIndex,
    private val branchInspector:(Path)->GitBranchState=GitWorkspace::currentBranch,
    private val indexRefresher:(Set<String>)->Unit=repositoryIndex::refresh,
    private val stageObserver:RuntimeStageObserver=RuntimeStageObserver {}
) {
    private val repositoryRoot=repositoryRoot.toRealPath()
    private var currentMode=AgentMode.ASK
    private var authorizedBranch:String?=null
    private var historyBranch:String?=null

    val mode:AgentMode
        @Synchronized get()=currentMode

    val authorizedEditBranch:String?
        @Synchronized get()=if (currentMode==AgentMode.EDIT) authorizedBranch else null

    val firstTouchDirtyPaths:Set<String>
        @Synchronized get()=history.firstTouchDirtyPaths

    /** Refresh the request graph without changing edit authorization or recovery history. */
    @Synchronized
    fun replaceAgents(askAgent:AskAgent,editAgent:EditAgent) {
        this.askAgent=askAgent
        this.editAgent=editAgent
    }

    @Synchronized
    fun setMode(requested:AgentMode):RejectedResult? {
        if (requested==AgentMode.EDIT) {
            val branch=currentNamedBranch()
                ?:return branchRejected("Edit requires a Git worktree with a named current branch")
            if (historyBranch!=null && branch!=historyBranch) {
                return branchRejected("Edit history belongs to another branch")
            }
            authorizedBranch=branch
            stageObserver.record(RuntimeStageEvidence("branch_guard",RuntimeStageStatus.PASSED))
        } else {
            authorizedBranch=null
        }
        currentMode=requested
        return null
    }

    @Synchronized
    fun submit(message:String):SessionResult=when (currentMode) {
        AgentMode.ASK -> AskResult(askAgent.ask(message))
        AgentMode.EDIT -> {
            if (!isAuthorizedBranchCurrent()) {
                RejectedResult("Edit requires the authorized named current branch","branch_guard")
            } else {
                try {
                    val branch=authorizedBranch
                    try {
                        EditResult(editAgent.edit(message,::requireAuthorizedBranchAtWriteBoundary))
                    } finally {
                        if (history.hasTrackedPaths) historyBranch=branch
                    }
                } catch (_:EditAuthorizationException) {
                    RejectedResult("Edit requires the authorized named current branch","branch_guard")
                } catch (failure:FileEditApplyException) {
                    if (failure.isRolledBackAuthorizationAbort()) {
                        RejectedResult("Edit requires the authorized named current branch","branch_guard")
                    } else {
                        throw failure
                    }
                } catch (failure:EditValidationException) {
                    RejectedResult("Edit plan validation failed; no files were written",failure.category)
                }
            }
        }
    }

    @Synchronized
    fun undo():HistoryCommandResult {
        if (currentMode!=AgentMode.EDIT) return RejectedResult("Undo requires Edit mode")
        if (!isAuthorizedBranchCurrent()) return RejectedResult("Undo requires the authorized named current branch","branch_guard")
        return try {
            historyResult(history.undo(::requireAuthorizedBranchAtWriteBoundary))
        } catch (failure:FileEditApplyException) {
            restoreFailed(failure)
            if (failure.isRolledBackAuthorizationAbort()) {
                RejectedResult("Undo requires the authorized named current branch","branch_guard")
            } else {
                throw failure
            }
        } catch (failure:Throwable) {
            restoreFailed(failure)
            throw failure
        }
    }

    @Synchronized
    fun discard():HistoryCommandResult {
        if (currentMode!=AgentMode.EDIT) return RejectedResult("Discard requires Edit mode")
        if (!isAuthorizedBranchCurrent()) return RejectedResult("Discard requires the authorized named current branch","branch_guard")
        return try {
            historyResult(history.discard(::requireAuthorizedBranchAtWriteBoundary))
        } catch (failure:FileEditApplyException) {
            restoreFailed(failure)
            if (failure.isRolledBackAuthorizationAbort()) {
                RejectedResult("Discard requires the authorized named current branch","branch_guard")
            } else {
                throw failure
            }
        } catch (failure:Throwable) {
            restoreFailed(failure)
            throw failure
        }
    }

    @Synchronized
    fun changes():List<TextChange> =history.changes()

    @Synchronized
    fun diff():String=AgentDiffRenderer.render(history.changes())

    private fun historyResult(result:HistoryResult):HistoryAppliedResult {
        stageObserver.record(RuntimeStageEvidence(
            "restore",when {
                !result.succeeded -> RuntimeStageStatus.BLOCKED
                result.changedPaths.isEmpty() -> RuntimeStageStatus.NOT_APPLICABLE
                else -> RuntimeStageStatus.PASSED
            },
            if (result.succeeded) null else "stale_baseline",
            when {
                !result.succeeded -> "Recovery has conflicting paths"
                result.changedPaths.isEmpty() -> "No recorded edits to restore"
                else -> "Recovery completed"
            }
        ))
        val warnings=result.warnings.toMutableList()
        if (result.succeeded && result.changedPaths.isNotEmpty()) {
            try {
                indexRefresher(result.changedPaths)
            } catch (_:Exception) {
                warnings+="Repository index refresh failed after files changed"
            }
        }
        return HistoryAppliedResult(result,warnings)
    }

    private fun isAuthorizedBranchCurrent():Boolean {
        val authorized=currentNamedBranch()==authorizedBranch
        stageObserver.record(RuntimeStageEvidence(
            "branch_guard",if (authorized) RuntimeStageStatus.PASSED else RuntimeStageStatus.BLOCKED,
            if (authorized) null else "branch_guard",
            if (authorized) null else "Authorized branch is not current"
        ))
        return authorized
    }

    private fun branchRejected(message:String):RejectedResult {
        stageObserver.record(RuntimeStageEvidence("branch_guard",RuntimeStageStatus.BLOCKED,"branch_guard","Named branch authorization failed"))
        return RejectedResult(message,"branch_guard")
    }

    private fun restoreFailed(failure:Throwable) {
        val category=if (failure is FileEditApplyException && failure.originalFailure is EditAuthorizationException) {
            "branch_guard"
        } else editFailureCategory(failure)
        stageObserver.record(RuntimeStageEvidence("restore",RuntimeStageStatus.BLOCKED,category,"Recovery transaction failed"))
    }

    private fun requireAuthorizedBranchAtWriteBoundary() {
        if (!isAuthorizedBranchCurrent()) throw EditAuthorizationException()
    }

    private fun FileEditApplyException.isRolledBackAuthorizationAbort():Boolean=
        originalFailure is EditAuthorizationException && rollbackFailures.isEmpty() && cleanupFailures.isEmpty()

    private fun currentNamedBranch():String?=try {
        val state=branchInspector(repositoryRoot)
        require(state.repositoryRoot==repositoryRoot) { "Git branch inspection escaped the selected repository" }
        when (state) {
            is GitBranchState.Detached -> null
            is GitBranchState.Named -> state.branch.also {
                require(it.isNotBlank()) { "Git branch inspection returned a blank branch" }
            }
        }
    } catch (failure:Throwable) {
        stageObserver.record(RuntimeStageEvidence("branch_guard",RuntimeStageStatus.BLOCKED,"branch_guard","Git branch inspection failed"))
        throw failure
    }
}
