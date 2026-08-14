package org.ftckb.cli

import java.nio.file.Path
import org.ftckb.agent.AgentAnswer

interface AskChatSession {
    fun ask(question:String):AgentAnswer
    fun status():ChatStatus
    fun save(path:Path?):Path
}

data class ChatStatus(
    val repository:Path,val team:String,val season:String,val provider:String,val model:String
)
