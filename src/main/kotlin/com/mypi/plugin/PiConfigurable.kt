package com.mypi.plugin

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Pi 配置页:提供文本框编辑 pi 可执行路径 + MCP 集成开关。
 *
 * - 空值表示走默认 PATH 查找(向后兼容)。
 * - 非空值则把该路径作为 pi 可执行文件直接启动。
 * - MCP 开关:开启后,每次启动 Pi 自动把 IDE MCP Server 注入 Pi,让 Pi 获得
 *   read_file / search / build / debug 等工具(IntelliJ IDEA 2025.2+)。
 *
 * 构造注入 Project:projectConfigurable 由平台注入项目实例。
 */
class PiConfigurable(private val project: Project) : Configurable {

    internal val pathField = JBTextField()
    internal val mcpCheckBox = JBCheckBox("启用 IDE MCP 集成(让 Pi 能读写文件、搜索代码、构建项目、调试等)")
    internal val mcpDedupCheckBox = JBCheckBox("开启去重检测(若 Pi 已通过 claude-code 等 import 获得 IDEA MCP,则跳过重复注入)")
    internal val perfLoggingCheckBox = JBCheckBox("开启性能计时日志(各启动阶段耗时输出到 idea.log,用于排查启动慢)")
    internal val formatCombo = JComboBox(arrayOf("冒号风格 (path:行号)", "GitHub 风格 (@path#L行号)"))

    override fun getDisplayName(): String = "Pi"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridwidth = GridBagConstraints.REMAINDER
        }

        // pi 可执行路径输入框
        val labeled = LabeledComponent.create(pathField, "pi 可执行路径(留空则使用 PATH 中的 pi):")
        panel.add(labeled, gbc)

        // 选区格式下拉框
        val formatLabeled = LabeledComponent.create(formatCombo, "选区格式:")
        panel.add(formatLabeled, gbc)

        // MCP 集成开关
        panel.add(mcpCheckBox, gbc)

        // 去重检测开关
        panel.add(mcpDedupCheckBox, gbc)

        // 性能计时日志开关
        panel.add(perfLoggingCheckBox, gbc)

        // 底部填充
        gbc.weighty = 1.0
        panel.add(JPanel(), gbc)

        return panel
    }

    /** 是否有未保存改动:文本框或复选框与持久化值不一致即为已修改。 */
    override fun isModified(): Boolean {
        val settings = PiSettings.getInstance(project)
        return pathField.text != settings.piPath ||
            mcpCheckBox.isSelected != settings.mcpEnabled ||
            mcpDedupCheckBox.isSelected != settings.mcpDedupEnabled ||
            perfLoggingCheckBox.isSelected != settings.perfLoggingEnabled ||
            formatCombo.selectedIndex != formatIndexFromSetting(settings.selectionFormat)
    }

    /**
     * 应用:trim 后写回 PiSettings,并同步 UI 文本(使 apply 后 isModified 归零,"应用"按钮正确熄灭);空串等价未配置。
     * 若用户关闭了 MCP 集成,同步清除 mcp.json 中的 intellij 条目(避免残留失效 URL)。
     */
    override fun apply() {
        val settings = PiSettings.getInstance(project)
        val trimmed = pathField.text.trim()
        val prevMcpEnabled = settings.mcpEnabled
        settings.piPath = trimmed
        settings.mcpEnabled = mcpCheckBox.isSelected
        settings.mcpDedupEnabled = mcpDedupCheckBox.isSelected
        settings.perfLoggingEnabled = perfLoggingCheckBox.isSelected
        settings.selectionFormat = if (formatCombo.selectedIndex == 0) "" else SelectionFormat.GITHUB_ANCHOR.name
        pathField.text = trimmed
        mcpCheckBox.isSelected = settings.mcpEnabled
        mcpDedupCheckBox.isSelected = settings.mcpDedupEnabled
        perfLoggingCheckBox.isSelected = settings.perfLoggingEnabled

        // 用户从「启用」切换到「禁用」→ 清除 mcp.json 中的 intellij 条目
        if (prevMcpEnabled && !settings.mcpEnabled) {
            val removed = PiMcpConfigService.getInstance(project).removeIdeMcpServer()
            if (removed) {
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    "已清除 IDE MCP 配置,后续启动 Pi 不再连接 IDE MCP Server。",
                    "Pi"
                )
            }
        }
    }

    /** 重置:把持久化值载入文本框和复选框(进入配置页时调用)。 */
    override fun reset() {
        val settings = PiSettings.getInstance(project)
        pathField.text = settings.piPath
        mcpCheckBox.isSelected = settings.mcpEnabled
        mcpDedupCheckBox.isSelected = settings.mcpDedupEnabled
        perfLoggingCheckBox.isSelected = settings.perfLoggingEnabled
        formatCombo.selectedIndex = formatIndexFromSetting(settings.selectionFormat)
    }

    /** 将 selectionFormat 持久化值映射为下拉框索引:空串/"COLON_LINE"→0, "GITHUB_ANCHOR"→1 */
    private fun formatIndexFromSetting(raw: String): Int {
        return if (raw == SelectionFormat.GITHUB_ANCHOR.name) 1 else 0
    }
}
