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
        val rules=knowledgeRetriever.retrieveRules(intent).sortedBy { it.id }
        val requestedRules=rules.filter { rule ->
            intent.ruleTopics.any { topic -> topic.equals(rule.topic,ignoreCase=true) }
        }
        val reservedRules=mutableListOf<RuleEvidenceItem>()
        var reservedCharacters=0
        var ruleNumber=1
        requestedRules.forEach { rule ->
            val item=RuleEvidenceItem("RULE:R$ruleNumber",rule)
            val length=EvidenceSerialization.block(item).length
            if (reservedCharacters+length<=maximumCharacters) {
                reservedRules+=item
                reservedCharacters+=length
                ruleNumber++
            }
        }
        var codeNumber=1
        val fragments=repositoryIndex.search(LocalQuery(intent.concepts,intent.symbols,intent.pathGlobs),48).toMutableList()
        if (fragments.isEmpty()) {
            // Deterministic fallback: the planned retrieval matched no source lines.
            // Include a bounded slice of the repository sources so location/absence
            // questions (e.g. "where is X configured?") can still be answered with a
            // code observation instead of degrading to insufficient evidence.
            fragments+=repositoryIndex.search(LocalQuery(emptySet(),emptySet(),setOf("**/*.java","**/*.kt")),48)
        }
        fragments.forEach { fragment ->
            val item=CodeEvidence("CODE:C$codeNumber",fragment.path,fragment.startLine,fragment.endLine,fragment.sha256,fragment.text)
            val length=EvidenceSerialization.block(item).length
            if (characters+length<=maximumCharacters-reservedCharacters) {
                evidence+=item
                characters+=length
                codeNumber++
            }
        }
        evidence+=reservedRules
        characters+=reservedCharacters
        val requestedRuleIds=requestedRules.mapTo(HashSet()) { it.id }
        rules.filterNot { it.id in requestedRuleIds }.forEach { rule ->
            val item=RuleEvidenceItem("RULE:R${ruleNumber}",rule)
            val length=EvidenceSerialization.block(item).length
            if (characters+length<=maximumCharacters) {
                evidence+=item
                characters+=length
                ruleNumber++
            }
        }
        var guideNumber=1
        knowledgeRetriever.retrieveGuides(intent).sortedWith(compareBy({ it.path },{ it.heading })).forEach { guide ->
            val item=guide.copy(id="GUIDE:G${guideNumber}")
            val length=EvidenceSerialization.block(item).length
            if (characters+length<=maximumCharacters) {
                evidence+=item
                characters+=length
                guideNumber++
            }
        }
        return ContextPack(evidence,characters)
    }
}
