package com.mypi.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project 级持久化设置:保存用户配置的 pi 可执行路径和 MCP 集成开关。
 *
 * - piPath 非空:PiRpcProcess.start() 以其作为可执行路径,不再依赖 PATH。
 * - piPath 为空:回退到原有默认查找(Windows 走 cmd /c pi,其它平台直接 pi),
 *   保证老用户升级后行为不变。
 * - mcpEnabled:是否把 IDE MCP Server 注入 Pi,让 Pi 获得 read_file/search/build/debug 等工具。
 */
@Service(Service.Level.PROJECT)
@State(name = "PiSettings", storages = [Storage("pi-settings.xml")])
class PiSettings : PersistentStateComponent<PiSettings> {

    /** 用户配置的 pi 路径;空串表示未配置(走默认 PATH)。@Volatile 保证 EDT 写、IO 线程读的可见性。 */
    @Volatile
    var piPath: String = ""

    /** 是否启用 IDE MCP 集成。默认开启,用户可在 Settings → Tools → Pi 关闭。 */
    @Volatile
    var mcpEnabled: Boolean = true

    /** 是否开启去重检测：检测 Pi 是否已通过 imports(如 claude-code)获得 IDEA MCP,避免重复注入。默认开启。 */
    @Volatile
    var mcpDedupEnabled: Boolean = true

    /** 是否开启性能计时日志：在 idea.log 输出各启动阶段耗时,用于排查启动慢问题。默认关闭。 */
    @Volatile
    var perfLoggingEnabled: Boolean = false

    /** 选区格式:"COLON_LINE"=冒号风格(@path:行号);"GITHUB_ANCHOR"=GitHub 风格(@path#L行号)。空串等价 COLON_LINE。 */
    @Volatile
    var selectionFormat: String = ""

    override fun getState(): PiSettings = this

    override fun loadState(state: PiSettings) {
        // 平台反序列化出一个新实例后回调,逐字段拷贝,避免直接替换 this 引用
        piPath = state.piPath
        mcpEnabled = state.mcpEnabled
        mcpDedupEnabled = state.mcpDedupEnabled
        perfLoggingEnabled = state.perfLoggingEnabled
        selectionFormat = state.selectionFormat
    }

    companion object {
        fun getInstance(project: Project): PiSettings = project.service()
    }
}
