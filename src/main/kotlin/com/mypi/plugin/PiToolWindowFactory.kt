package com.mypi.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 右侧 Pi 工具窗工厂。
 *
 * createToolWindowContent 只建面板 Content(状态 Label 兜底);启动/聚焦 pi 的逻辑由
 * [PiTerminalService] 的 init 监听 [com.intellij.openapi.wm.ToolWindowManagerListener]
 * (Pi 工具窗显示事件)处理——这样**每次点 Pi 图标**都触发(不止首次):
 * pi 已开→聚焦终端,未开→启动新 pi,成功后隐藏右侧 Pi 面板。
 *
 * 决策更新(哥反馈):解决"手动退出 pi 后再点图标,只弹面板无法重启"的问题。
 */
class PiToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val app = ApplicationManager.getApplication()
        // 触发 PiTerminalService 创建 → init 注册 ToolWindow 显示监听(每次点图标都跑 openPi)
        val service = project.service<PiTerminalService>()

        // 面板内容最小:状态 Label(启动中/已就绪/启动失败:...)兜底显示
        val statusLabel = JLabel("正在启动 pi...", SwingConstants.CENTER)
        service.onStatus = { msg -> app.invokeLater { statusLabel.text = msg } }
        val panel = JPanel(BorderLayout()).apply { add(statusLabel, BorderLayout.CENTER) }
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel, "", false)
        )
        // 启动/聚焦 pi 由 PiTerminalService 的 ToolWindowManagerListener 处理(每次点图标都触发)
    }
}
