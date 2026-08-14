package org.ftckb.model

enum class MessageRole { SYSTEM,USER,ASSISTANT }

data class ModelMessage(val role:MessageRole,val content:String)
data class ModelRequest(val messages:List<ModelMessage>,val maxOutputTokens:Int)
data class TokenUsage(val inputTokens:Int?,val outputTokens:Int?)
data class ModelResponse(val content:String,val usage:TokenUsage?=null)

fun interface ModelProvider {
    fun complete(request:ModelRequest):ModelResponse
}

sealed class ModelProviderException(message:String,cause:Throwable?=null):RuntimeException(message,cause) {
    class Authentication:ModelProviderException("model provider authentication failed")
    class RateLimited:ModelProviderException("model provider rate limit reached")
    class RequestLimit:ModelProviderException("model provider profile cannot enforce the requested output limit")
    class Transport(cause:Throwable):ModelProviderException("model provider transport failed",cause)
    class Protocol(message:String):ModelProviderException(message)
}
