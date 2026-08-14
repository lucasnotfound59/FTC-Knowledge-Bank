package org.ftckb.agent

import org.ftckb.domain.KnowledgeRule
import org.ftckb.model.TokenUsage

data class RetrievalIntent(
    val concepts:Set<String>,val symbols:Set<String>,val pathGlobs:Set<String>,
    val ruleTopics:Set<String>,val guideTopics:Set<String>
) {
    init {
        listOf(concepts,symbols,pathGlobs,ruleTopics,guideTopics).forEach(::validateValues)
        pathGlobs.forEach(::validateGlob)
    }

    private fun validateValues(values:Set<String>) {
        require(values.size<=12) { "retrieval intent arrays may contain at most 12 values" }
        require(values.all { it.length in 1..120 }) { "retrieval intent values must be 1..120 characters" }
    }

    private fun validateGlob(glob:String) {
        require(!glob.startsWith('/') && '\\' !in glob && ".." !in glob && !windowsRoot.containsMatchIn(glob)) {
            "pathGlobs must be repository-relative patterns"
        }
    }

    private companion object {
        val windowsRoot=Regex("^[A-Za-z]:")
    }
}

data class PlanningInput(
    val question:String,val recentSummary:String?,val recentReferences:Set<String>,
    val repositorySummary:String
)

sealed interface EvidenceItem { val id:String }

data class CodeEvidence(
    override val id:String,val path:String,val startLine:Int,val endLine:Int,
    val sha256:String,val text:String
):EvidenceItem

data class RuleEvidenceItem(override val id:String,val rule:KnowledgeRule):EvidenceItem

data class GuideEvidence(
    override val id:String,val path:String,val heading:String,val text:String
):EvidenceItem

data class ContextPack(val evidence:List<EvidenceItem>,val estimatedCharacters:Int)

data class AnswerInput(val question:String,val priorContext:String?,val context:ContextPack)

enum class ClaimKind { APPROVED_RULE,CODE_OBSERVATION,MODEL_INFERENCE,INSUFFICIENT_EVIDENCE }

data class AnswerClaim(val kind:ClaimKind,val text:String,val citations:List<String>)

data class AgentAnswer(val claims:List<AnswerClaim>,val usage:TokenUsage?)

class CitationValidationException(message:String):RuntimeException(message)
