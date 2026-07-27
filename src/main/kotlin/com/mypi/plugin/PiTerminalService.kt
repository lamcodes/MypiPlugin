package com.mypi.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.io.File

/**
 * Project 级服务:管理"插件专属 pi 终端"——用 Reworked Terminal 跑 pi,并提供选区注入入口。
 *
 * 职责:
 *  - init 监听 Pi 工具窗显示:每次点 Pi 图标 → [openPi](pi 已开则聚焦终端,未开则启动新 pi),
 *    成功后隐藏右侧 Pi 面板。解决哥反馈的"手动退出 pi 后再点图标无法重启"。
 *  - [openPi]:预检 pi 可执行 → [TerminalToolWindowTabsManager] 建标题 "Pi" 的 tab → 持有 [TerminalView]
 *    + [activateTerminal] 激活底部 Terminal 工具窗。
 *  - [inject]:向 pi 终端"只填不发送"文本(createSendTextBuilder,不调 shouldExecute)。
 *  - 监听 tab 关闭清引用 + 通知面板。
 *
 * 依赖的 Reworked Terminal API 为 @ApiStatus.Experimental(2026.2 仍未 graduated),
 * 调用处用 runCatching 包裹降级,失败时通过 [onStatus] 回调提示,不让插件崩溃。
 */
@Service(Service.Level.PROJECT)
class PiTerminalService(val project: Project) : Disposable {

    init {
        // 监听 Pi 工具窗"被显示"(每次点 Pi 图标都触发,不止首次)。
        // openPi 内部:pi 已开→聚焦终端;未开→启动新 pi。成功才隐藏 Pi 面板(失败保留显示错误)。
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id != "Pi") return
                    ApplicationManager.getApplication().invokeLater {
                        val err = openPi()
                        if (err.isEmpty()) tw.hide(null)
                    }
                }
            }
        )
    }

    /** 当前 pi 终端的 tab(为空表示未启动或已被关闭)。 */
    @Volatile private var piTab: TerminalToolWindowTab? = null

    /** 当前 pi 终端的 TerminalView(注入入口)。 */
    @Volatile private var piView: TerminalView? = null

    /**
     * 状态变化回调:文本状态("未启动"/"已就绪"/"启动失败:..."/"注入失败:...")。
     * 可能在 EDT 或终端线程触发,UI 侧需 invokeLater 切回 EDT 更新 Label。
     */
    @Volatile var onStatus: ((String) -> Unit)? = null

    /** pi 终端是否存活(可注入)。 */
    fun hasOpen(): Boolean = piView != null

    /**
     * 启动 pi 终端。单一会话:已开则聚焦 + 激活终端,不重开;未开则预检后建新 tab。
     * 必须在 EDT 调用(createTabBuilder/createTab 标注 @RequiresEdt)。
     *
     * @return 错误信息;空串表示成功
     */
    fun openPi(): String {
        if (piView != null) { // 单一会话:已开则聚焦 + 激活终端,不重复建
            focusPi()
            activateTerminal()
            return ""
        }
        val piExe = resolvePiExecutable() ?: run {
            val msg = "启动失败:未找到 pi,请在 Settings→Tools→Pi 配置路径"
            notify(msg)
            return msg
        }
        return runCatching {
            val manager = TerminalToolWindowTabsManager.getInstance(project)
            val tab = manager.createTabBuilder()
                .tabName("Pi")
                .workingDirectory(project.basePath ?: System.getProperty("user.dir"))
                .shellCommand(listOf(piExe))
                .createTab()
            piTab = tab
            piView = tab.view
            registerCloseListener(tab.content)
            activateTerminal()
            notify("已就绪")
            ""
        }.getOrElse { e ->
            val msg = "启动失败:${e.message ?: e.toString()}"
            notify(msg)
            msg
        }
    }

    /**
     * 向 pi 终端注入文本——只填不发送(用户补问题再回车)。
     *
     * @return null 表示成功;非空表示错误信号("未开"由调用方提示"点启动按钮";其余为注入异常)
     */
    fun inject(text: String): String? {
        val view = piView ?: return "未开"
        return runCatching {
            view.createSendTextBuilder()
                .useBracketedPasteMode()
                .sendEndKeyBeforeText()
                .send(text)
            null
        }.getOrElse { e ->
            "注入失败:${e.message ?: e.toString()}"
        }
    }

    /** 聚焦到 pi 终端 tab(已开时用)。 */
    fun focusPi() {
        val tab = piTab ?: return
        val content = tab.content
        content.manager?.setSelectedContent(content)
    }

    /**
     * 激活底部 Terminal 工具窗(显示并拉到前台),让用户看到 pi 终端。
     */
    private fun activateTerminal() {
        val terminal = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        terminal.show(null)
    }

    /**
     * 预检 pi 可执行:优先用 PiSettings 配的路径;空则扫 PATH。
     * Windows 优先 .exe,其次 .cmd/.bat;其它平台找 "pi"。
     */
    private fun resolvePiExecutable(): String? {
        val configured = PiSettings.getInstance(project).piPath.trim()
        if (configured.isNotEmpty() && File(configured).canExecute()) return configured
        val pathEnv = System.getenv("PATH") ?: return null
        val isWindows = System.getProperty("os.name", "").lowercase().startsWith("windows")
        val candidates = if (isWindows) listOf("pi.exe", "pi.cmd", "pi.bat", "pi") else listOf("pi")
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            for (name in candidates) {
                val f = File(dir, name)
                if (f.canExecute()) return f.absolutePath
            }
        }
        return null
    }

    /**
     * 监听 Pi tab 关闭:用户关掉 pi 终端 tab 时清引用 + 通知面板"未启动"。
     */
    private fun registerCloseListener(content: Content) {
        val manager = content.manager ?: return
        manager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (event.content === content) {
                    piTab = null
                    piView = null
                    notify("未启动")
                }
            }
        })
    }

    private fun notify(msg: String) {
        onStatus?.invoke(msg)
    }

    override fun dispose() {
        val tab = piTab
        piTab = null
        piView = null
        if (tab != null) {
            runCatching { TerminalToolWindowTabsManager.getInstance(project).closeTab(tab) }
        }
    }

    companion object {
        fun getInstance(project: Project): PiTerminalService = project.service()
    }
}
