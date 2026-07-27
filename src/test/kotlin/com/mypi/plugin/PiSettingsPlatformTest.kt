package com.mypi.plugin

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * PiSettings 平台集成测试(Tier2,BasePlatformTestCase,JUnit3 风格)。
 * 验证 Project 级 @Service 的 getInstance 单例性与持久化字段读写。
 */
class PiSettingsPlatformTest : BasePlatformTestCase() {

    fun testGetInstanceIsSingleton() {
        val s1 = project.service<PiSettings>()
        val s2 = project.service<PiSettings>()
        assertSame("同一 Project 下 PiSettings 应为单例", s1, s2)
    }

    fun testDefaultPiPathIsEmpty() {
        assertEquals("", project.service<PiSettings>().piPath)
    }

    fun testPiPathReadWrite() {
        val s = project.service<PiSettings>()
        s.piPath = "/usr/local/bin/pi"
        assertEquals("/usr/local/bin/pi", s.piPath)
    }
}
