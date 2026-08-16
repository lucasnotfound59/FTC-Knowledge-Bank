package org.ftckb.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadFeature

object ModelJson {
    private val mapper:ObjectMapper=JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()
    private val fencedJson=Regex("^\\s*```json[\\t ]*\\r?\\n([\\s\\S]*?)\\r?\\n```\\s*$")

    fun objectNode(text:String):JsonNode {
        val body=fencedJson.matchEntire(text)?.groupValues?.get(1) ?: text.also {
            require("```" !in it) { "response must be a JSON object or one fenced json block" }
        }
        val node=try {
            val parser:JsonParser=mapper.factory.createParser(body)
            parser.use {
                val parsed:JsonNode=mapper.readTree(it) ?: error("response must contain JSON")
                require(it.nextToken()==null) { "response contains trailing JSON content" }
                parsed
            }
        } catch (error:Exception) {
            if (error is IllegalArgumentException) throw error
            throw IllegalArgumentException("response must contain valid JSON",error)
        }
        require(node.isObject) { "response must be a JSON object" }
        return node
    }

    fun requireFields(node:JsonNode,allowed:Set<String>,name:String) {
        require(node.isObject) { "$name must be an object" }
        val unknown=node.fieldNames().asSequence().filter { it !in allowed }.toList().sorted()
        require(unknown.isEmpty()) { "$name contains unknown fields: ${unknown.joinToString()}" }
        val missing=allowed.filter { !node.has(it) }.sorted()
        require(missing.isEmpty()) { "$name is missing fields: ${missing.joinToString()}" }
    }

    fun stringArray(node:JsonNode,name:String):List<String> {
        require(node.isArray) { "$name must be an array" }
        return node.mapIndexed { index,value ->
            require(value.isTextual) { "$name[$index] must be a string" }
            value.asText()
        }
    }
}
