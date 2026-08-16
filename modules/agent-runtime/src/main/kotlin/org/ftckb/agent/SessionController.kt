package org.ftckb.agent

import java.nio.file.Path
import org.ftckb.agent.edit.EditAgent
import org.ftckb.agent.edit.EditHistory
import org.ftckb.agent.edit.EditReport
import org.ftckb.agent.edit.EditValidationException
import org.ftckb.agent.edit.FileEditApplyException
import org.ftckb.agent.edit.HistoryResult
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
data class RejectedResult(val message:String):SessionResult,HistoryCommandResult

private class EditAuthorizationException:RuntimeException()

class SessionController(
    private val askAgent:AskAgent,
    private val editAgent:EditAgent,
    private val history:EditHistory,
    repositoryRoot:Path,
    private val repositoryIndex:RepositoryIndex,
    private val branchInspector:(Path)->GitBranchState=GitWorkspace::currentBranch,
    private val indexRefresher:(Set<String>)->Unit=repositoryIndex::refresh
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

    @Synchronized
    fun setMode(requested:AgentMode):RejectedResult? {
        if (requested==AgentMode.EDIT) {
            val branch=currentNamedBranch()
                ?:return RejectedResult("Edit requires a Git worktree with a named current branch")
            if (historyBranch!=null && branch!=historyBranch) {
                return RejectedResult("Edit history belongs to another branch")
            }
            authorizedBranch=branch
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
                RejectedResult("Edit requires the authorized named current branch")
            } else {
                try {
                    val branch=authorizedBranch
                    try {
                        EditResult(editAgent.edit(message,::requireAuthorizedBranchAtWriteBoundary))
                    } finally {
                        if (history.hasTrackedPaths) historyBranch=branch
                    }
                } catch (_:EditAuthorizationException) {
                    RejectedResult("Edit requires the authorized named current branch")
                } catch (failure:FileEditApplyException) {
                    if (failure.isRolledBackAuthorizationAbort()) {
                        RejectedResult("Edit requires the authorized named current branch")
                    } else {
                        throw failure
                    }
                } catch (_:EditValidationException) {
                    RejectedResult("Edit plan validation failed; no files were written")
                }
            }
        }
    }

    @Synchronized
    fun undo():HistoryCommandResult {
        if (currentMode!=AgentMode.EDIT) return RejectedResult("Undo requires Edit mode")
        if (!isAuthorizedBranchCurrent()) return RejectedResult("Undo requires the authorized named current branch")
        return try {
            historyResult(history.undo(::requireAuthorizedBranchAtWriteBoundary))
        } catch (failure:FileEditApplyException) {
            if (failure.isRolledBackAuthorizationAbort()) {
                RejectedResult("Undo requires the authorized named current branch")
            } else {
                throw failure
            }
        }
    }

    @Synchronized
    fun discard():HistoryCommandResult {
        if (currentMode!=AgentMode.EDIT) return RejectedResult("Discard requires Edit mode")
        if (!isAuthorizedBranchCurrent()) return RejectedResult("Discard requires the authorized named current branch")
        return try {
            historyResult(history.discard(::requireAuthorizedBranchAtWriteBoundary))
        } catch (failure:FileEditApplyException) {
            if (failure.isRolledBackAuthorizationAbort()) {
                RejectedResult("Discard requires the authorized named current branch")
            } else {
                throw failure
            }
        }
    }

    @Synchronized
    fun changes():List<TextChange> =history.changes()

    @Synchronized
    fun diff():String=AgentDiffRenderer.render(history.changes())

    private fun historyResult(result:HistoryResult):HistoryAppliedResult {
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

    private fun isAuthorizedBranchCurrent():Boolean=currentNamedBranch()==authorizedBranch

    private fun requireAuthorizedBranchAtWriteBoundary() {
        if (!isAuthorizedBranchCurrent()) throw EditAuthorizationException()
    }

    private fun FileEditApplyException.isRolledBackAuthorizationAbort():Boolean=
        originalFailure is EditAuthorizationException && rollbackFailures.isEmpty() && cleanupFailures.isEmpty()

    private fun currentNamedBranch():String? {
        val state=branchInspector(repositoryRoot)
        require(state.repositoryRoot==repositoryRoot) { "Git branch inspection escaped the selected repository" }
        return when (state) {
            is GitBranchState.Detached -> null
            is GitBranchState.Named -> state.branch.also {
                require(it.isNotBlank()) { "Git branch inspection returned a blank branch" }
            }
        }
    }
}
