package org.ftckb.model.openai

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

class ProviderContractTest {
    @Test
    fun `normalizes recorded OpenAI and DeepSeek success contracts`() {
        val openAi=provider(HttpResult(200,fixture("openai-success.json"))).complete(request())
        val deepSeek=provider(HttpResult(200,fixture("deepseek-success.json"))).complete(request())

        assertEquals("OpenAI fixture answer",openAi.content)
        assertEquals(21,openAi.usage?.inputTokens)
        assertEquals(8,openAi.usage?.outputTokens)
        assertEquals("DeepSeek fixture answer",deepSeek.content)
        assertEquals(34,deepSeek.usage?.inputTokens)
        assertEquals(13,deepSeek.usage?.outputTokens)
    }

    @Test
    fun `normalizes JSON object message content to compact JSON`() {
        val response=provider(HttpResult(200,fixture("json-object-content.json"))).complete(request())

        assertEquals("{\"answer\":\"structured fixture\"}",response.content)
        assertEquals(5,response.usage?.inputTokens)
        assertEquals(null,response.usage?.outputTokens)
    }

    @Test
    fun `rejects a response over four MiB without exposing its body`() {
        val marker=fixture("oversize-response-prefix.txt").trim()
        val body=marker+"x".repeat(4*1024*1024)

        val error=assertThrows(ModelProviderException.Protocol::class.java) {
            provider(HttpResult(200,body)).complete(request())
        }

        assertEquals("model provider response exceeded size limit",error.message)
        assertFalse(error.message.orEmpty().contains(marker))
    }

    @Test
    fun `rejects a multibyte response over four MiB`() {
        val body="€".repeat(1_500_000)

        val error=assertThrows(ModelProviderException.Protocol::class.java) {
            provider(HttpResult(200,body)).complete(request())
        }

        assertEquals("model provider response exceeded size limit",error.message)
    }

    @Test
    fun `classifies recorded error contracts without exposing response bodies`() {
        val cases=listOf(
            ErrorCase(401,"unauthorized.json",ModelProviderException.Authentication::class.java),
            ErrorCase(429,"rate-limited.json",ModelProviderException.RateLimited::class.java),
            ErrorCase(500,"server-error.html",ModelProviderException.Protocol::class.java)
        )

        cases.forEach { case ->
            val body=fixture(case.fixture)
            val error=assertThrows(case.type) {
                provider(HttpResult(case.status,body)).complete(request())
            }
            assertFalse(error.message.orEmpty().contains(body.trim()))
            assertFalse(error.stackTraceToString().contains(body.trim()))
        }
    }

    @Test
    fun `classifies malformed empty and null success bodies as stable protocol errors`() {
        listOf(
            "{" to "model provider returned an invalid response",
            fixture("empty-choices.json") to "model provider response contained no completion content",
            fixture("null-content.json") to "model provider response contained no completion content"
        ).forEach { (body,message) ->
            val error=assertThrows(ModelProviderException.Protocol::class.java) {
                provider(HttpResult(200,body)).complete(request())
            }
            assertEquals(message,error.message)
            assertFalse(error.message.orEmpty().contains(body))
        }
    }

    @Test
    fun `rejects a declared non JSON success body without exposing content type or body`() {
        val contentType="text/html; charset=utf-8"
        val marker="synthetic success page"

        val error=assertThrows(ModelProviderException.Protocol::class.java) {
            provider(HttpResult(200,marker,mapOf("Content-Type" to contentType))).complete(request())
        }

        assertEquals("model provider returned unexpected content type",error.message)
        assertFalse(error.message.orEmpty().contains(contentType))
        assertFalse(error.message.orEmpty().contains(marker))
    }

    @Test
    fun `includes only bounded safe request IDs in status errors`() {
        val safeId="req-fixture-123"
        val unsafeSecret=listOf("sk","synthetic","header","credential").joinToString("-")
        val controlId="req-safe\nforged"
        val oversizedId="r".repeat(129)

        val safe=assertThrows(ModelProviderException.Protocol::class.java) {
            provider(HttpResult(500,"ignored",mapOf("X-Request-Id" to safeId))).complete(request())
        }
        assertTrue(safe.message.orEmpty().contains(safeId))
        listOf(unsafeSecret,controlId,oversizedId).forEach { requestId ->
            val error=assertThrows(ModelProviderException.Protocol::class.java) {
                provider(HttpResult(500,"ignored",mapOf("X-Request-Id" to requestId))).complete(request())
            }
            assertEquals("model provider returned HTTP 500",error.message)
        }
    }

    private fun provider(result:HttpResult)=ProviderFactory.create(
        ProviderProfile(
            "contract-fixture",URI("https://fixture.invalid/v1"),"fixture-model","FIXTURE_CREDENTIAL",
            30,1024,MaxTokensParameter.MAX_TOKENS,false
        ),
        SecretResolver { syntheticCredential() },
        HttpTransport { result }
    )

    private fun request()=ModelRequest(listOf(ModelMessage(MessageRole.USER,"fixture request")),128)

    private fun fixture(name:String)=javaClass.getResource("/contracts/$name")!!.readText()

    private fun syntheticCredential()=listOf("synthetic","provider","credential").joinToString("-")

    private data class ErrorCase(
        val status:Int,
        val fixture:String,
        val type:Class<out ModelProviderException>
    )
}
