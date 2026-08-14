package org.ftckb.agent

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
                Each array is host-validated. Do not request files or tools.
            """.trimIndent()),
            ModelMessage(MessageRole.USER,buildString {
                append("Question: ").append(input.question).append('\n')
                input.recentSummary?.let { append("Recent summary: ").append(it).append('\n') }
                if (input.recentReferences.isNotEmpty()) append("Recent references: ").append(input.recentReferences.joinToString()).append('\n')
                append("Repository summary: ").append(input.repositorySummary)
                issue?.let { append("\nRepair the previous response: ").append(it) }
            })
        ),512
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
        val terms=token.findAll(input.question+" "+input.recentReferences.joinToString(" "))
            .map { it.value.replaceFirstChar(Char::lowercase) }
            .filter { it.lowercase() !in stopWords }
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
        val token=Regex("[A-Za-z_][A-Za-z0-9_]*")
        val stopWords=setOf("a","an","and","are","does","for","how","in","is","of","the","to","use","what","with")
    }
}
