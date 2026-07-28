package com.mypi.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
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
 * inject 结果的密封类型:让调用方(SendSelectionAction)用 when 穷举分支处理,
 * 取代原先的字符串魔法值(null/"未开"/"注入失败:..."),避免调用方漏判或写错信号串。
 */
sealed interface InjectResult {
    /** 注入成功 */
    data object Success : InjectResult
    /** pi 终端未开启,由调用方提示用户去点启动按钮 */
    data object TerminalNotOpen : InjectResult
    /** 注入过程异常,携带错误信息 */
    data class Failed(val message: String) : InjectResult
}

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

    private val logger = thisLogger()

    init {
        // 监听 Pi 工具窗"被显示"(每次点 Pi 图标都触发,不止首次)。
        // openPi 内部:pi 已开→聚焦终端;未开→启动新 pi。成功才隐藏 Pi 面板(失败保留显示错误)。
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id != "Pi") return
                    // invokeLater 不可省:toolWindowShown 在平台"显示工具窗"流程中同步触发,
                    // 此处若直接 openPi(建终端 tab + 激活)会与显示流程重入/抢焦点;
                    // 推迟到当前 EDT 事件结束后执行,确保 service init 已完成、显示流程已收尾。
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
     * 新建 Pi 前会先注入 IDE MCP Server 连接信息([PiMcpConfigService.injectIdeMcpServer]),
     * 让 Pi 启动后自动获得 read_file / search / build / debug 等工具。
     * 已开时跳过注入(Pi 运行中改配置不生效,需重启)。
     *
     * @return 错误信息;空串表示成功
     */
    fun openPi(): String {
        val perfLog = PiSettings.getInstance(project).perfLoggingEnabled
        val totalStart = if (perfLog) System.currentTimeMillis() else 0
        if (piView != null) { // 单一会话:已开则聚焦 + 激活终端,不重复建
            focusAndActivatePi()
            return ""
        }

        // 新建 Pi 前注入 IDE MCP Server 连接信息(让 Pi 获得 IDE 工具)
        val injectStart = if (perfLog) System.currentTimeMillis() else 0
        injectMcpConfig()
        if (perfLog) {
            logger.info("openPi: injectMcpConfig 耗时 ${System.currentTimeMillis() - injectStart}ms")
        }

        val resolveStart = if (perfLog) System.currentTimeMillis() else 0
        val piExe = resolvePiExecutable() ?: run {
            val msg = "启动失败:未找到 pi,请在 Settings→Tools→Pi 配置路径"
            notify(msg)
            return msg
        }
        if (perfLog) {
            logger.info("openPi: resolvePiExecutable 耗时 ${System.currentTimeMillis() - resolveStart}ms, piExe=$piExe")
        }

        val createStart = if (perfLog) System.currentTimeMillis() else 0
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
            if (perfLog) {
                logger.info("openPi: createTab+activate 耗时 ${System.currentTimeMillis() - createStart}ms, 总耗时 ${System.currentTimeMillis() - totalStart}ms")
            }
            notify("已就绪")
            ""
        }.getOrElse { e ->
            if (perfLog) {
                logger.warn("openPi: createTab 失败耗时 ${System.currentTimeMillis() - createStart}ms, 总耗时 ${System.currentTimeMillis() - totalStart}ms", e)
            }
            val msg = "启动失败:${e.message ?: e.toString()}"
            notify(msg)
            msg
        }
    }

    /**
     * 注入 IDE MCP Server 配置到 Pi 的 mcp.json。
     * MCP 不可用或用户关闭开关时静默降级,不影响 Pi 正常启动。
     */
    private fun injectMcpConfig() {
        when (val result = PiMcpConfigService.getInstance(project).injectIdeMcpServer()) {
            is PiMcpConfigService.InjectResult.Success -> notify("已注入 IDE MCP,Pi 可使用 IDE 工具")
            is PiMcpConfigService.InjectResult.Failed -> notify("MCP 配置注入失败:${result.message}")
            else -> { /* Disabled / McpNotAvailable / AlreadyConfigured: 静默跳过 */ }
        }
    }

    /**
     * 向 pi 终端注入文本——只填不发送(用户补问题再回车)。
     *
     * @return [InjectResult];调用方按分支决定聚焦或弹框提示
     */
    fun inject(text: String): InjectResult {
        val view = piView ?: return InjectResult.TerminalNotOpen
        return runCatching {
            view.createSendTextBuilder()
                .useBracketedPasteMode()
                .sendEndKeyBeforeText()
                .send(text)
            InjectResult.Success
        }.getOrElse { e ->
            InjectResult.Failed("注入失败:${e.message ?: e.toString()}")
        }
    }

    /** 聚焦到 pi 终端 tab(已开时用)。 */
    fun focusPi() {
        val tab = piTab ?: return
        val content = tab.content
        content.manager?.setSelectedContent(content)
    }

    /**
     * 聚焦并激活 pi 终端:选中 Pi tab + 激活底部 Terminal 工具窗(把键盘焦点交给 pi 输入行)。
     * 注入成功后由 SendSelectionAction 调用,让用户直接在 pi 终端补问题 + 回车,无需手动切焦点。
     */
    fun focusAndActivatePi() {
        focusPi()
        activateTerminal()
    }

    /**
     * 激活底部 Terminal 工具窗(显示并拉到前台),让用户看到 pi 终端。
     */
    private fun activateTerminal() {
        val terminal = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        // activate(回调,autoFocus=true) 而非 show:工具窗激活后聚焦 pi tab 内容组件,
        // 让 pi 输入行接管键盘焦点(show 只显示不聚焦内容,哥反馈打字还在编辑区)。
        terminal.activate {
            // TerminalView.preferredFocusableComponent:终端为焦点场景设计的可聚焦组件(JediTerm 输入区)。
            // content.component 是根组件,焦点遍历走不到输入框(哥反馈打字落到 tab 标题而非输入区)。
            piView?.preferredFocusableComponent?.requestFocusInWindow()
        }
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
