package org.ftckb.agent.edit

data class EditPlan(val summary:String,val operations:List<EditOperation>)

sealed interface EditOperation {
    val reason:String
    val citations:List<String>
}

data class CreateText(
    val path:String,
    val expectedAbsent:Boolean,
    val content:String,
    override val reason:String,
    override val citations:List<String>
):EditOperation

data class ReplaceText(
    val path:String,
    val expectedSha256:String,
    val oldText:String,
    val newText:String,
    override val reason:String,
    override val citations:List<String>
):EditOperation

data class DeleteText(
    val path:String,
    val expectedSha256:String,
    override val reason:String,
    override val citations:List<String>
):EditOperation

data class MoveText(
    val sourcePath:String,
    val destinationPath:String,
    val expectedSha256:String,
    val destinationExpectedAbsent:Boolean,
    override val reason:String,
    override val citations:List<String>
):EditOperation
