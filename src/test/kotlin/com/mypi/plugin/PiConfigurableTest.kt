package com.mypi.plugin

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * PiConfigurable 平台集成测试(Tier2)。
 * 覆盖 displayName、reset/apply/isModified round-trip、apply 的 trim 行为、空串等价未配置。
 */
class PiConfigurableTest : BasePlatformTestCase() {

    fun testDisplayName() {
        assertEquals("Pi", PiConfigurable(project).displayName)
    }

    fun testResetApplyIsModifiedRoundTrip() {
        val configurable = PiConfigurable(project)
        val settings = project.service<PiSettings>()

        settings.piPath = "/orig/pi"
        configurable.reset() // 从 settings 载入文本框
        assertEquals("/orig/pi", configurable.pathField.text)
        assertFalse("刚 reset,文本框与 settings 一致,不应判为已修改", configurable.isModified)

        configurable.pathField.text = "  /new/pi  " // 两端带空格,验证 apply 的 trim
        assertTrue("文本框与 settings 不一致应判为已修改", configurable.isModified)

        configurable.apply()
        assertEquals("/new/pi", settings.piPath) // trim 后写回
        assertFalse("apply 后应恢复一致", configurable.isModified)
    }

    fun testEmptyPathEqualsUnconfigured() {
        val configurable = PiConfigurable(project)
        configurable.pathField.text = ""
        configurable.apply()
        assertEquals("", project.service<PiSettings>().piPath)
    }
}
