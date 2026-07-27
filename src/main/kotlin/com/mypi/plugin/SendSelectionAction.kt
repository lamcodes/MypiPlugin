package com.mypi.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages

/**
 * 快捷键动作(Ctrl+Alt+;):把当前选区或光标定位注入到 pi 终端(只填不发送)。
 *
 * 上下文判断(决策#7):
 *  - 有选区 → [SelectionContextBuilder.build] 产出"文件名:起-止 + 代码围栏"。
 *  - 无选区 → [SelectionContextBuilder.buildFileLocator] 产出"文件名:光标行号"。
 *
 * 注入经 [PiTerminalService.inject];若 pi 终端未开,弹框提示去点启动按钮(决策#10)。
 */
class SendSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val fileName = file?.name ?: "未知文件"
        val doc = editor.document
        val selModel = editor.selectionModel
        val selectedText = selModel.selectedText

        // 有选区发代码块;无选区发"文件名:光标行号"(0-based → 1-based 展示)
        val text = if (!selectedText.isNullOrEmpty()) {
            val startLine = doc.getLineNumber(selModel.selectionStart) + 1
            val endLine = doc.getLineNumber(selModel.selectionEnd) + 1
            SelectionContextBuilder.build(fileName, startLine, endLine)
        } else {
            val caretLine = doc.getLineNumber(editor.caretModel.offset) + 1
            SelectionContextBuilder.buildFileLocator(fileName, caretLine)
        }

        // 注入到 pi 终端:按 InjectResult 分支决定聚焦或弹框提示(sealed 类型,编译期穷举)
        val service = project.service<PiTerminalService>()
        when (val result = service.inject(text)) {
            InjectResult.Success -> service.focusAndActivatePi() // 注入成功,焦点切到 pi 终端(用户直接补问题+回车)
            InjectResult.TerminalNotOpen -> Messages.showInfoMessage("Pi 终端未开启,请点右侧 Pi 面板的启动按钮", "Pi")
            is InjectResult.Failed -> Messages.showInfoMessage(result.message, "Pi") // 注入失败等异常
        }
    }

    override fun update(e: AnActionEvent) {
        // 始终启用(决策#7):无选区也允许,发"文件名:行号"定位;仅需有编辑器
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }
}
