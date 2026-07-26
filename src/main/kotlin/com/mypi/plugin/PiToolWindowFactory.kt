package com.mypi.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * 右侧 Pi 聊天面板工厂。MVP:只读文本区流式显示 Pi 回复。
 */
class PiToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PiChatPanel()
        val app = ApplicationManager.getApplication()
        val service = project.service<PiService>()

        // 启动状态反馈:首次打开订阅 onStarted;若已启动(重开面板)直接显示就绪
        if (service.rpc.isStarted()) {
            panel.append("pi 已就绪\n\n")
        } else {
            panel.append("正在启动 pi...\n")
            service.rpc.onStarted = { ok ->
                app.invokeLater {
                    panel.append(if (ok) "pi 已就绪\n\n" else "pi 启动失败,见下方错误\n\n")
                }
            }
        }
        // 接线:Pi 的文本增量 → 面板(后台线程回调,切回 EDT 更新 Swing)
        service.rpc.onTextDelta = { delta -> app.invokeLater { panel.append(delta) } }
        // 错误回调:启动失败/发送失败时显示,避免静默
        service.rpc.onError = { err -> app.invokeLater { panel.append("\n[错误] ${err.message ?: err}\n") } }
        // 断开回调:pi 进程非主动终止时提示用户(下次发送会经 sendPrompt 自动重启)
        service.rpc.onTerminated = { cause ->
            app.invokeLater {
                val msg = if (cause == null) "pi 已断开(下次发送将自动重启)"
                          else "pi 异常断开: ${cause.message ?: cause}"
                panel.append("\n[$msg]\n")
            }
        }

        val content = com.intellij.ui.content.ContentFactory.getInstance()
            .createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 聊天面板:顶部只读文本区(流式追加回复),底部预留输入栏。
 */
class PiChatPanel : JPanel() {

    val outputArea: JBTextArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        layout = BorderLayout()
        add(JScrollPane(outputArea), BorderLayout.CENTER)
    }

    /** 追加一段回复文本(必须在 EDT 调用)。 */
    fun append(text: String) {
        outputArea.append(text)
        // 流式追加后把光标移到末尾,使视图自动滚动到底部(EDT 内操作,安全)
        outputArea.caretPosition = outputArea.document.length
    }
}
