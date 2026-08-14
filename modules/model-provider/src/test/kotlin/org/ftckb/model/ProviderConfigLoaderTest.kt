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
}
