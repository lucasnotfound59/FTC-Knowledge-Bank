package org.ftckb.agent

import org.ftckb.repository.LocalQuery
import org.ftckb.repository.RepositoryIndex

class ContextRetriever(
    private val repositoryIndex:RepositoryIndex,
    private val knowledgeRetriever:KnowledgeRetriever,
    private val maximumCharacters:Int=48_000
) {
    init { require(maximumCharacters in 1..48_000) { "maximum context size must be 1..48000" } }

    fun retrieve(intent:RetrievalIntent):ContextPack {
        val evidence=mutableListOf<EvidenceItem>()
        var characters=0
        var codeNumber=1
        repositoryIndex.search(LocalQuery(intent.concepts,intent.symbols,intent.pathGlobs),48).forEach { fragment ->
            if (characters+fragment.text.length<=maximumCharacters) {
                evidence+=CodeEvidence("CODE:C${codeNumber++}",fragment.path,fragment.startLine,fragment.endLine,fragment.sha256,fragment.text)
                characters+=fragment.text.length
            }
        }
        var ruleNumber=1
        knowledgeRetriever.retrieveRules(intent).sortedBy { it.id }.forEach { rule ->
            val item=RuleEvidenceItem("RULE:R${ruleNumber}",rule)
            val length=ruleLength(item)
            if (characters+length<=maximumCharacters) {
                evidence+=item
                characters+=length
                ruleNumber++
            }
        }
        var guideNumber=1
        knowledgeRetriever.retrieveGuides(intent).sortedWith(compareBy({ it.path },{ it.heading })).forEach { guide ->
            val item=guide.copy(id="GUIDE:G${guideNumber}")
            if (characters+item.text.length<=maximumCharacters) {
                evidence+=item
                characters+=item.text.length
                guideNumber++
            }
        }
        return ContextPack(evidence,characters)
    }

    private fun ruleLength(item:RuleEvidenceItem)=item.rule.instruction.length+item.rule.rationale.length+item.rule.title.length
}
