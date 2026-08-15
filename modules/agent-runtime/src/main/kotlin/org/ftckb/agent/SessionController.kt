package org.ftckb.agent

import java.nio.file.Path
import org.ftckb.agent.edit.EditAgent
import org.ftckb.agent.edit.EditHistory
import org.ftckb.agent.edit.EditReport
import org.ftckb.agent.edit.EditValidationException
import org.ftckb.agent.edit.HistoryResult
import org.ftckb.git.AgentDiffRenderer
import org.ftckb.git.GitWorkspace
import org.ftckb.git.GitWorkspaceState
import org.ftckb.git.TextChange
import org.ftckb.repository.RepositoryIndex

enum class AgentMode { ASK,EDIT }

sealed interface SessionResult
data class AskResult(val answer:AgentAnswer):SessionResult
data class EditResult(val report:EditReport):SessionResult
sealed interface HistoryCommandResult
data class HistoryAppliedResult(val result:HistoryResult):HistoryCommandResult
data class RejectedResult(val message:String):SessionResult,HistoryCommandResult

private class EditAuthorizationException:RuntimeException()

class SessionController(
    private val askAgent:AskAgent,
    private val editAgent:EditAgent,
    private val history:EditHistory,
    repositoryRoot:Path,
    private val repositoryIndex:RepositoryIndex,
    private val workspaceInspector:(Path)->GitWorkspaceState=GitWorkspace::inspect
) {
    private val repositoryRoot=repositoryRoot.toRealPath()
    private var currentMode=AgentMode.ASK
    private var authorizedBranch:String?=null

    val mode:AgentMode
        @Synchronized get()=currentMode

    @Synchronized
    fun setMode(requested:AgentMode):RejectedResult? {
        if (requested==AgentMode.EDIT) {
            val branch=currentNamedBranch()
                ?:return RejectedResult("Edit requires a Git worktree with a named current branch")
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
                    EditResult(editAgent.edit(message,::requireAuthorizedBranchAtWriteBoundary))
                } catch (_:EditAuthorizationException) {
                    RejectedResult("Edit requires the authorized named current branch")
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
        return HistoryAppliedResult(history.undo().also(::refreshAfterHistory))
    }

    @Synchronized
    fun discard():HistoryCommandResult {
        if (currentMode!=AgentMode.EDIT) return RejectedResult("Discard requires Edit mode")
        if (!isAuthorizedBranchCurrent()) return RejectedResult("Discard requires the authorized named current branch")
        return HistoryAppliedResult(history.discard().also(::refreshAfterHistory))
    }

    @Synchronized
    fun changes():List<TextChange> =history.changes()

    @Synchronized
    fun diff():String=AgentDiffRenderer.render(history.changes())

    private fun refreshAfterHistory(result:HistoryResult) {
        if (result.succeeded && result.changedPaths.isNotEmpty()) repositoryIndex.refresh(result.changedPaths)
    }

    private fun isAuthorizedBranchCurrent():Boolean=currentNamedBranch()==authorizedBranch

    private fun requireAuthorizedBranchAtWriteBoundary() {
        if (!isAuthorizedBranchCurrent()) throw EditAuthorizationException()
    }

    private fun currentNamedBranch():String?=runCatching {
        val workspace=workspaceInspector(repositoryRoot)
        workspace.branch.takeIf {
            workspace.repositoryRoot==repositoryRoot && !workspace.detached && !it.isNullOrBlank()
        }
    }.getOrNull()
}
