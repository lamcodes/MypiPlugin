package com.mypi.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PiSettings 持久化状态测试(Tier1,JUnit 5 jupiter)。
 *
 * 裸实例化 PiSettings(无参构造,不依赖平台 Project/DI),
 * 验证 PersistentStateComponent 自持久化模式的默认值与 getState/loadState 契约。
 */
class PiSettingsStateTest {

    @Test
    fun `getState 返回自身`() {
        val s = PiSettings()
        // 自持久化模式:getState 返回 this,由平台序列化其 public bean 属性
        assertSame(s, s.state)
    }

    @Test
    fun `默认字段值符合预期`() {
        val s = PiSettings()
        assertEquals("", s.piPath, "未配置时 piPath 应为空串,等价走默认 PATH")
        assertTrue(s.mcpEnabled, "默认应启用 IDE MCP 集成")
        assertTrue(s.mcpDedupEnabled, "默认应启用 MCP 去重检测")
        assertFalse(s.perfLoggingEnabled, "默认应关闭性能计时日志")
        assertEquals("", s.selectionFormat, "默认选区格式应为空串(等价 COLON_LINE)")
    }

    @Test
    fun `loadState 拷贝全部字段且保持 this 身份不变`() {
        val s = PiSettings()
        val loaded = PiSettings().apply {
            piPath = "/usr/local/bin/pi"
            mcpEnabled = false
            mcpDedupEnabled = false
            perfLoggingEnabled = true
            selectionFormat = SelectionFormat.GITHUB_ANCHOR.name
        }

        s.loadState(loaded)

        assertEquals("/usr/local/bin/pi", s.piPath, "loadState 应逐字段拷贝,不直接替换引用")
        assertFalse(s.mcpEnabled)
        assertFalse(s.mcpDedupEnabled)
        assertTrue(s.perfLoggingEnabled)
        assertEquals("GITHUB_ANCHOR", s.selectionFormat)
        assertSame(s, s.state, "loadState 后 getState 仍返回同一实例(身份不变)")
    }

    @Test
    fun `loadState 空串等价未配置`() {
        val s = PiSettings().apply { piPath = "/old/pi" }
        s.loadState(PiSettings().apply { piPath = "" })
        assertEquals("", s.piPath)
    }
}
