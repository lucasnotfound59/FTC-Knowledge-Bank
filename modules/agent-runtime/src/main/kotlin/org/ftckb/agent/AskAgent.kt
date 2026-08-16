package org.ftckb.agent

class AskAgent(
    private val retrievalPlanner:RetrievalPlanner,
    private val contextRetriever:ContextRetriever,
    private val answerGenerator:AnswerGenerator,
    val conversation:ConversationState,
    private val repositorySummary:String
) {
    fun ask(question:String):AgentAnswer {
        require(question.isNotBlank()) { "question must not be blank" }
        val safeQuestion=conversation.redactForPrompt(question)
        val conversationContext=conversation.context()
        val submission=conversation.submit(safeQuestion)
        val intent=retrievalPlanner.plan(PlanningInput(
            safeQuestion,conversationContext.rollingSummary,conversationContext.recentReferences,
            repositorySummary,conversationContext.pendingQuestions
        ))
        val retrieved=contextRetriever.retrieve(intent)
        val answer=answerGenerator.generate(AnswerInput(safeQuestion,priorContext(conversationContext),retrieved))
        conversation.record(submission,answer,referencedIds(answer,retrieved))
        return answer
    }

    private fun priorContext(context:ConversationContext):String? {
        if (context.rollingSummary==null && context.recentTurns.isEmpty() && context.pendingQuestions.isEmpty()) return null
        return buildString {
            context.rollingSummary?.let { append("Untrusted rolling summary:\n").append(it).append('\n') }
            if (context.recentTurns.isNotEmpty()) {
                append("Recent turns:\n")
                context.recentTurns.forEach { append(conversation.renderTurnForPrompt(it)) }
            }
            if (context.pendingQuestions.isNotEmpty()) {
                append("Pending user turns without validated assistant answers:\n")
                context.pendingQuestions.forEach { append("User: ").append(it).append('\n') }
            }
        }
    }

    private fun referencedIds(answer:AgentAnswer,context:ContextPack):Set<String> {
        val evidence=context.evidence.associateBy { it.id }
        return answer.claims.flatMapTo(LinkedHashSet()) { claim ->
            claim.citations.mapNotNull { citation ->
                when (val item=evidence[citation]) {
                    is CodeEvidence -> item.path
                    is RuleEvidenceItem -> item.rule.id
                    is GuideEvidence -> item.path
                    null -> null
                }
            }
        }
    }
}
