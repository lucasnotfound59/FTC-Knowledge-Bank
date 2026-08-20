package org.ftckb.session

import org.ftckb.agent.CredentialRedactor
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse

/**
 * Provider facade whose delegate and redaction secrets can be swapped while the
 * surrounding ConversationState keeps its history. Redacts both request messages
 * and response content so secrets never reach the wire or the stored transcript.
 */
class SwapModelProvider(
    initial:ModelProvider,
    initialSecrets:Set<String> =emptySet()
):ModelProvider {
    @Volatile private var delegate=initial
    @Volatile private var exactSecrets=initialSecrets.filter(String::isNotBlank).toSet()

    @Synchronized
    fun replace(newDelegate:ModelProvider,newSecrets:Set<String> =emptySet()) {
        delegate=newDelegate
        exactSecrets=newSecrets.filter(String::isNotBlank).toSet()
    }

    fun currentSecrets():Set<String> =exactSecrets

    override fun complete(request:ModelRequest):ModelResponse {
        val secrets=exactSecrets
        val redactedRequest=request.copy(
            messages=request.messages.map { message->
                message.copy(content=CredentialRedactor.redact(message.content,secrets))
            }
        )
        return delegate.complete(redactedRequest).let { response->
            response.copy(content=CredentialRedactor.redact(response.content,secrets))
        }
    }
}
