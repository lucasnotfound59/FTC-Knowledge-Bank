package org.ftckb.agent.edit

import com.fasterxml.jackson.databind.JsonNode
import org.ftckb.agent.ModelJson

object EditPlanParser {
    private val sha256Pattern=Regex("[0-9a-f]{64}")
    private val windowsAbsolutePath=Regex("^[A-Za-z]:")

    fun parse(text:String):EditPlan {
        val node=ModelJson.objectNode(text)
        ModelJson.requireFields(node,setOf("summary","operations"),"edit plan")
        val summary=nonBlankString(node,"summary","edit plan")
        val operationNodes=node["operations"]
        require(operationNodes.isArray) { "operations must be an array" }
        require(!operationNodes.isEmpty) { "operations must not be empty" }
        require(operationNodes.size()<=MAX_OPERATIONS) { "operations must contain at most $MAX_OPERATIONS items" }
        val destinations=mutableSetOf<String>()
        val operations=operationNodes.mapIndexed { index,operationNode ->
            val operation=parseOperation(operationNode,index)
            val destination=destinationOf(operation)
            require(destinations.add(destination)) { "operations contain duplicate destination: $destination" }
            operation
        }
        return EditPlan(summary,operations)
    }

    private fun parseOperation(node:JsonNode,index:Int):EditOperation {
        require(node.isObject) { "operations[$index] must be an object" }
        val kind=requiredString(node,"kind","operations[$index]")
        return when (kind) {
            "create" -> parseCreate(node,index)
            "replace" -> parseReplace(node,index)
            "delete" -> parseDelete(node,index)
            "move" -> parseMove(node,index)
            else -> throw IllegalArgumentException("operations[$index].kind is unsupported: $kind")
        }
    }

    private fun parseCreate(node:JsonNode,index:Int):CreateText {
        val name="operations[$index]"
        ModelJson.requireFields(node,setOf("kind","path","expectedAbsent","content","reason","citations"),name)
        return CreateText(
            safePath(requiredString(node,"path",name),"$name.path"),
            requiredTrue(node,"expectedAbsent",name),
            requiredString(node,"content",name),
            nonBlankString(node,"reason",name),
            citations(node,name)
        )
    }

    private fun parseReplace(node:JsonNode,index:Int):ReplaceText {
        val name="operations[$index]"
        ModelJson.requireFields(node,setOf("kind","path","expectedSha256","oldText","newText","reason","citations"),name)
        return ReplaceText(
            safePath(requiredString(node,"path",name),"$name.path"),
            sha256(requiredString(node,"expectedSha256",name),"$name.expectedSha256"),
            requiredString(node,"oldText",name),
            requiredString(node,"newText",name),
            nonBlankString(node,"reason",name),
            citations(node,name)
        )
    }

    private fun parseDelete(node:JsonNode,index:Int):DeleteText {
        val name="operations[$index]"
        ModelJson.requireFields(node,setOf("kind","path","expectedSha256","reason","citations"),name)
        return DeleteText(
            safePath(requiredString(node,"path",name),"$name.path"),
            sha256(requiredString(node,"expectedSha256",name),"$name.expectedSha256"),
            nonBlankString(node,"reason",name),
            citations(node,name)
        )
    }

    private fun parseMove(node:JsonNode,index:Int):MoveText {
        val name="operations[$index]"
        ModelJson.requireFields(
            node,
            setOf("kind","sourcePath","destinationPath","expectedSha256","destinationExpectedAbsent","reason","citations"),
            name
        )
        val sourcePath=safePath(requiredString(node,"sourcePath",name),"$name.sourcePath")
        val destinationPath=safePath(requiredString(node,"destinationPath",name),"$name.destinationPath")
        require(sourcePath!=destinationPath) { "$name source and destination must differ" }
        return MoveText(
            sourcePath,
            destinationPath,
            sha256(requiredString(node,"expectedSha256",name),"$name.expectedSha256"),
            requiredTrue(node,"destinationExpectedAbsent",name),
            nonBlankString(node,"reason",name),
            citations(node,name)
        )
    }

    private fun destinationOf(operation:EditOperation):String=when (operation) {
        is CreateText -> operation.path
        is ReplaceText -> operation.path
        is DeleteText -> operation.path
        is MoveText -> operation.destinationPath
    }

    private fun requiredString(node:JsonNode,field:String,name:String):String {
        val value=node[field]
        require(value!=null && value.isTextual) { "$name.$field must be a string" }
        return value.asText()
    }

    private fun nonBlankString(node:JsonNode,field:String,name:String):String {
        val value=requiredString(node,field,name)
        require(value.isNotBlank()) { "$name.$field must not be blank" }
        require(value.length<=MAX_TEXT_LENGTH) { "$name.$field must be at most $MAX_TEXT_LENGTH characters" }
        return value
    }

    private fun requiredTrue(node:JsonNode,field:String,name:String):Boolean {
        val value=node[field]
        require(value!=null && value.isBoolean && value.booleanValue()) { "$name.$field must be true" }
        return true
    }

    private fun citations(node:JsonNode,name:String):List<String> {
        val values=ModelJson.stringArray(node["citations"],"$name.citations")
        require(values.isNotEmpty()) { "$name.citations must not be empty" }
        require(values.all(String::isNotBlank)) { "$name.citations must contain non-blank IDs" }
        require(values.size<=MAX_CITATIONS) { "$name.citations must contain at most $MAX_CITATIONS items" }
        return values
    }

    private fun sha256(value:String,name:String):String {
        require(sha256Pattern.matches(value)) { "$name must be a lowercase SHA-256 hash" }
        return value
    }

    private fun safePath(value:String,name:String):String {
        require(value.isNotBlank() && value.length<=MAX_PATH_LENGTH) { "$name must be a non-blank path up to $MAX_PATH_LENGTH characters" }
        require('\\' !in value && '\u0000' !in value) { "$name contains unsafe path syntax" }
        require(!value.startsWith('/') && !windowsAbsolutePath.containsMatchIn(value)) { "$name must be relative" }
        val parts=value.split('/')
        require(parts.none { it.isEmpty() || it=="." || it==".." }) { "$name contains unsafe path syntax" }
        return value
    }

    private const val MAX_OPERATIONS=24
    private const val MAX_CITATIONS=16
    private const val MAX_TEXT_LENGTH=2_000
    private const val MAX_PATH_LENGTH=512
}
