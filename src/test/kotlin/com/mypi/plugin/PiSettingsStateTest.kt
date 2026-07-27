package com.mypi.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * PiSettings 持久化状态测试(Tier1,JUnit 5 jupiter)。
 *
 * 裸实例化 PiSettings(无参构造,不依赖平台 Project/DI),
 * 验证 PersistentStateComponent 自持久化模式的 getState/loadState 契约。
 */
class PiSettingsStateTest {

    @Test
    fun `getState 返回自身`() {
        val s = PiSettings()
        // 自持久化模式:getState 返回 this,由平台序列化其 public bean 属性(piPath)
        assertSame(s, s.state)
    }

    @Test
    fun `默认 piPath 为空串`() {
        assertEquals("", PiSettings().piPath, "未配置时 piPath 应为空串,等价走默认 PATH")
    }

    @Test
    fun `loadState 拷贝字段且保持 this 身份不变`() {
        val s = PiSettings()
        val loaded = PiSettings().apply { piPath = "/usr/local/bin/pi" }

        s.loadState(loaded)

        assertEquals("/usr/local/bin/pi", s.piPath, "loadState 应逐字段拷贝,不直接替换引用")
        assertSame(s, s.state, "loadState 后 getState 仍返回同一实例(身份不变)")
    }

    @Test
    fun `loadState 空串等价未配置`() {
        val s = PiSettings()
        s.loadState(PiSettings().apply { piPath = "" })
        assertEquals("", s.piPath)
    }
}
