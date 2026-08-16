package org.ftckb.model

import java.util.TreeMap

data class RedactionResult(val text:String,val redactionCount:Int)

object SecretRedactor {
    fun redact(text:String,exactSecrets:Set<String> =emptySet()):RedactionResult {
        if (text.isEmpty()) return RedactionResult(text,0)
        val spans=TreeMap<Int,Int>()
        if (exactSecrets.size>MAX_EXACT_SECRETS) return failClosed()
        var exactSecretCharacters=0L
        exactSecrets.filterNot(String::isBlank).forEach { secret ->
            exactSecretCharacters+=secret.length
            if (exactSecretCharacters>MAX_EXACT_SECRET_CHARACTERS) return failClosed()
        }
        val secrets=exactSecrets.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .sortedByDescending(String::length)
            .toList()
        if (text.length.toLong()*secrets.size>MAX_EXACT_SCAN_CHARACTERS) return failClosed()
        var candidateMatches=0
        for (secret in secrets) {
            var offset=0
            while (offset<=text.length-secret.length) {
                val start=text.indexOf(secret,offset)
                if (start<0) break
                candidateMatches++
                if (candidateMatches>MAX_CANDIDATE_MATCHES) return failClosed()
                select(spans,start,start+secret.length)
                if (spans.size>MAX_REDACTION_SPANS) return failClosed()
                if (spans.size==1 && spans.firstKey()==0 && spans.firstEntry().value==text.length) return failClosed()
                offset=start+secret.length
            }
        }
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                candidateMatches++
                if (candidateMatches>MAX_CANDIDATE_MATCHES) return failClosed()
                select(spans,match.range.first,match.range.last+1)
                if (spans.size>MAX_REDACTION_SPANS) return failClosed()
                if (spans.size==1 && spans.firstKey()==0 && spans.firstEntry().value==text.length) return failClosed()
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

    private fun failClosed()=RedactionResult(REPLACEMENT,1)

    private fun select(spans:TreeMap<Int,Int>,start:Int,end:Int) {
        var mergedStart=start
        var mergedEnd=end
        spans.floorEntry(start)?.takeIf { (_,existingEnd) -> existingEnd>start }?.let { (existingStart,existingEnd) ->
            mergedStart=existingStart
            mergedEnd=maxOf(mergedEnd,existingEnd)
            spans.remove(existingStart)
        }
        var next=spans.ceilingEntry(mergedStart)
        while (next!=null && next.key<mergedEnd) {
            mergedEnd=maxOf(mergedEnd,next.value)
            spans.remove(next.key)
            next=spans.ceilingEntry(mergedStart)
        }
        spans[mergedStart]=mergedEnd
    }

    private const val REPLACEMENT="[REDACTED]"
    private const val MAX_EXACT_SECRETS=64
    private const val MAX_EXACT_SECRET_CHARACTERS=8L*1024*1024
    private const val MAX_EXACT_SCAN_CHARACTERS=64L*1024*1024
    private const val MAX_CANDIDATE_MATCHES=4096
    private const val MAX_REDACTION_SPANS=4096
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
