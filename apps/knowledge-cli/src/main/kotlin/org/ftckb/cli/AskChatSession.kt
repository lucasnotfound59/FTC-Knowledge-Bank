package org.ftckb.cli

import java.nio.file.Path
import org.ftckb.agent.AgentAnswer

interface AskChatSession {
    fun ask(question:String):AgentAnswer
    fun status():ChatStatus
    fun save(path:Path?):Path
}

sealed class AskChatSessionException(message:String):RuntimeException(message) {
    class RepositoryRead:AskChatSessionException("local repository is unavailable")
    class KnowledgeRead:AskChatSessionException("local knowledge is unavailable")
}

data class ChatStatus(
    val repository:Path,val team:String,val season:String,val provider:String,val model:String
)
