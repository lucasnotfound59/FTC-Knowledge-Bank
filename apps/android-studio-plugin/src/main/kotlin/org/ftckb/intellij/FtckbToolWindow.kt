package org.ftckb.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class FtckbToolWindowFactory:ToolWindowFactory {
    override fun createToolWindowContent(project:Project,toolWindow:ToolWindow) {
        val panel=FtckbToolWindow(project,toolWindow)
        val content=ContentFactory.getInstance().createContent(panel,"",false)
        toolWindow.contentManager.addContent(content)
    }
}

class FtckbToolWindow(private val project:Project,toolWindow:ToolWindow):JPanel(BorderLayout()) {
    private val service get() = FtckbService.of(project)
    private val status=JLabel("正在初始化…")
    private val messages=JPanel().apply { layout=BoxLayout(this,BoxLayout.Y_AXIS) }
    private val scroll=JScrollPane(messages).apply {
        verticalScrollBarPolicy=JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        border=BorderFactory.createEmptyBorder()
    }
    private val input=JTextArea(3,40).apply { lineWrap=true; wrapStyleWord=true }
    private val send=JButton("发送")

    init {
        status.border=BorderFactory.createEmptyBorder(4,8,4,8)

        val toolbar=JPanel().apply { layout=BoxLayout(this,BoxLayout.X_AXIS); border=BorderFactory.createEmptyBorder(0,8,6,8) }
        toolbar.add(button("询问模式") { service.setModeAsk(); refreshStatus() })
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(button("编辑模式") { switchToEdit() })
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(button("撤销") { service.undo { error -> afterHistory(error,"撤销完成") } })
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(button("放弃") { service.discard { error -> afterHistory(error,"放弃完成") } })
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(button("显示差异") { showDiff() })
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(button("保存会话") { saveSession() })
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(button("清空对话") { service.clearConversation(); messages.removeAll(); refreshStatus() })
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(button("设置") { openSettings() })

        val north=JPanel().apply { layout=BoxLayout(this,BoxLayout.Y_AXIS) }
        north.add(status)
        north.add(toolbar)
        add(north,BorderLayout.NORTH)
        add(scroll,BorderLayout.CENTER)

        val composer=JPanel(BorderLayout(4,0)).apply { border=BorderFactory.createEmptyBorder(6,8,6,8) }
        composer.add(JScrollPane(input),BorderLayout.CENTER)
        composer.add(send,BorderLayout.EAST)
        add(composer,BorderLayout.SOUTH)

        send.addActionListener { submit() }
        input.addKeyListener(object:java.awt.event.KeyAdapter() {
            override fun keyPressed(event:java.awt.event.KeyEvent) {
                if (event.keyCode==java.awt.event.KeyEvent.VK_ENTER && !event.isShiftDown) {
                    event.consume()
                    submit()
                }
            }
        })
        minimumSize=Dimension(320,200)
        service.initializeAsync { error ->
            if (error!=null) append("错误",error,true)
            refreshStatus()
        }
        refreshStatus()
    }

    private fun button(label:String,action:()->Unit):JButton=JButton(label).apply {
        addActionListener { action() }
    }

    private fun submit() {
        val question=input.text.trim()
        if (question.isEmpty()) return
        input.text=""
        append("你",question)
        send.isEnabled=false
        service.submit(question) { outcome ->
            send.isEnabled=true
            when (outcome) {
                is SubmitOutcome.Answered -> appendAnswer(outcome.answer)
                is SubmitOutcome.Edited -> {
                    append("助手","已完成修改："+outcome.summary+"\n文件："+outcome.changedPaths.joinToString(", "))
                    val violations=service.checkChanges()
                    if (violations.isEmpty()) append("助手","标准检查：通过")
                    else violations.forEach { append("标准检查",it,true) }
                }
                is SubmitOutcome.Failed -> append("错误","[${outcome.code}] ${outcome.message}",true)
            }
            refreshStatus()
        }
    }

    private fun appendAnswer(answer:org.ftckb.agent.AgentAnswer) {
        if (answer.claims.isEmpty()) append("助手","（空回答）")
        answer.claims.forEach { claim ->
            val kind=when (claim.kind) {
                org.ftckb.agent.ClaimKind.APPROVED_RULE -> "已批准规则"
                org.ftckb.agent.ClaimKind.CODE_OBSERVATION -> "代码观察"
                org.ftckb.agent.ClaimKind.MODEL_INFERENCE -> "模型推断"
                org.ftckb.agent.ClaimKind.INSUFFICIENT_EVIDENCE -> "证据不足"
            }
            val citations=if (claim.citations.isEmpty()) "" else "\n引用："+claim.citations.joinToString(" ")
            append(kind,claim.text+citations)
        }
    }

    private fun append(role:String,text:String,error:Boolean=false) {
        ApplicationManager.getApplication().invokeLater {
            val bubble=JPanel().apply { layout=BoxLayout(this,BoxLayout.Y_AXIS) }
            val roleLabel=JLabel(role).apply {
                font=font.deriveFont(java.awt.Font.BOLD,font.size2D-1f)
            }
            val body=JTextArea().apply {
                this.text=text
                isEditable=false
                lineWrap=true
                wrapStyleWord=true
                background=null
                border=null
                maximumSize=Dimension(Int.MAX_VALUE,Int.MAX_VALUE)
            }
            if (error) roleLabel.foreground=java.awt.Color(178,34,34)
            bubble.add(roleLabel)
            bubble.add(body)
            bubble.border=BorderFactory.createEmptyBorder(4,0,6,0)
            bubble.alignmentX=Component.LEFT_ALIGNMENT
            messages.add(bubble)
            messages.revalidate()
            messages.repaint()
            scroll.verticalScrollBar.value=scroll.verticalScrollBar.maximum
        }
    }

    private fun switchToEdit() {
        val refusal=service.setModeEdit()
        if (refusal!=null) append("错误",refusal,true)
        refreshStatus()
    }

    private fun afterHistory(error:String?,success:String) {
        ApplicationManager.getApplication().invokeLater {
            if (error==null) append("助手",success) else append("错误",error,true)
            refreshStatus()
        }
    }

    private fun showDiff() {
        val files=service.changedFiles()
        if (files.isEmpty()) {
            append("助手","没有 Agent 改动。")
            return
        }
        val factory=com.intellij.diff.DiffContentFactory.getInstance()
        files.forEach { file ->
            val change=service.fileChange(file) ?: return@forEach
            val request=com.intellij.diff.requests.SimpleDiffRequest(
                file,
                factory.create(project,change.first.orEmpty()),
                factory.create(project,change.second.orEmpty()),
                "修改前","修改后",
            )
            com.intellij.diff.DiffManager.getInstance().showDiff(project,request,com.intellij.diff.DiffDialogHints.FRAME)
        }
    }

    private fun saveSession() {
        executorSave { error ->
            ApplicationManager.getApplication().invokeLater {
                if (error==null) append("助手","会话已保存。") else append("错误",error,true)
            }
        }
    }

    private fun executorSave(onDone:(String?)->Unit) {
        java.util.concurrent.Executors.newSingleThreadExecutor().submit {
            val error=runCatching { service.sessionSave() }.exceptionOrNull()?.message ?: null
            onDone(error)
        }
    }

    private fun openSettings() {
        val state=service.settingsSnapshot()
        val dialog=FtckbSettingsDialog(project,state) {
            val error=service.reconfigure(it)
            if (error!=null) append("错误",error,true)
            refreshStatus()
        }
        dialog.show()
    }

    private fun refreshStatus() {
        ApplicationManager.getApplication().invokeLater { status.text=service.statusText() }
    }
}
