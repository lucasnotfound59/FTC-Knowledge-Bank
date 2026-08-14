package org.ftckb.model.openai

import org.ftckb.model.ModelProvider
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver

object ProviderFactory {
    fun create(
        profile:ProviderProfile,
        secretResolver:SecretResolver,
        transport:HttpTransport=JdkHttpTransport()
    ):ModelProvider {
        val apiKey=secretResolver.get(profile.apiKeyEnv)
            ?.takeIf { it.isNotBlank() }
            ?:error("missing API key environment variable: ${profile.apiKeyEnv}")
        return ChatCompletionsProvider(profile,apiKey,transport)
    }
}
