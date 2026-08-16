package org.ftckb.agent

object ContextSafety {
    const val MAX_CONTEXT_CHARACTERS=48_000
    private const val OPEN_TAG="<untrusted_context"
    private const val CLOSE_TAG="</untrusted_context>"
    private const val ESCAPED_OPEN_TAG="<\\untrusted_context"
    private const val ESCAPED_CLOSE_TAG="<\\/untrusted_context>"

    fun wrap(item:EvidenceItem):String=envelope(item.id,attributes(item),body(item))

    fun wrapUntrusted(id:String,text:String):String=envelope(id,emptyMap(),text)

    fun block(item:EvidenceItem):String=wrap(item)

    fun payload(evidence:List<EvidenceItem>):String=buildString {
        evidence.forEach { append(wrap(it)) }
    }

    fun selectWithinBudget(evidence:List<EvidenceItem>,budget:Int=MAX_CONTEXT_CHARACTERS):List<EvidenceItem> {
        require(budget>=0) { "budget must not be negative" }
        val selected=mutableListOf<EvidenceItem>()
        var characters=0
        for (item in evidence) {
            val length=block(item).length
            if (characters+length>budget) continue
            selected+=item
            characters+=length
        }
        return selected
    }

    private fun attributes(item:EvidenceItem):Map<String,String> =when (item) {
        is CodeEvidence -> mapOf("sha256" to item.sha256)
        is RuleEvidenceItem,is GuideEvidence -> emptyMap()
    }

    private fun body(item:EvidenceItem):String=when (item) {
        is CodeEvidence -> item.path+':'+item.startLine+'-'+item.endLine+'\n'+item.text
        is RuleEvidenceItem -> "approved rule "+item.rule.id+": "+item.rule.instruction
        is GuideEvidence -> "guide "+item.path+" # "+item.heading+'\n'+item.text
    }

    private fun envelope(id:String,attributes:Map<String,String>,body:String):String=buildString {
        append(OPEN_TAG).append(" id=\"").append(escapeAttribute(id)).append('"')
        attributes.forEach { (name,value) ->
            append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"')
        }
        append(">\n").append(escapeBody(body)).append('\n').append(CLOSE_TAG).append('\n')
    }

    private fun escapeAttribute(value:String):String=value.replace("\\","\\\\").replace("\"","\\\"")

    private fun escapeBody(value:String):String=value
        .replace(CLOSE_TAG,ESCAPED_CLOSE_TAG)
        .replace(OPEN_TAG,ESCAPED_OPEN_TAG)
}
