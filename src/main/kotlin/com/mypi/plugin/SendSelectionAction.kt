package com.mypi.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * 快捷键动作(Ctrl+Alt+;):把当前选区或光标定位注入到 pi 终端(只填不发送)。
 *
 * 上下文判断(决策#7):
 *  - 有选区 → [SelectionContextBuilder.build] 产出 "@相对路径#L起-止行"。
 *  - 无选区 → [SelectionContextBuilder.build] 产出 "@相对路径#L光标行"(传 caretLine 两次)。
 *
 * 路径经 [resolvePath] 解析为"相对项目根"(与 pi 启动的工作目录同源);项目外文件降级为裸文件名。
 * 注入经 [PiTerminalService.inject];若 pi 终端未开,弹框提示去点启动按钮(决策#10)。
 */
class SendSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val path = resolvePath(project, file)
        val doc = editor.document
        val selModel = editor.selectionModel
        val selectedText = selModel.selectedText

        // 有选区发范围;无选区发单行(传 caretLine 两次,build 内部合并为 #L单行号)
        val text = if (!selectedText.isNullOrEmpty()) {
            val startLine = doc.getLineNumber(selModel.selectionStart) + 1
            val endLine = doc.getLineNumber(selModel.selectionEnd) + 1
            SelectionContextBuilder.build(path, startLine, endLine)
        } else {
            val caretLine = doc.getLineNumber(editor.caretModel.offset) + 1
            SelectionContextBuilder.build(path, caretLine, caretLine)
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
        // 始终启用(决策#7):无选区也允许,发"@相对路径#L行号"定位;仅需有编辑器
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    /**
     * 解析文件路径为"相对项目根"(与 pi 启动工作目录 project.basePath 同源,pi 视角等价于绝对路径)。
     * 用 [VfsUtilCore.getRelativePath](全程 `/` 规范化),规避 Windows basePath 反斜杠与 file.path
     * 正斜杠混用导致的字符串误判。算不出相对路径(项目外 / jar 内 / 临时文件 / 无项目根)时降级为
     * 裸文件名,再降级"未知文件"。
     *
     * 注:brief 原拟用 `ProjectUtil.guessProjectDir`,但该类所在 platform.ide 模块未被 IntelliJ
     * Platform Gradle Plugin 2.x 默认暴露给插件编译期 classpath;改用 core 层 [LocalFileSystem]
     * 由 `project.basePath` 拿到 baseDir,语义等价(brief KDoc 亦把 project.basePath 标为同源信号)。
     */
    private fun resolvePath(project: Project, file: VirtualFile?): String {
        if (file == null) return "未知文件"
        val basePath = project.basePath ?: return file.name
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return file.name
        return VfsUtilCore.getRelativePath(file, baseDir, '/') ?: file.name
    }
}
