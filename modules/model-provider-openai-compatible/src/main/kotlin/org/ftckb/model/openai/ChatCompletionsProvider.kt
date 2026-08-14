package org.ftckb.model.openai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelProviderException
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.model.ProviderProfile
import org.ftckb.model.TokenUsage

internal class ChatCompletionsProvider(
    private val profile:ProviderProfile,
    private val apiKey:String,
    private val transport:HttpTransport,
    private val mapper:JsonMapper=JsonMapper.builder().build()
):ModelProvider {
    override fun complete(request:ModelRequest):ModelResponse {
        if (profile.maxTokensParameter==null && request.maxOutputTokens>profile.maxOutputTokens) {
            throw ModelProviderException.RequestLimit()
        }
        val result=try {
            transport.send(HttpExchange(
                endpoint(),
                mapOf("Authorization" to "Bearer $apiKey","Accept" to "application/json"),
                encode(request),
                profile.timeoutSeconds
            ))
        } catch (_:IOException) {
            throw ModelProviderException.Transport(IOException("HTTP request failed"))
        }
        when (result.status) {
            401,403 -> throw ModelProviderException.Authentication()
            429 -> throw ModelProviderException.RateLimited()
        }
        if (result.status !in 200..299) {
            throw ModelProviderException.Protocol("model provider returned HTTP ${result.status}")
        }
        return decode(result.body)
    }

    private fun endpoint()=URI(profile.baseUrl.toString().removeSuffix("/")+"/chat/completions")

    private fun encode(request:ModelRequest):String {
        val body=mapper.createObjectNode()
        body.put("model",profile.model)
        val messages=body.putArray("messages")
        request.messages.forEach { message ->
            messages.addObject()
                .put("role",message.role.name.lowercase())
                .put("content",message.content)
        }
        val tokenField=when (profile.maxTokensParameter) {
            org.ftckb.model.MaxTokensParameter.MAX_TOKENS -> "max_tokens"
            org.ftckb.model.MaxTokensParameter.MAX_COMPLETION_TOKENS -> "max_completion_tokens"
            null -> null
        }
        if (tokenField!=null) body.put(tokenField,minOf(request.maxOutputTokens,profile.maxOutputTokens))
        if (profile.jsonMode) body.putObject("response_format").put("type","json_object")
        body.put("stream",false)
        return mapper.writeValueAsString(body)
    }

    private fun decode(body:String):ModelResponse {
        val root=try {
            mapper.readTree(body)
        } catch (_:JsonProcessingException) {
            throw ModelProviderException.Protocol("model provider returned an invalid response")
        }
        val content=root.path("choices").takeIf(JsonNode::isArray)
            ?.get(0)?.path("message")?.path("content")
            ?.takeIf { it.isTextual && it.asText().isNotBlank() }
            ?.asText()
            ?:throw ModelProviderException.Protocol("model provider response contained no completion content")
        val usage=root.path("usage")
        val inputTokens=usage.intOrNull("prompt_tokens")
        val outputTokens=usage.intOrNull("completion_tokens")
        return ModelResponse(content,if (inputTokens==null && outputTokens==null) null else TokenUsage(inputTokens,outputTokens))
    }

    private fun JsonNode.intOrNull(name:String):Int? {
        val value=path(name)
        return if (value.isInt) value.intValue() else null
    }
}
