package org.ftckb.model

import java.util.TreeMap

data class RedactionResult(val text:String,val redactionCount:Int)

object SecretRedactor {
    fun redact(text:String,exactSecrets:Set<String> =emptySet()):RedactionResult {
        if (text.isEmpty()) return RedactionResult(text,0)
        val spans=TreeMap<Int,Int>()
        exactSecrets.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .sortedByDescending(String::length)
            .forEach { secret ->
                var offset=0
                while (offset<=text.length-secret.length) {
                    val start=text.indexOf(secret,offset)
                    if (start<0) break
                    select(spans,start,start+secret.length)
                    offset=start+1
                }
            }
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                select(spans,match.range.first,match.range.last+1)
            }
        }
        if (spans.isEmpty()) return RedactionResult(text,0)
        val redacted=buildString(text.length) {
            var offset=0
            spans.forEach { (start,end) ->
                append(text,offset,start)
                append(REPLACEMENT)
                offset=end
            }
            append(text,offset,text.length)
        }
        return RedactionResult(redacted,spans.size)
    }

    private fun select(spans:TreeMap<Int,Int>,start:Int,end:Int) {
        if (spans.floorEntry(start)?.value?.let { it>start }==true) return
        if (spans.ceilingKey(start)?.let { it<end }==true) return
        spans[start]=end
    }

    private const val REPLACEMENT="[REDACTED]"
    private val authorization=Regex("(?i)\\bauthorization[ \\t]*:[ \\t]*bearer[ \\t]+[^\\s,;]+")
    private val bearer=Regex("(?i)\\bbearer[ \\t]+[A-Za-z0-9._~+/-]{12,}={0,2}")
    private val skToken=Regex("(?i)sk-[A-Za-z0-9][A-Za-z0-9._-]{10,}")
    private val apiKeyAssignment=Regex(
        "(?i)(?:\\b[A-Za-z0-9_-]*api[_-]?key\\b|"+
            "[\"'][A-Za-z0-9_-]*api[_-]?key[\"']|"+
            "\\b[A-Za-z0-9_.$-]+\\s*\\[\\s*[\"'][A-Za-z0-9_-]*api[_-]?key[\"']\\s*])"+
            "\\s*[:=]\\s*(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)"
    )
    private val patterns=listOf(authorization,bearer,skToken,apiKeyAssignment)
}
