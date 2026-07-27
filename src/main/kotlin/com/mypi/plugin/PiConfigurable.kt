package com.mypi.plugin

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Pi 配置页:提供文本框编辑 pi 可执行路径。
 *
 * - 空值表示走默认 PATH 查找(向后兼容)。
 * - 非空值则把该路径作为 pi 可执行文件直接启动。
 *
 * 构造注入 Project:projectConfigurable 由平台注入项目实例。
 */
class PiConfigurable(private val project: Project) : Configurable {

    internal val pathField = JBTextField()

    override fun getDisplayName(): String = "Pi"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        // 中文标签:说明空值语义,降低用户配置门槛
        val labeled = LabeledComponent.create(pathField, "pi 可执行路径(留空则使用 PATH 中的 pi):")
        panel.add(labeled, BorderLayout.NORTH)
        return panel
    }

    /** 是否有未保存改动:文本框内容与持久化值不一致即为已修改。 */
    override fun isModified(): Boolean {
        return pathField.text != PiSettings.getInstance(project).piPath
    }

    /** 应用:trim 后写回 PiSettings,并同步 UI 文本(使 apply 后 isModified 归零,"应用"按钮正确熄灭);空串等价未配置。 */
    override fun apply() {
        val trimmed = pathField.text.trim()
        PiSettings.getInstance(project).piPath = trimmed
        pathField.text = trimmed
    }

    /** 重置:把持久化值载入文本框(进入配置页时调用)。 */
    override fun reset() {
        pathField.text = PiSettings.getInstance(project).piPath
    }
}
