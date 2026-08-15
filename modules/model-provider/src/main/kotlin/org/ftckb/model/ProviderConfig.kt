package org.ftckb.model

import java.net.URI

enum class MaxTokensParameter { MAX_TOKENS,MAX_COMPLETION_TOKENS }

data class ProviderProfile(
    val name:String,
    val baseUrl:URI,
    val model:String,
    val apiKeyEnv:String,
    val timeoutSeconds:Int=90,
    val maxOutputTokens:Int=4096,
    val maxTokensParameter:MaxTokensParameter?=null,
    val jsonMode:Boolean=false
) {
    init {
        require(name.matches(Regex("[a-z0-9][a-z0-9.-]*"))) { "invalid provider name" }
        require(baseUrl.scheme.equals("https",true) && !baseUrl.host.isNullOrBlank() && baseUrl.userInfo==null) {
            "provider baseUrl must use HTTPS without credentials"
        }
        require(baseUrl.rawQuery==null && baseUrl.rawFragment==null) {
            "provider baseUrl must use HTTPS without credentials, query, or fragment"
        }
        require(model.isNotBlank()) { "provider model must not be blank" }
        require(apiKeyEnv.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "invalid apiKeyEnv" }
        require(timeoutSeconds in 1..300) { "timeoutSeconds must be between 1 and 300" }
        require(maxOutputTokens in 1..131072) { "maxOutputTokens must be between 1 and 131072" }
    }
}

data class ProviderConfig(val defaultProvider:String,val providers:Map<String,ProviderProfile>) {
    fun profile(name:String=defaultProvider):ProviderProfile {
        if (name.length !in 1..MAX_SELECTOR_LENGTH || !name.matches(selectorPattern) ||
            SecretRedactor.redact(name).redactionCount>0) {
            error("invalid provider profile selector")
        }
        return providers[name] ?:error("unknown provider profile")
    }

    private companion object {
        const val MAX_SELECTOR_LENGTH=64
        val selectorPattern=Regex("[a-z0-9][a-z0-9.-]*")
    }
}

fun interface SecretResolver {
    fun get(name:String):String?
}
