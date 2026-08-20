package org.ftckb.intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

class FtckbSettingsState {
    var team="20827"
    var season="2025-2026"
    var provider="deepseek"
    var configPath=""
    var knowledgePath=""
}

@State(name="FtckbSettings",storages=[Storage("ftckb-as.xml")])
@Service(Service.Level.PROJECT)
class FtckbSettings(private val project:Project):PersistentStateComponent<FtckbSettingsState> {
    private var state=FtckbSettingsState()

    override fun getState():FtckbSettingsState =state
    override fun loadState(value:FtckbSettingsState) { state=value }

    fun snapshot():FtckbSettingsState=FtckbSettingsState().also { copy ->
        copy.team=state.team
        copy.season=state.season
        copy.provider=state.provider
        copy.configPath=state.configPath
        copy.knowledgePath=state.knowledgePath
    }

    fun apply(value:FtckbSettingsState) {
        state.team=value.team.trim()
        state.season=value.season.trim()
        state.provider=value.provider.trim()
        state.configPath=value.configPath.trim()
        state.knowledgePath=value.knowledgePath.trim()
    }

    companion object {
        fun of(project:Project):FtckbSettings=project.getService(FtckbSettings::class.java)
    }
}
