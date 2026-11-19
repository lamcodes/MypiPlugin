package com.mypi.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * PiMcpConfigStore 纯文件与 JSON 逻辑测试（Tier1, JUnit 5 jupiter, 零 IntelliJ 依赖）。
 */
class PiMcpConfigStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `首次写入自动创建父目录并写入 intellij MCP 条目`() {
        val piConfig = tempDir.resolve(".pi/agent/mcp.json").toFile()

        val changed = PiMcpConfigStore.upsertIdeMcpServer(piConfig, "http://127.0.0.1:64342/sse")

        assertTrue(changed)
        assertTrue(piConfig.exists())
        val root = Json.parseToJsonElement(piConfig.readText(Charsets.UTF_8)).jsonObject
        val url = root["mcpServers"]!!.jsonObject["intellij"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("http://127.0.0.1:64342/sse", url)
    }

    @Test
    fun `重复写入相同 URL 幂等返回 false`() {
        val piConfig = tempDir.resolve("mcp.json").toFile()
        assertTrue(PiMcpConfigStore.upsertIdeMcpServer(piConfig, "http://127.0.0.1:64342/sse"))
        assertFalse(PiMcpConfigStore.upsertIdeMcpServer(piConfig, "http://127.0.0.1:64342/sse"))
    }

    @Test
    fun `更新 URL 时保留已有其它 server 与顶层字段`() {
        val piConfig = tempDir.resolve("mcp.json").toFile()
        piConfig.writeText(
            """
            {
              "imports": ["claude-code"],
              "mcpServers": {
                "other": { "url": "http://localhost:9000/sse" },
                "intellij": { "url": "http://127.0.0.1:11111/sse" }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val changed = PiMcpConfigStore.upsertIdeMcpServer(piConfig, "http://127.0.0.1:22222/sse")

        assertTrue(changed)
        val root = Json.parseToJsonElement(piConfig.readText(Charsets.UTF_8)).jsonObject
        assertEquals("claude-code", root["imports"]!!.jsonArray[0].jsonPrimitive.content)
        val servers = root["mcpServers"]!!.jsonObject
        assertEquals("http://localhost:9000/sse", servers["other"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:22222/sse", servers["intellij"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `写入前遇到损坏 JSON 抛异常且不覆盖原文件`() {
        val piConfig = tempDir.resolve("mcp.json").toFile()
        val broken = "{ broken json"
        piConfig.writeText(broken, Charsets.UTF_8)

        assertThrows(IllegalStateException::class.java) {
            PiMcpConfigStore.upsertIdeMcpServer(piConfig, "http://127.0.0.1:64342/sse")
        }
        assertEquals(broken, piConfig.readText(Charsets.UTF_8))
    }

    @Test
    fun `写入前遇到非对象根节点抛异常且不覆盖原文件`() {
        val piConfig = tempDir.resolve("mcp.json").toFile()
        piConfig.writeText("[1, 2, 3]", Charsets.UTF_8)

        assertThrows(IllegalStateException::class.java) {
            PiMcpConfigStore.upsertIdeMcpServer(piConfig, "http://127.0.0.1:64342/sse")
        }
        assertEquals("[1, 2, 3]", piConfig.readText(Charsets.UTF_8))
    }

    @Test
    fun `removeIdeMcpServer 仅移除 intellij 并保留其它条目`() {
        val piConfig = tempDir.resolve("mcp.json").toFile()
        piConfig.writeText(
            """
            {
              "imports": ["claude-code"],
              "mcpServers": {
                "other": { "url": "http://localhost:9000/sse" },
                "intellij": { "url": "http://127.0.0.1:64342/sse" }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        assertTrue(PiMcpConfigStore.removeIdeMcpServer(piConfig))
        val root = Json.parseToJsonElement(piConfig.readText(Charsets.UTF_8)).jsonObject
        val servers = root["mcpServers"]!!.jsonObject
        assertFalse(servers.containsKey("intellij"))
        assertEquals("http://localhost:9000/sse", servers["other"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("claude-code", root["imports"]!!.jsonArray[0].jsonPrimitive.content)
        // 再次移除返回 false
        assertFalse(PiMcpConfigStore.removeIdeMcpServer(piConfig))
    }

    @Test
    fun `removeIdeMcpServer 遇到损坏 JSON 安全跳过并触发回调`() {
        val piConfig = tempDir.resolve("mcp.json").toFile()
        val broken = "{ not valid"
        piConfig.writeText(broken, Charsets.UTF_8)
        var parseFailedCalled = false

        val removed = PiMcpConfigStore.removeIdeMcpServer(
            piConfigFile = piConfig,
            onParseFailed = { parseFailedCalled = true }
        )

        assertFalse(removed)
        assertTrue(parseFailedCalled)
        assertEquals(broken, piConfig.readText(Charsets.UTF_8))
    }

    @Test
    fun `去重检测 claude-code import 匹配同 host-port 忽略路径差异`() {
        val piConfig = tempDir.resolve("mcp.json").toFile()
        val claudeConfig = tempDir.resolve(".claude.json").toFile()
        piConfig.writeText("""{"imports": ["claude-code"]}""", Charsets.UTF_8)
        claudeConfig.writeText(
            """
            {
              "mcpServers": {
                "jetbrains": { "url": "http://127.0.0.1:64342/stream" }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        assertTrue(
            PiMcpConfigStore.isIdeaMcpAlreadyAvailable(
                sseUrl = "http://127.0.0.1:64342/sse",
                piConfigFile = piConfig,
                claudeConfigFile = claudeConfig
            )
        )
        assertFalse(
            PiMcpConfigStore.isIdeaMcpAlreadyAvailable(
                sseUrl = "http://127.0.0.1:64343/sse",
                piConfigFile = piConfig,
                claudeConfigFile = claudeConfig
            )
        )
    }

    @Test
    fun `去重检测缺少 claude-code import 时返回 false`() {
        val piConfig = tempDir.resolve("mcp.json").toFile()
        val claudeConfig = tempDir.resolve(".claude.json").toFile()
        piConfig.writeText("""{"imports": ["cursor"]}""", Charsets.UTF_8)
        claudeConfig.writeText(
            """{"mcpServers": {"jetbrains": {"url": "http://127.0.0.1:64342/sse"}}}""",
            Charsets.UTF_8
        )

        assertFalse(
            PiMcpConfigStore.isIdeaMcpAlreadyAvailable(
                sseUrl = "http://127.0.0.1:64342/sse",
                piConfigFile = piConfig,
                claudeConfigFile = claudeConfig
            )
        )
    }

    @Test
    fun `extractHostPort 规范化 host 和默认端口并拒绝非法 URL`() {
        assertEquals("localhost:64342", PiMcpConfigStore.extractHostPort("http://LOCALHOST:64342/sse"))
        assertEquals("example.com:80", PiMcpConfigStore.extractHostPort("http://example.com/sse"))
        assertEquals("example.com:443", PiMcpConfigStore.extractHostPort("https://example.com/sse"))
        assertNull(PiMcpConfigStore.extractHostPort("not-a-valid-url"))
        assertNull(PiMcpConfigStore.extractHostPort("/relative/path"))
        assertNull(PiMcpConfigStore.extractHostPort("stdio://local"))
    }
}
