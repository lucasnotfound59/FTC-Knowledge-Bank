package org.ftckb.model.openai

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import org.ftckb.model.MaxTokensParameter
import org.ftckb.model.MessageRole
import org.ftckb.model.ModelMessage
import org.ftckb.model.ModelProviderException
import org.ftckb.model.ModelRequest
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatCompletionsProviderTest {
    private val mapper=JsonMapper.builder().build()

    @Test
    fun `sends a DeepSeek-compatible request and normalizes a completion`() {
        val transport=FakeTransport(HttpResult(200,fixture("deepseek-success.json")))
        val provider=ProviderFactory.create(profile(),resolver(),transport)

        val response=provider.complete(ModelRequest(
            listOf(ModelMessage(MessageRole.SYSTEM,"system"),ModelMessage(MessageRole.USER,"hello")),512
        ))

        assertEquals("answer",response.content)
        assertEquals(12,response.usage?.inputTokens)
        assertEquals(7,response.usage?.outputTokens)
        assertEquals(URI("https://api.deepseek.com/chat/completions"),transport.exchange.uri)
        assertEquals("Bearer test-key",transport.exchange.headers["Authorization"])
        val body=mapper.readTree(transport.exchange.body)
        assertEquals(setOf("model","messages","max_tokens","stream"),body.fieldNames().asSequence().toSet())
        assertEquals("deepseek-chat",body["model"].asText())
        assertEquals(512,body["max_tokens"].asInt())
        assertEquals("system",body["messages"][0]["role"].asText())
        assertEquals("user",body["messages"][1]["role"].asText())
        assertFalse(body["stream"].booleanValue())
    }

    @Test
    fun `uses configured max completion tokens field`() {
        val transport=FakeTransport(HttpResult(200,fixture("openai-success.json")))
        val provider=ProviderFactory.create(
            profile(maxTokensParameter=MaxTokensParameter.MAX_COMPLETION_TOKENS),resolver(),transport
        )

        provider.complete(request())

        val body=mapper.readTree(transport.exchange.body)
        assertEquals(512,body["max_completion_tokens"].asInt())
        assertFalse(body.has("max_tokens"))
    }

    @Test
    fun `omits token fields when the profile does not name one`() {
        val transport=FakeTransport(HttpResult(200,fixture("openai-success.json")))
        val provider=ProviderFactory.create(profile(maxTokensParameter=null),resolver(),transport)

        provider.complete(request())

        val body=mapper.readTree(transport.exchange.body)
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
    }

    @Test
    fun `caps a named token field at the configured profile limit`() {
        val transport=FakeTransport(HttpResult(200,fixture("openai-success.json")))
        val provider=ProviderFactory.create(profile(maxOutputTokens=256),resolver(),transport)

        provider.complete(ModelRequest(listOf(ModelMessage(MessageRole.USER,"hello")),4_096))

        assertEquals(256,mapper.readTree(transport.exchange.body)["max_tokens"].asInt())
        assertTrue(transport.sent)
    }

    @Test
    fun `rejects an oversized request even when token fields are omitted`() {
        val transport=FakeTransport(HttpResult(200,fixture("openai-success.json")))
        val provider=ProviderFactory.create(
            profile(maxTokensParameter=null,maxOutputTokens=256),resolver(),transport
        )

        val error=assertThrows(ModelProviderException.RequestLimit::class.java) {
            provider.complete(ModelRequest(listOf(ModelMessage(MessageRole.USER,"hello")),4_096))
        }

        assertEquals("model provider profile cannot enforce the requested output limit",error.message)
        assertFalse(transport.sent)
    }

    @Test
    fun `adds json object response format when json mode is configured`() {
        val transport=FakeTransport(HttpResult(200,fixture("openai-success.json")))
        val provider=ProviderFactory.create(profile(jsonMode=true),resolver(),transport)

        provider.complete(request())

        assertEquals("json_object",mapper.readTree(transport.exchange.body)["response_format"]["type"].asText())
    }

    @Test
    fun `maps unauthorized responses to redacted authentication errors`() {
        val error=assertThrows(ModelProviderException.Authentication::class.java) {
            ProviderFactory.create(profile(),resolver(),FakeTransport(HttpResult(401,"not authorized test-key"))).complete(request())
        }

        assertFalse(error.message.orEmpty().contains("test-key"))
    }

    @Test
    fun `maps rate limited responses to redacted rate limit errors`() {
        val error=assertThrows(ModelProviderException.RateLimited::class.java) {
            ProviderFactory.create(profile(),resolver(),FakeTransport(HttpResult(429,"rate limited test-key"))).complete(request())
        }

        assertFalse(error.message.orEmpty().contains("test-key"))
    }

    @Test
    fun `maps server responses to redacted protocol errors`() {
        val error=assertThrows(ModelProviderException.Protocol::class.java) {
            ProviderFactory.create(profile(),resolver(),FakeTransport(HttpResult(500,"server error test-key"))).complete(request())
        }

        assertEquals("model provider returned HTTP 500",error.message)
        assertFalse(error.message.orEmpty().contains("test-key"))
    }

    @Test
    fun `maps I O failures to redacted transport errors`() {
        val error=assertThrows(ModelProviderException.Transport::class.java) {
            ProviderFactory.create(profile(),resolver(),HttpTransport { throw IOException("request failed for test-key") })
                .complete(request())
        }

        assertFalse(error.message.orEmpty().contains("test-key"))
        assertFalse(error.cause?.message.orEmpty().contains("test-key"))
    }

    @Test
    fun `maps malformed authorization headers to stable transport errors without retaining credentials`() {
        val injectedName=listOf("X","Synthetic","Header").joinToString("-")
        val secretShape=listOf("sk","synthetic","header","credential").joinToString("-")
        val malformed=listOf("synthetic","line").joinToString("-")+"\r\n$injectedName: $secretShape"
        val provider=ProviderFactory.create(
            profile(),SecretResolver { malformed },JdkHttpTransport()
        )

        val error=assertThrows(ModelProviderException.Transport::class.java) {
            provider.complete(request())
        }

        assertEquals("model provider transport failed",error.message)
        assertEquals("HTTP request failed",error.cause?.message)
        assertEquals(null,error.cause?.cause)
        val diagnostic=throwableDiagnostics(error)
        listOf(malformed,injectedName,secretShape).forEach { unsafe ->
            assertFalse(diagnostic.contains(unsafe),unsafe)
        }
    }

    @Test
    fun `rejects empty choices as a protocol error`() {
        val error=assertThrows(ModelProviderException.Protocol::class.java) {
            ProviderFactory.create(profile(),resolver(),FakeTransport(HttpResult(200,"{\"choices\":[]}"))).complete(request())
        }

        assertEquals("model provider response contained no completion content",error.message)
    }

    @Test
    fun `rejects missing environment keys without exposing a key value`() {
        val error=assertThrows(IllegalStateException::class.java) {
            ProviderFactory.create(profile(),SecretResolver { null },FakeTransport(HttpResult(200,"{}")))
        }

        assertEquals("missing API key environment variable: DEEPSEEK_API_KEY",error.message)
        assertFalse(error.message.orEmpty().contains("test-key"))
    }

    private fun request()=ModelRequest(listOf(ModelMessage(MessageRole.USER,"hello")),512)

    @Test
    fun `sends configured temperature`() {
        val transport=FakeTransport(HttpResult(200,fixture("openai-success.json")))
        val provider=ProviderFactory.create(profile(temperature=0.0),resolver(),transport)

        provider.complete(request())

        val body=mapper.readTree(transport.exchange.body)
        assertEquals(0.0,body["temperature"].asDouble())
    }

    @Test
    fun `omits temperature when the profile does not configure it`() {
        val transport=FakeTransport(HttpResult(200,fixture("openai-success.json")))
        val provider=ProviderFactory.create(profile(),resolver(),transport)

        provider.complete(request())

        assertFalse(mapper.readTree(transport.exchange.body).has("temperature"))
    }

    private fun profile(
        maxTokensParameter:MaxTokensParameter?=MaxTokensParameter.MAX_TOKENS,
        maxOutputTokens:Int=4096,
        jsonMode:Boolean=false,
        temperature:Double?=null
    )=ProviderProfile(
        "deepseek",URI("https://api.deepseek.com/"),"deepseek-chat","DEEPSEEK_API_KEY",
        90,maxOutputTokens,maxTokensParameter,jsonMode,temperature
    )

    private fun resolver()=SecretResolver { name -> if (name=="DEEPSEEK_API_KEY") "test-key" else null }

    private fun fixture(name:String)=javaClass.getResource("/$name")!!.readText()

    private fun throwableDiagnostics(error:Throwable):String=buildString {
        val pending=ArrayDeque<Throwable>().apply { add(error) }
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        while (pending.isNotEmpty()) {
            val current=pending.removeFirst()
            if (!seen.add(current)) continue
            append(current.toString()).append('\n')
            current.cause?.let(pending::add)
            current.suppressed.forEach(pending::add)
        }
    }

    private class FakeTransport(private val result:HttpResult):HttpTransport {
        lateinit var exchange:HttpExchange
        var sent=false

        override fun send(exchange:HttpExchange):HttpResult {
            sent=true
            this.exchange=exchange
            return result
        }
    }
}
