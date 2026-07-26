package com.mypi.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages

/**
 * 快捷键动作:把当前选区(文件名 + 起止行号 + 选中文本)作为上下文发给 Pi。
 * 默认快捷键 Ctrl+Alt+P(见 plugin.xml)。
 */
class SendSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        // 抓选区
        val selModel = editor.selectionModel
        val selectedText = selModel.selectedText
        if (selectedText.isNullOrEmpty()) {
            Messages.showInfoMessage("请先选中一段代码。", "Pi")
            return
        }

        // 文件名 + 起止行号(0-based → 1-based 展示)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val fileName = file?.name ?: "未知文件"
        val doc = editor.document
        val startLine = doc.getLineNumber(selModel.selectionStart) + 1
        val endLine = doc.getLineNumber(selModel.selectionEnd) + 1

        // 构造 prompt 并经 project service 发给 Pi
        // (sendPrompt 内部会在 IO 协程自动确保 pi 已启动,不在 EDT 调 start)
        val prompt = SelectionContextBuilder.build(fileName, selectedText, startLine, endLine)
        project.service<PiService>().rpc.sendPrompt(prompt)
    }

    override fun update(e: AnActionEvent) {
        // 必须轻量:只看编辑器是否存在 + 选区是否非空,严禁读 document 等重活,避免拖慢 UI 刷新
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = !editor?.selectionModel?.selectedText.isNullOrEmpty()
        e.presentation.isEnabledAndVisible = editor != null && hasSelection
    }
}
