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
data class RejectedResult(val message:String):SessionResult

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

    val mode:AgentMode
        @Synchronized get()=currentMode

    @Synchronized
    fun setMode(requested:AgentMode):RejectedResult? {
        if (requested==AgentMode.EDIT && !hasNamedCurrentBranch()) {
            return RejectedResult("Edit requires a Git worktree with a named current branch")
        }
        currentMode=requested
        return null
    }

    @Synchronized
    fun submit(message:String):SessionResult=when (currentMode) {
        AgentMode.ASK -> AskResult(askAgent.ask(message))
        AgentMode.EDIT -> {
            if (!hasNamedCurrentBranch()) {
                RejectedResult("Edit requires a Git worktree with a named current branch")
            } else {
                try {
                    EditResult(editAgent.edit(message))
                } catch (_:EditValidationException) {
                    RejectedResult("Edit plan validation failed; no files were written")
                }
            }
        }
    }

    @Synchronized
    fun undo():HistoryResult=history.undo().also(::refreshAfterHistory)

    @Synchronized
    fun discard():HistoryResult=history.discard().also(::refreshAfterHistory)

    @Synchronized
    fun changes():List<TextChange> =history.changes()

    @Synchronized
    fun diff():String=AgentDiffRenderer.render(history.changes())

    private fun refreshAfterHistory(result:HistoryResult) {
        if (result.succeeded && result.changedPaths.isNotEmpty()) repositoryIndex.refresh(result.changedPaths)
    }

    private fun hasNamedCurrentBranch():Boolean=runCatching {
        val workspace=workspaceInspector(repositoryRoot)
        workspace.repositoryRoot==repositoryRoot && !workspace.detached && !workspace.branch.isNullOrBlank()
    }.getOrDefault(false)
}
