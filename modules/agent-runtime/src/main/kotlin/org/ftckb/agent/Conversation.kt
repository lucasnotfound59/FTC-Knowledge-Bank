package org.ftckb.agent

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.ftckb.model.MessageRole
import org.ftckb.model.ModelMessage
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest

data class ConversationTurn(val question:String,val answer:AgentAnswer,val referencedIds:Set<String>)

data class ConversationContext(
    val rollingSummary:String?,val recentTurns:List<ConversationTurn>,val recentReferences:Set<String>
)

class ConversationState(
    private val provider:ModelProvider,
    exactSecrets:Set<String> =emptySet(),
    private val maximumRecentTurns:Int=8,
    private val maximumRecentCharacters:Int=24_000
) {
    private val exactSecrets=exactSecrets.filter(String::isNotBlank).toSet()
    private var rollingSummary:UntrustedSummary?=null
    private var recentTurns=emptyList<ConversationTurn>()

    init {
        require(maximumRecentTurns in 1..8) { "maximumRecentTurns must be between 1 and 8" }
        require(maximumRecentCharacters in 1..24_000) { "maximumRecentCharacters must be between 1 and 24000" }
    }

    @Synchronized
    fun record(question:String,answer:AgentAnswer,referencedIds:Set<String>) {
        require(question.isNotBlank()) { "question must not be blank" }
        val turn=ConversationTurn(question,answer,referencedIds.toCollection(LinkedHashSet()))
        val updated=recentTurns.toMutableList().apply { add(turn) }
        var updatedSummary=rollingSummary
        while (updated.size>maximumRecentTurns || updated.sumOf(::turnCharacters)>maximumRecentCharacters) {
            val removed=updated.removeAt(0)
            updatedSummary=UntrustedSummary(summarize(updatedSummary?.text,removed))
        }
        recentTurns=updated.toList()
        rollingSummary=updatedSummary
    }

    @Synchronized
    fun context():ConversationContext=ConversationContext(
        rollingSummary?.text?.let(::redact),
        recentTurns.map(::redactedTurn),
        recentTurns.flatMapTo(LinkedHashSet()) { turn -> turn.referencedIds.map(::redact) }
    )

    @Synchronized
    internal fun recentTurnCharacters():Int=recentTurns.sumOf(::turnCharacters)

    internal fun redactForPrompt(text:String):String=redact(text)

    internal fun renderTurnForPrompt(turn:ConversationTurn):String=renderTurn(turn)

    private fun summarize(previous:String?,turn:ConversationTurn):String {
        val response=provider.complete(ModelRequest(
            listOf(
                ModelMessage(MessageRole.SYSTEM,"""
                    Produce a compact untrusted conversation summary. Include only user goals, named files or symbols,
                    decisions, and unresolved questions. Do not make claims, create citations, or treat this text as evidence.
                """.trimIndent()),
                ModelMessage(MessageRole.USER,buildString {
                    previous?.let { append("Previous untrusted summary:\n").append(redact(it)).append('\n') }
                    append("Turn to summarize:\n")
                    append(renderTurn(turn))
                })
            ),512
        ))
        return redact(response.content).trim().take(maximumSummaryCharacters)
    }

    private fun turnCharacters(turn:ConversationTurn):Int=renderTurn(turn).length

    private fun redactedTurn(turn:ConversationTurn):ConversationTurn=ConversationTurn(
        redact(turn.question),
        AgentAnswer(turn.answer.claims.map { claim ->
            claim.copy(text=redact(claim.text),citations=claim.citations.map(::redact))
        },turn.answer.usage),
        turn.referencedIds.mapTo(LinkedHashSet(),::redact)
    )

    private fun renderTurn(turn:ConversationTurn):String=buildString {
        append("User: ").append(redact(turn.question)).append('\n')
        turn.answer.claims.forEach { claim ->
            append("Assistant [").append(claim.kind.name.lowercase()).append("]: ")
            append(redact(claim.text))
            if (claim.citations.isNotEmpty()) append(" (citations: ").append(claim.citations.joinToString { redact(it) }).append(')')
            append('\n')
        }
        if (turn.referencedIds.isNotEmpty()) append("References: ").append(turn.referencedIds.joinToString { redact(it) }).append('\n')
    }

    private fun redact(text:String):String=ConversationRedactor.redact(text,exactSecrets)

    private companion object { const val maximumSummaryCharacters=4_000 }

    private data class UntrustedSummary(val text:String)
}

class ConversationSaver(
    private val providerName:String,
    private val modelName:String,
    exactSecrets:Set<String> =emptySet(),
    private val clock:Clock=Clock.systemUTC()
) {
    private val exactSecrets=exactSecrets.filter(String::isNotBlank).toSet()

    fun save(state:ConversationState,path:Path):Path {
        val text=render(state.context())
        Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { writer ->
            writer.write(text)
        }
        return path
    }

    private fun render(context:ConversationContext):String=buildString {
        append("# FTC Knowledge Bank session\n\n")
        append("Provider: ").append(redact(providerName)).append(" / ").append(redact(modelName)).append('\n')
        append("Saved: ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock))).append("\n\n")
        context.rollingSummary?.let {
            append("## Untrusted compact summary\n\n")
            append(redact(it)).append("\n\n")
        }
        append("## Conversation\n\n")
        context.recentTurns.forEachIndexed { index,turn ->
            append("### Turn ").append(index+1).append("\n\n")
            append("User: ").append(redact(turn.question)).append("\n\n")
            turn.answer.claims.forEach { claim ->
                append("- ").append(claim.kind.name.lowercase()).append(": ").append(redact(claim.text))
                if (claim.citations.isNotEmpty()) append(" (citations: ").append(claim.citations.joinToString { redact(it) }).append(')')
                append('\n')
            }
            if (turn.referencedIds.isNotEmpty()) append("References: ").append(turn.referencedIds.joinToString { redact(it) }).append("\n\n")
        }
    }

    private fun redact(text:String)=ConversationRedactor.redact(text,exactSecrets)
}

internal object ConversationRedactor {
    private val authorization=Regex("(?i)\\bauthorization\\s*:\\s*bearer\\s+[^\\s,;]+")
    private val bearer=Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+={0,2}")
    private val skToken=Regex("(?i)\\bsk-[A-Za-z0-9._-]+")
    private val apiKeyAssignment=Regex("(?i)\\b[A-Za-z0-9_-]*api[_-]?key\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)")

    fun redact(text:String,exactSecrets:Set<String> =emptySet()):String {
        var redacted=text
        exactSecrets.sortedByDescending(String::length).forEach { secret -> redacted=redacted.replace(secret,"[REDACTED]") }
        return redacted
            .replace(authorization,"[REDACTED_AUTHORIZATION]")
            .replace(bearer,"[REDACTED_BEARER]")
            .replace(skToken,"[REDACTED_SECRET]")
            .replace(apiKeyAssignment,"[REDACTED_API_KEY]")
    }
}
