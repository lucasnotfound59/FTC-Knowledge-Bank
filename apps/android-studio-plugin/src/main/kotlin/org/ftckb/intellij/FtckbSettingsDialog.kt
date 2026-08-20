package org.ftckb.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class FtckbSettingsDialog(
    private val project:Project,
    initial:FtckbSettingsState,
    private val onApply:(FtckbSettingsState)->Unit
):DialogWrapper(project) {
    private val team=JTextField(initial.team)
    private val season=JTextField(initial.season)
    private val provider=JTextField(initial.provider)
    private val configPath=JTextField(initial.configPath)
    private val knowledgePath=JTextField(initial.knowledgePath)

    init {
        title="FTC 知识库设置"
        init()
    }

    override fun createCenterPanel():JComponent {
        val panel=JPanel(GridBagLayout())
        val constraints=GridBagConstraints().apply {
            insets=Insets(4,4,4,4)
            fill=GridBagConstraints.HORIZONTAL
            anchor=GridBagConstraints.WEST
        }
        fun row(index:Int,label:String,field:JTextField) {
            constraints.gridx=0; constraints.gridy=index; constraints.weightx=0.0
            panel.add(JBLabel(label),constraints)
            constraints.gridx=1; constraints.weightx=1.0
            panel.add(field,constraints)
        }
        row(0,"队伍编号",team)
        row(1,"赛季（YYYY-YYYY）",season)
        row(2,"Provider 名称",provider)
        row(3,"配置文件路径（留空用 ~/.ftckb/config.yaml）",configPath)
        row(4,"知识库路径（留空用插件内置知识库）",knowledgePath)
        return panel
    }

    override fun doOKAction() {
        val dialog=this@FtckbSettingsDialog
        val value=FtckbSettingsState().apply {
            this.team=dialog.team.text.trim()
            this.season=dialog.season.text.trim()
            this.provider=dialog.provider.text.trim()
            this.configPath=dialog.configPath.text.trim()
            this.knowledgePath=dialog.knowledgePath.text.trim()
        }
        if (!org.ftckb.domain.RuleIdentity.isCanonicalTeam(value.team)) {
            com.intellij.openapi.ui.Messages.showErrorDialog("队伍编号必须是纯数字","FTC 知识库设置")
            return
        }
        if (!org.ftckb.domain.RuleIdentity.isCanonicalSeason(value.season)) {
            com.intellij.openapi.ui.Messages.showErrorDialog("赛季必须是 YYYY-YYYY","FTC 知识库设置")
            return
        }
        onApply(value)
        super.doOKAction()
    }
}
