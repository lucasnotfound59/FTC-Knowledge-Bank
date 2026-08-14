package org.ftckb.cli

import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Path
import org.ftckb.agent.AgentAnswer
import org.ftckb.agent.CitationValidationException
import org.ftckb.agent.ClaimKind
import org.ftckb.model.ModelProviderException

class ChatRepl(
    private val session:AskChatSession,
    private val input:BufferedReader,
    private val out:PrintStream
) {
    fun run():Int {
        out.println("FTC Ask chat ready. Type /help for commands.")
        while (true) {
            val line=input.readLine() ?:return 0
            if (line.isBlank()) continue
            when {
                line=="/exit" -> return 0
                line=="/help" -> printHelp()
                line=="/mode ask" -> out.println("mode=ask")
                line=="/mode edit" || line in unsupportedCommands -> out.println("not available in Ask Core")
                line=="/status" -> printStatus()
                line=="/save" -> save(null)
                line.startsWith("/save ") -> save(line.removePrefix("/save "))
                line.startsWith("/") -> out.println("unknown command: $line")
                else -> ask(line)
            }
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
        out.println("mode=ask")
    }

    private fun save(path:String?) {
        try {
            out.println("saved=${session.save(path?.let(Path::of))}")
        } catch (_:Exception) {
            out.println("save error: unable to save session")
        }
    }

    private fun printHelp() {
        out.println("commands: /help /mode ask /status /save [path] /exit")
        out.println("Ask Core is read-only; edit commands are unavailable.")
    }

    private companion object {
        val unsupportedCommands=setOf("/undo","/discard","/diff","/commit")
    }
}
