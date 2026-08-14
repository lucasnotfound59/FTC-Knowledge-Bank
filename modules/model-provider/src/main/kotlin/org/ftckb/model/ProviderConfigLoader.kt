package org.ftckb.model

import java.math.BigDecimal
import java.net.URI
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

object ProviderConfigLoader {
    private val load=Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
    private val rootKeys=setOf("defaultProvider","providers")
    private val profileKeys=setOf(
        "baseUrl","model","apiKeyEnv","timeoutSeconds","maxOutputTokens","maxTokensParameter","jsonMode"
    )

    fun decode(text:String):ProviderConfig {
        val root=load.loadFromString(text).asMap("root")
        root.rejectUnknownFields(rootKeys,"root")
        val defaultProvider=root.string("defaultProvider")
        val providerMaps=root.requiredMap("providers")
        require(providerMaps.isNotEmpty()) { "providers must not be empty" }
        val providers=providerMaps.mapValues { (name,value) ->
            decodeProfile(name,value.asMap("providers.$name"))
        }
        require(defaultProvider in providers) { "defaultProvider must name an existing provider" }
        return ProviderConfig(defaultProvider,providers)
    }

    private fun decodeProfile(name:String,map:Map<String,Any?>):ProviderProfile {
        map.rejectUnknownFields(profileKeys,"providers.$name")
        return ProviderProfile(
            name=name,
            baseUrl=URI(map.string("baseUrl")),
            model=map.string("model"),
            apiKeyEnv=map.string("apiKeyEnv"),
            timeoutSeconds=map.optionalInt("timeoutSeconds") ?: 90,
            maxOutputTokens=map.optionalInt("maxOutputTokens") ?: 4096,
            maxTokensParameter=map.optionalString("maxTokensParameter")?.toMaxTokensParameter(),
            jsonMode=map.optionalBoolean("jsonMode") ?: false
        )
    }

    private fun String.toMaxTokensParameter()=when (this) {
        "max_tokens" -> MaxTokensParameter.MAX_TOKENS
        "max_completion_tokens" -> MaxTokensParameter.MAX_COMPLETION_TOKENS
        else -> error("maxTokensParameter must be max_tokens or max_completion_tokens")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(name:String)=this as? Map<String,Any?> ?: error("$name must be a map")
    private fun Map<String,Any?>.string(key:String)=this[key] as? String ?: error("$key must be a string")
    private fun Map<String,Any?>.optionalString(key:String):String? {
        if (key !in this) return null
        return this[key] as? String ?: error("$key must be a string")
    }
    private fun Map<String,Any?>.requiredMap(key:String)=this[key].asMap(key)
    private fun Map<String,Any?>.optionalInt(key:String):Int? {
        if (key !in this) return null
        return this[key].strictInt(key)
    }
    private fun Map<String,Any?>.optionalBoolean(key:String):Boolean? {
        if (key !in this) return null
        return this[key] as? Boolean ?: error("$key must be a boolean")
    }
    private fun Any?.strictInt(key:String):Int {
        val number=this as? Number ?: error("$key must be an integer")
        return runCatching { BigDecimal(number.toString()).toBigIntegerExact().intValueExact() }
            .getOrElse { error("$key must be an integer") }
    }
    private fun Map<String,Any?>.rejectUnknownFields(allowed:Set<String>,name:String) {
        val unknown=(keys-allowed).sorted()
        if (unknown.isNotEmpty()) error("$name contains unknown fields: ${unknown.joinToString()}")
    }
}
