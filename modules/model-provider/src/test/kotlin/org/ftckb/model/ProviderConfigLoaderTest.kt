package org.ftckb.model

import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProviderConfigLoaderTest {
    @Test
    fun `decodes a strict deepseek profile`() {
        val config=ProviderConfigLoader.decode("""
            defaultProvider: deepseek
            providers:
              deepseek:
                baseUrl: https://api.deepseek.com
                model: deepseek-chat
                apiKeyEnv: DEEPSEEK_API_KEY
                timeoutSeconds: 90
                maxOutputTokens: 4096
                maxTokensParameter: max_tokens
                jsonMode: false
        """.trimIndent())

        assertEquals("deepseek",config.defaultProvider)
        assertEquals(
            ProviderProfile(
                "deepseek",URI("https://api.deepseek.com"),"deepseek-chat","DEEPSEEK_API_KEY",
                90,4096,MaxTokensParameter.MAX_TOKENS,false
            ),
            config.profile("deepseek")
        )
    }

    @Test
    fun `rejects unknown profile fields`() {
        val error=assertThrows(IllegalStateException::class.java) {
            ProviderConfigLoader.decode("""
                defaultProvider: custom
                providers:
                  custom:
                    baseUrl: https://example.com/v1
                    model: model
                    apiKeyEnv: CUSTOM_KEY
                    unsafeExtraBody: true
            """.trimIndent())
        }
        assertEquals("providers.custom contains unknown fields: unsafeExtraBody",error.message)
    }

    @Test
    fun `rejects non-https provider roots`() {
        val error=assertThrows(IllegalArgumentException::class.java) {
            ProviderProfile("x",URI("http://example.com"),"m","KEY",90,4096,null,false)
        }
        assertEquals("provider baseUrl must use HTTPS without credentials",error.message)
    }

    @Test
    fun `rejects provider roots with a query or fragment`() {
        listOf(
            URI("https://example.com/v1?tenant=robotics"),
            URI("https://example.com/v1#chat")
        ).forEach { uri ->
            val error=assertThrows(IllegalArgumentException::class.java) {
                ProviderProfile("x",uri,"m","KEY",90,4096,null,false)
            }
            assertEquals("provider baseUrl must use HTTPS without credentials, query, or fragment",error.message)
        }
    }

    @Test
    fun `rejects unsafe provider selectors before lookup without echoing them`() {
        val profile=ProviderProfile(
            "custom.v2-beta",URI("https://example.com/v1"),"model","CUSTOM_KEY"
        )
        val config=ProviderConfig(profile.name,mapOf(profile.name to profile))
        val credentialShape=listOf("sk","synthetic","selector","credential").joinToString("-")
        val selectors=listOf(
            "custom.v2-beta\nforged",
            "\u001b[31mcustom.v2-beta",
            "\u001b]0;owned\u0007custom.v2-beta",
            "\u202ecustom.v2-beta",
            "custom\u200d.v2-beta",
            credentialShape,
            "x".repeat(1024*1024)
        )

        selectors.forEach { selector ->
            val error=assertThrows(IllegalStateException::class.java) { config.profile(selector) }
            assertEquals("invalid provider profile selector",error.message)
        }
        assertEquals(profile,config.profile("custom.v2-beta"))
    }

    @Test
    fun `does not echo a valid but unknown provider selector`() {
        val profile=ProviderProfile("known",URI("https://example.com/v1"),"model","CUSTOM_KEY")
        val config=ProviderConfig(profile.name,mapOf(profile.name to profile))

        val error=assertThrows(IllegalStateException::class.java) { config.profile("unknown-custom") }

        assertEquals("unknown provider profile",error.message)
    }
}
