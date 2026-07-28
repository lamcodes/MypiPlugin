package com.mypi.plugin

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * SendSelectionAction 平台集成测试(Tier2)。
 *
 * 方案转向后:actionPerformed 调 PiTerminalService.inject(依赖终端 + Messages 弹框),
 * 平台测试难可靠覆盖,留手动验证;此处只测 update 灰显逻辑(决策#7:有 editor 即启用,
 * 无选区也允许发"@相对路径#L行号")。
 *
 * 用 [TestActionEvent.createTestEvent](非 deprecated,取代已移除的 createFromAnAction)
 * + 平台 [DataManager.getDataContext] 获取真实 DataContext(不手写,避免 override
 * deprecated getData(String) 导致的 IDE ERROR)。
 */
class SendSelectionActionTest : BasePlatformTestCase() {

    /** 用平台 DataManager 获取 editor component 的 data context(含 EDITOR/PROJECT)。 */
    private fun makeEvent(): AnActionEvent {
        val dataContext = DataManager.getInstance().getDataContext(myFixture.editor.component)
        return TestActionEvent.createTestEvent(dataContext)
    }

    fun testUpdateEnabledWhenSelectionExists() {
        myFixture.configureByText("App.java", "class App { <selection>int x = 1;</selection> }")
        val event = makeEvent()
        SendSelectionAction().update(event)
        assertTrue("有选区时动作应启用", event.presentation.isEnabled)
    }

    fun testUpdateEnabledWithoutSelection() {
        // 决策#7:无选区也启用(发"@相对路径#L行号"定位),不再灰显
        myFixture.configureByText("App.java", "class App { int x = 1; }")
        val event = makeEvent()
        SendSelectionAction().update(event)
        assertTrue("无选区时动作也应启用(发定位)", event.presentation.isEnabled)
    }

    fun testUpdateDisabledWithoutEditor() {
        // 无 editor 时 actionPerformed 无法工作;EMPTY_CONTEXT 不含 EDITOR → update 灰显
        val event = TestActionEvent.createTestEvent(DataContext.EMPTY_CONTEXT)
        SendSelectionAction().update(event)
        assertFalse("无 editor 时动作应灰显", event.presentation.isEnabled)
    }
}
