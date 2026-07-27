package com.mypi.plugin

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * SendSelectionAction 平台集成测试(Tier2)。
 *
 * 方案转向后:actionPerformed 调 PiTerminalService.inject(依赖终端 + Messages 弹框),
 * 平台测试难可靠覆盖,留手动验证;此处只测 update 灰显逻辑(决策#7:有 editor 即启用,
 * 无选区也允许发"文件名:行号")。
 */
class SendSelectionActionTest : BasePlatformTestCase() {

    /** 手搓 DataContext:提供 PROJECT/EDITOR/VIRTUAL_FILE,用于构造 AnActionEvent。 */
    private fun makeEvent(): AnActionEvent {
        val editor = myFixture.editor
        val file = myFixture.file?.virtualFile
        val dc = object : DataContext {
            override fun getData(dataId: String): Any? = when (dataId) {
                CommonDataKeys.EDITOR.name -> editor
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> file
                else -> null
            }
        }
        return AnActionEvent.createFromAnAction(SendSelectionAction(), null, "PiTest", dc)
    }

    fun testUpdateEnabledWhenSelectionExists() {
        myFixture.configureByText("App.java", "class App { <selection>int x = 1;</selection> }")
        val event = makeEvent()
        SendSelectionAction().update(event)
        assertTrue("有选区时动作应启用", event.presentation.isEnabled)
    }

    fun testUpdateEnabledWithoutSelection() {
        // 决策#7:无选区也启用(发"文件名:行号"定位),不再灰显
        myFixture.configureByText("App.java", "class App { int x = 1; }")
        val event = makeEvent()
        SendSelectionAction().update(event)
        assertTrue("无选区时动作也应启用(发定位)", event.presentation.isEnabled)
    }

    fun testUpdateDisabledWithoutEditor() {
        // 无 editor 时 actionPerformed 无法工作,应灰显
        val dc = object : DataContext {
            override fun getData(dataId: String): Any? =
                if (dataId == CommonDataKeys.PROJECT.name) project else null
        }
        val event = AnActionEvent.createFromAnAction(SendSelectionAction(), null, "PiTest", dc)
        SendSelectionAction().update(event)
        assertFalse("无 editor 时动作应灰显", event.presentation.isEnabled)
    }
}
