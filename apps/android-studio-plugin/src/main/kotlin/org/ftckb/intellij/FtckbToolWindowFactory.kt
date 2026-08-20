package org.ftckb.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import javax.swing.JPanel

class FtckbToolWindowFactory:ToolWindowFactory {
    override fun createToolWindowContent(project:Project,toolWindow:ToolWindow) {
        val panel=JPanel()
        panel.add(JBLabel("FTC 知识库（骨架）：M3 将接入 Ask/Edit 会话。"))
        val content=com.intellij.ui.content.ContentFactory.getInstance().createContent(panel,"",false)
        toolWindow.contentManager.addContent(content)
    }
}
