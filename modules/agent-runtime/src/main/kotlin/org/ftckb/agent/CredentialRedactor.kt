package org.ftckb.agent

import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest

object CredentialRedactor {
    private val authorization=Regex("(?i)\\bauthorization\\s*:\\s*bearer\\s+[^\\s,;]+")
    private val bearer=Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+={0,2}")
    private val skToken=Regex("(?i)\\bsk-[A-Za-z0-9._-]+")
    private val apiKeyAssignment=Regex("(?i)\\b[A-Za-z0-9_-]*api[_-]?key\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)")

    fun redact(text:String,exactSecrets:Set<String> =emptySet()):String {
        var redacted=text
        exactSecrets.asSequence()
            .filter(String::isNotBlank)
            .sortedByDescending(String::length)
            .forEach { secret -> redacted=redacted.replace(secret,"[REDACTED]") }
        return redacted
            .replace(authorization,"[REDACTED_AUTHORIZATION]")
            .replace(bearer,"[REDACTED_BEARER]")
            .replace(skToken,"[REDACTED_SECRET]")
            .replace(apiKeyAssignment,"[REDACTED_API_KEY]")
    }
}

class RedactingModelProvider(
    private val delegate:ModelProvider,
    exactSecrets:Set<String> =emptySet()
):ModelProvider {
    private val exactSecrets=exactSecrets.filter(String::isNotBlank).toSet()

    override fun complete(request:ModelRequest)=delegate.complete(request.copy(
        messages=request.messages.map { message ->
            message.copy(content=CredentialRedactor.redact(message.content,exactSecrets))
        }
    ))
}
