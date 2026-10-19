package org.ftckb.knowledge

import java.nio.file.Path
import org.ftckb.domain.*

data class KnowledgeSourceError(val path:String, val message:String)
data class RuleSource(val path:String, val fileDigest:String, val ruleYaml:String)
data class KnowledgeSnapshot(
    val root:Path, val version:String, val rules:List<KnowledgeRule>,
    val violations:List<RuleViolation>, val loadErrors:List<KnowledgeSourceError>,
    val sources:Map<String,RuleSource>, val fileDigests:Map<String,String>
) { val valid:Boolean get() = violations.isEmpty() && loadErrors.isEmpty() }
enum class RuleMutationAction { CREATE, EDIT, APPROVE, REJECT }
data class RuleMutationRequest(
    val action:RuleMutationAction, val ruleId:String, val expectedVersion:String,
    val ruleYaml:String?=null, val approver:Approver?=null, val context:RuleContext?=null
)
data class RuleMutationPreview(
    val previewId:String, val action:RuleMutationAction, val ruleId:String,
    val baseVersion:String, val targetPath:String, val beforeYaml:String?, val afterYaml:String,
    val violations:List<RuleViolation>, val loadErrors:List<KnowledgeSourceError>,
    val resolution:ResolutionResult?, val approvalReset:Boolean, val canApply:Boolean
)
data class RuleMutationResult(val ruleId:String, val version:String, val action:RuleMutationAction)
class KnowledgeMutationException(val code:String, message:String):RuntimeException(message)
