package org.ftckb.agent

import java.text.Normalizer
import java.util.Locale
import org.ftckb.model.MessageRole
import org.ftckb.model.ModelMessage
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest

class RetrievalPlanner(private val provider:ModelProvider) {
    fun plan(input:PlanningInput):RetrievalIntent {
        var issue:String?=null
        repeat(2) { attempt ->
            try {
                return decode(provider.complete(request(input,issue)).content)
            } catch (error:IllegalArgumentException) {
                issue=error.message ?: "invalid retrieval JSON"
                if (attempt==1) return fallback(input)
            }
        }
        return fallback(input)
    }

    private fun request(input:PlanningInput,issue:String?):ModelRequest=ModelRequest(
        listOf(
            ModelMessage(MessageRole.SYSTEM,"""
                Return exactly one JSON object with the arrays concepts, symbols, pathGlobs, ruleTopics, and guideTopics.
                symbols must list every concrete class, method, or identifier mentioned in the question; pathGlobs should name likely source files; concepts holds only general topic words; ruleTopics names rule topics relevant to the question.
                Each array is host-validated. Do not request files or tools.
            """.trimIndent()),
            ModelMessage(MessageRole.USER,buildString {
                append("Question: ").append(input.question).append('\n')
                input.recentSummary?.let { append("Recent summary: ").append(it).append('\n') }
                if (input.recentReferences.isNotEmpty()) append("Recent references: ").append(input.recentReferences.joinToString()).append('\n')
                if (input.pendingQuestions.isNotEmpty()) {
                    append("Pending user turns without validated answers: ").append(input.pendingQuestions.joinToString()).append('\n')
                }
                append("Repository summary: ").append(ContextSafety.wrapUntrusted("REPOSITORY",input.repositorySummary))
                issue?.let { append("\nRepair the previous response: ").append(it) }
            })
        ),4096
    )

    private fun decode(text:String):RetrievalIntent {
        val node=ModelJson.objectNode(text)
        val names=setOf("concepts","symbols","pathGlobs","ruleTopics","guideTopics")
        ModelJson.requireFields(node,names,"retrieval intent")
        return RetrievalIntent(
            ModelJson.stringArray(node["concepts"],"concepts").toLinkedSet(),
            ModelJson.stringArray(node["symbols"],"symbols").toLinkedSet(),
            ModelJson.stringArray(node["pathGlobs"],"pathGlobs").toLinkedSet(),
            ModelJson.stringArray(node["ruleTopics"],"ruleTopics").toLinkedSet(),
            ModelJson.stringArray(node["guideTopics"],"guideTopics").toLinkedSet()
        )
    }

    private fun fallback(input:PlanningInput):RetrievalIntent {
        val source=Normalizer.normalize(
            input.question+" "+input.recentReferences.joinToString(" ")+" "+input.pendingQuestions.joinToString(" "),
            Normalizer.Form.NFKC
        )
        val terms=token.findAll(source)
            .map { Normalizer.normalize(it.value,Normalizer.Form.NFKC).replaceFirstChar { character -> character.lowercase(Locale.ROOT) } }
            .filter { it.lowercase(Locale.ROOT) !in stopWords }
            .distinct()
            .take(12)
            .toList()
            .toLinkedSet()
        return RetrievalIntent(terms,emptySet(),emptySet(),emptySet(),emptySet())
    }

    private fun List<String>.toLinkedSet():Set<String> {
        return LinkedHashSet(this)
    }

    private companion object {
        val token=Regex("[\\p{L}\\p{N}_]+")
        val stopWords=setOf("a","an","and","are","does","for","how","in","is","of","the","to","use","what","with")
    }
}
