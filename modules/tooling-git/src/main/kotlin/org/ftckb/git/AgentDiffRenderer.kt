package org.ftckb.git

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils

data class TextChange(
    val path:String,val before:String?,val after:String?,val projectLevel:Boolean,
    val expectedExecutable:Boolean?=null
)

object AgentDiffRenderer {
    fun render(changes:List<TextChange>):String=changes
        .sortedBy { it.path }
        .joinToString(separator="") { change->render(change) }

    private fun render(change:TextChange):String {
        val beforeLines=change.before?.split('\n')?:emptyList()
        val afterLines=change.after?.split('\n')?:emptyList()
        val patch=DiffUtils.diff(beforeLines,afterLines)
        val oldName=if(change.before==null) "/dev/null" else "a/${change.path}"
        val newName=if(change.after==null) "/dev/null" else "b/${change.path}"
        val lines=UnifiedDiffUtils.generateUnifiedDiff(oldName,newName,beforeLines,patch,3)
        val marker=if(change.projectLevel) listOf("PROJECT-LEVEL CHANGE: ${change.path}") else emptyList()
        return (marker+lines).joinToString(separator="\n",postfix="\n")
    }
}
