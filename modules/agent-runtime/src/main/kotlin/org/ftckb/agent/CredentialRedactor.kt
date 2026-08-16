package org.ftckb.agent

import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest

object CredentialRedactor {
    fun redact(text:String,exactSecrets:Set<String> =emptySet()):String=
        SecretRedactor.redact(text,exactSecrets).text
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
    )).let { response->
        response.copy(content=CredentialRedactor.redact(response.content,exactSecrets))
    }
}
