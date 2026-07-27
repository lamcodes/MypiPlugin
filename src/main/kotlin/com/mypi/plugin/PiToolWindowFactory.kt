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
 * 右侧 Pi 工具窗工厂。决策更新(哥反馈):点 Pi 图标 = 直接启动 pi,不停留面板。
 *
 * [createToolWindowContent] 先接好状态回调,再调 [PiTerminalService.openPi](启动 pi 终端 + 聚焦底部 Terminal);
 * 启动**成功**才隐藏右侧面板(让用户专注底部 pi 终端),**失败**则保留面板显示错误(如"未找到 pi")。
 *
 * 注:首次点图标触发 createToolWindowContent(自动 openPi);后续 show 复用 Content,不重复 openPi
 * (pi 已跑则聚焦终端,见 [PiTerminalService.openPi] 的"已开则聚焦"分支)。
 */
class PiToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val app = ApplicationManager.getApplication()
        val service = project.service<PiTerminalService>()

        // 先建状态 Label + 接 onStatus(必须在 openPi 之前,否则 openPi 内 notify 时回调还是 null)
        val statusLabel = JLabel("正在启动 pi...", SwingConstants.CENTER)
        service.onStatus = { msg -> app.invokeLater { statusLabel.text = msg } }
        val panel = JPanel(BorderLayout()).apply { add(statusLabel, BorderLayout.CENTER) }
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel, "", false)
        )

        // 点图标 = 启动 pi(openPi 内激活并聚焦底部 Terminal 的 Pi tab)
        val startErr = service.openPi()

        // 成功才隐藏右侧面板(让用户专注底部 pi 终端);失败保留面板显示错误信息
        app.invokeLater {
            if (startErr.isEmpty()) toolWindow.hide(null)
        }
    }
}
