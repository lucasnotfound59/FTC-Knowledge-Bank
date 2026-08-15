package org.ftckb.cli

import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Path
import org.ftckb.agent.AgentAnswer
import org.ftckb.agent.AgentMode
import org.ftckb.agent.AskResult
import org.ftckb.agent.CitationValidationException
import org.ftckb.agent.ClaimKind
import org.ftckb.agent.CredentialRedactor
import org.ftckb.agent.EditResult
import org.ftckb.agent.HistoryAppliedResult
import org.ftckb.agent.RejectedResult
import org.ftckb.agent.SessionController
import org.ftckb.agent.edit.EditReport
import org.ftckb.git.CommitRequest
import org.ftckb.git.GitBranchState
import org.ftckb.git.GitCommitService
import org.ftckb.git.GitWorkspace
import org.ftckb.model.ModelProviderException

class ChatRepl(
    private val session:AskChatSession,
    private val input:BufferedReader,
    private val out:PrintStream,
    private val controller:SessionController?,
    private val repositoryRoot:Path?,
    private val baselineDirtyPaths:Set<String>?,
    private val sanitize:(String)->String
) {
    constructor(session:AskChatSession,input:BufferedReader,out:PrintStream):this(
        session,input,out,null,null,emptySet(),CredentialRedactor::redact
    )

    fun run():Int {
        out.println("FTC Agent chat ready. Type /help for commands.")
        while (true) {
            val line=input.readLine() ?:return 0
            if (line.isBlank()) continue
            when {
                line=="/exit" -> return 0
                line=="/help" -> printHelp()
                line=="/mode ask" -> setMode(AgentMode.ASK)
                line=="/mode edit" -> setMode(AgentMode.EDIT)
                line=="/undo" -> historyCommand(undo=true)
                line=="/discard" -> historyCommand(undo=false)
                line=="/diff" -> printDiff()
                line=="/commit" -> commit()
                line=="/status" -> printStatus()
                line=="/save" -> save(null)
                line.startsWith("/save ") -> save(line.removePrefix("/save "))
                line.startsWith("/") -> out.println("unknown command: $line")
                else -> submit(line)
            }
        }
    }

    private fun setMode(mode:AgentMode) {
        val active=controller
        if (active==null) {
            if (mode==AgentMode.ASK) out.println("mode=ask") else out.println("not available in Ask Core")
            return
        }
        try {
            val rejection=active.setMode(mode)
            if (rejection!=null) {
                out.println("mode refused: ${safeLine(rejection.message)}")
                return
            }
            out.println("mode=${mode.name.lowercase()}")
        } catch (_:Exception) {
            runCatching { active.setMode(AgentMode.ASK) }
            out.println("mode refused: Edit requires a readable Git worktree with a named current branch")
        }
    }

    private fun submit(message:String) {
        val active=controller
        if (active==null || active.mode==AgentMode.ASK) {
            ask(message)
            return
        }
        try {
            when (val result=active.submit(message)) {
                is EditResult -> render(result.report)
                is RejectedResult -> out.println("edit refused: ${safeLine(result.message)}")
                is AskResult -> render(result.answer)
            }
        } catch (_:ModelProviderException) {
            out.println("model provider error: request failed")
        } catch (_:Exception) {
            out.println("edit error: request did not complete")
        }
    }

    private fun ask(question:String) {
        try {
            render(session.ask(question))
        } catch (_:ModelProviderException) {
            out.println("model provider error: request failed")
        } catch (_:CitationValidationException) {
            out.println("citation validation error: response citations are invalid")
        } catch (_:AskChatSessionException.RepositoryRead) {
            out.println("repository error: local repository is unavailable")
        } catch (_:AskChatSessionException.KnowledgeRead) {
            out.println("knowledge error: local knowledge is unavailable")
        }
    }

    private fun render(answer:AgentAnswer) {
        answer.claims.forEach { claim ->
            out.print(label(claim.kind))
            claim.citations.forEach { citation -> out.print(" [$citation]") }
            out.println(": ${claim.text}")
        }
    }

    private fun render(report:EditReport) {
        out.println("edit=ok")
        out.println("summary: ${safeLine(report.summary)}")
        printPaths("paths",report.changedPaths.sorted())
        printValues("reasons",report.reasons,MAX_REASONS)
        printValues("citations",report.citations.sorted(),MAX_CITATIONS)
        if (report.warnings.isNotEmpty()) printValues("warnings",report.warnings,MAX_WARNINGS)
        if (report.projectLevelPaths.isNotEmpty()) {
            out.println("warning: project-level changes require extra review")
            printPaths("project-level paths",report.projectLevelPaths.sorted())
        }
        out.println("Agent diff:")
        out.print(safeBlock(report.diff))
        if (!report.diff.endsWith('\n')) out.println()
    }

    private fun label(kind:ClaimKind):String=when (kind) {
        ClaimKind.APPROVED_RULE -> "已批准规则"
        ClaimKind.CODE_OBSERVATION -> "代码观察"
        ClaimKind.MODEL_INFERENCE -> "模型推断"
        ClaimKind.INSUFFICIENT_EVIDENCE -> "证据不足"
    }

    private fun printStatus() {
        val status=session.status()
        out.println("repository=${status.repository}")
        out.println("team=${status.team} season=${status.season}")
        out.println("provider=${status.provider} model=${status.model}")
        out.println("mode=${controller?.mode?.name?.lowercase()?:"ask"}")
    }

    private fun historyCommand(undo:Boolean) {
        val active=controller
        if (active==null) {
            out.println("not available in Ask Core")
            return
        }
        try {
            when (val result=if (undo) active.undo() else active.discard()) {
                is RejectedResult -> out.println("${if (undo) "undo" else "discard"} refused: ${safeLine(result.message)}")
                is HistoryAppliedResult -> {
                    val name=if (undo) "undo" else "discard"
                    if (result.result.conflicts.isNotEmpty()) {
                        out.println("$name conflict; no files were overwritten")
                        printPaths("conflicts",result.result.conflicts.sorted())
                    } else {
                        out.println("$name=ok")
                        printPaths("paths",result.result.changedPaths.sorted())
                    }
                }
            }
        } catch (_:Exception) {
            out.println("${if (undo) "undo" else "discard"} error: request did not complete")
        }
    }

    private fun printDiff() {
        val active=controller
        if (active==null) {
            out.println("not available in Ask Core")
            return
        }
        try {
            val diff=active.diff()
            if (diff.isEmpty()) out.println("Agent diff: none")
            else {
                out.println("Agent diff:")
                out.print(safeBlock(diff))
                if (!diff.endsWith('\n')) out.println()
            }
        } catch (_:Exception) {
            out.println("diff error: unable to render Agent changes")
        }
    }

    private fun commit() {
        val active=controller
        val root=repositoryRoot
        if (active==null || root==null) {
            out.println("not available in Ask Core")
            return
        }
        val authorizedBranch=active.authorizedEditBranch
        if (authorizedBranch==null || currentNamedBranchOrNull()!=authorizedBranch) {
            out.println("commit refused: Edit mode on the authorized named branch is required")
            return
        }
        val changes=active.changes()
        val paths=changes.mapTo(sortedSetOf()) { it.path }
        if (paths.isEmpty()) {
            out.println("commit refused: no Agent changes")
            return
        }
        out.println("commit paths:")
        paths.forEach { out.println("- ${safePath(it)}") }
        out.println("commit message: $COMMIT_MESSAGE")
        val baseline=baselineDirtyPaths
        if (baseline==null) {
            out.println("commit refused: startup Git baseline is unavailable")
            return
        }
        val overlap=paths.intersect(baseline).toSortedSet()
        if (overlap.isNotEmpty()) {
            out.println("commit refused: Agent-touched paths were dirty at startup")
            printPaths("baseline-dirty paths",overlap)
            return
        }
        out.println("type yes to create this local commit:")
        if (input.readLine()!="yes") {
            out.println("commit canceled")
            return
        }
        if (currentNamedBranchOrNull()!=authorizedBranch) {
            out.println("commit refused: the authorized branch changed")
            return
        }
        try {
            val expected=changes.associate { it.path to it.after }
            val commit=GitCommitService.commit(
                CommitRequest(root,paths,baseline,COMMIT_MESSAGE,authorizedBranch,expected)
            )
            out.println("commit=$commit")
        } catch (_:Exception) {
            out.println("commit refused: repository state is not safe for an isolated local commit")
        }
    }

    private fun save(path:String?) {
        try {
            out.println("saved=${session.save(path?.let(Path::of))}")
        } catch (_:Exception) {
            out.println("save error: unable to save session")
        }
    }

    private fun printHelp() {
        if (controller==null) {
            out.println("commands: /help /mode ask /status /save [path] /exit")
            out.println("Ask Core is read-only; edit commands are unavailable.")
        } else {
            out.println("commands: /help /mode ask|edit /undo /discard /diff /commit /status /save [path] /exit")
            out.println("Edit writes validated text changes on the current named branch; commit is local-only.")
        }
    }

    private fun printValues(label:String,values:Collection<String>,limit:Int) {
        out.println("$label:")
        values.take(limit).forEach { out.println("- ${safeLine(it)}") }
        if (values.size>limit) out.println("- ... ${values.size-limit} more")
    }

    private fun printPaths(label:String,paths:Collection<String>) {
        out.println("$label:")
        paths.take(MAX_PATHS).forEach { out.println("- ${safePath(it)}") }
        if (paths.size>MAX_PATHS) out.println("- ... ${paths.size-MAX_PATHS} more")
    }

    private fun safeLine(value:String):String=safeCharacters(value,multiline=false)
        .take(MAX_LINE_CHARACTERS)

    private fun safePath(value:String):String=safeCharacters(value,multiline=false)
        .take(MAX_PATH_CHARACTERS)

    private fun safeBlock(value:String):String {
        val sanitized=safeCharacters(value,multiline=true)
        val clean=sanitized.take(MAX_DIFF_CHARACTERS)
        return if (clean.length<sanitized.length) clean+"\n... diff truncated\n" else clean
    }

    private fun safeCharacters(value:String,multiline:Boolean):String=sanitize(value)
        .map { character->
            when {
                multiline && (character=='\n'||character=='\t') -> character
                character.isISOControl()||Character.getType(character)==Character.FORMAT.toInt() -> ' '
                else -> character
            }
        }
        .joinToString("")

    private fun currentNamedBranch():String {
        return when (val state=GitWorkspace.currentBranch(requireNotNull(repositoryRoot))) {
            is GitBranchState.Named -> state.branch
            is GitBranchState.Detached -> error("detached HEAD")
        }
    }

    private fun currentNamedBranchOrNull():String?=runCatching { currentNamedBranch() }.getOrNull()

    private companion object {
        const val COMMIT_MESSAGE="chore: apply FTC Agent edits"
        const val MAX_LINE_CHARACTERS=500
        const val MAX_PATH_CHARACTERS=512
        const val MAX_DIFF_CHARACTERS=20_000
        const val MAX_PATHS=24
        const val MAX_REASONS=24
        const val MAX_CITATIONS=64
        const val MAX_WARNINGS=4
    }
}
