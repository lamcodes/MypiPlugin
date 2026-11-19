package com.mypi.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Pi 的 mcp.json 与去重检测纯文件存储层（零 IntelliJ 平台依赖，便于 Tier1 单测覆盖）。
 *
 * 职责：
 *  - 原子写入/更新 `mcpServers.intellij.url`，保留其它 server 与顶层字段（如 `imports`）
 *  - 写入前遇到损坏 JSON 或非对象根节点时抛出异常，拒绝静默清空用户配置
 *  - 清理 `intellij` 条目，文件不存在或损坏时安全跳过
 *  - 检测 `imports`（当前支持 `claude-code` → `~/.claude.json`）是否已包含指向同一 `host:port` 的 IDEA MCP
 *
 * 线程安全：本对象无状态，跨 Project 并发访问同一用户级文件时由调用方（[PiMcpConfigService]）负责加锁。
 */
internal object PiMcpConfigStore {

    /** 格式化 JSON 实例（复用，避免每次创建） */
    private val prettyJson = Json { prettyPrint = true }

    /**
     * 检查 Pi 是否已通过 `imports` 机制获得了指向 [sseUrl] 的 IDEA MCP Server。
     */
    fun isIdeaMcpAlreadyAvailable(
        sseUrl: String,
        piConfigFile: File,
        claudeConfigFile: File
    ): Boolean {
        if (!piConfigFile.exists()) return false
        val config = readJsonObjectOrNull(piConfigFile) ?: return false

        val imports = config["imports"] as? JsonArray ?: return false
        for (importEntry in imports) {
            val importKind = (importEntry as? JsonPrimitive)?.contentOrNull ?: continue
            if (importKind == "claude-code" && claudeCodeHasIdeaMcp(sseUrl, claudeConfigFile)) {
                return true
            }
        }
        return false
    }

    /**
     * 检查 Claude Code 配置文件中是否已包含指向同一 `host:port` 的 MCP Server。
     * 忽略路径差异（如 `/sse` vs `/stream`）。
     */
    fun claudeCodeHasIdeaMcp(sseUrl: String, claudeConfigFile: File): Boolean {
        if (!claudeConfigFile.exists()) return false
        val ideaHostPort = extractHostPort(sseUrl) ?: return false

        val config = readJsonObjectOrNull(claudeConfigFile) ?: return false
        val servers = config["mcpServers"] as? JsonObject ?: return false
        for ((_, serverValue) in servers) {
            val server = serverValue as? JsonObject ?: continue
            val urlElement = server["url"] as? JsonPrimitive ?: continue
            val url = urlElement.contentOrNull ?: continue
            if (extractHostPort(url) == ideaHostPort) {
                return true
            }
        }
        return false
    }

    /**
     * 从 URL 中提取规范化的 `host:port`（host 小写，http/https 补齐默认端口）。
     * 无合法 host 或无法确定端口时返回 null，避免畸形 URL 被误判相等。
     */
    fun extractHostPort(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val host = uri.host?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
            val port = when {
                uri.port != -1 -> uri.port
                uri.scheme.equals("http", ignoreCase = true) -> 80
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> return null
            }
            "$host:$port"
        }.getOrNull()
    }

    /**
     * 写入或更新 `mcpServers.intellij.url`。
     * 保留原有 `imports` 和其它 `mcpServers` 条目；文件损坏时抛出 [IllegalStateException] 拒绝覆盖。
     *
     * @return true 表示配置有实际变更并已落盘；false 表示已是最新，无需写入
     */
    fun upsertIdeMcpServer(
        piConfigFile: File,
        sseUrl: String,
        onAtomicFallback: (Exception) -> Unit = {}
    ): Boolean {
        val existingConfig = readJsonObjectForWrite(piConfigFile)

        val existingServers = existingConfig["mcpServers"] as? JsonObject
        val existingIntellij = existingServers?.get("intellij") as? JsonObject
        val existingUrl = existingIntellij?.get("url") as? JsonPrimitive
        if (existingUrl?.contentOrNull == sseUrl) {
            return false
        }

        val newServers = buildJsonObject {
            existingServers?.entries?.forEach { (key, value) ->
                put(key, value)
            }
            put("intellij", buildJsonObject {
                put("url", sseUrl)
            })
        }

        val newConfig = buildJsonObject {
            existingConfig.entries.forEach { (key, value) ->
                if (key != "mcpServers") put(key, value)
            }
            put("mcpServers", newServers)
        }

        atomicWrite(piConfigFile, newConfig, onAtomicFallback)
        return true
    }

    /**
     * 从 `mcp.json` 移除 `mcpServers.intellij` 条目，保留其它所有条目与顶层字段。
     * 文件不存在或解析失败时安全跳过并返回 false。
     */
    fun removeIdeMcpServer(
        piConfigFile: File,
        onParseFailed: () -> Unit = {},
        onAtomicFallback: (Exception) -> Unit = {}
    ): Boolean {
        if (!piConfigFile.exists()) return false

        val existingConfig = readJsonObjectOrNull(piConfigFile) ?: run {
            onParseFailed()
            return false
        }

        val existingServers = existingConfig["mcpServers"] as? JsonObject ?: return false
        if (!existingServers.containsKey("intellij")) return false

        val newServers = buildJsonObject {
            existingServers.entries.forEach { (key, value) ->
                if (key != "intellij") put(key, value)
            }
        }

        val newConfig = buildJsonObject {
            existingConfig.entries.forEach { (key, value) ->
                if (key != "mcpServers") put(key, value)
            }
            put("mcpServers", newServers)
        }

        atomicWrite(piConfigFile, newConfig, onAtomicFallback)
        return true
    }

    /**
     * 原子写入文件：先写同目录临时文件，再通过 `ATOMIC_MOVE` 替换目标文件；
     * 若文件系统不支持原子替换，降级为普通写入并清理临时文件。
     */
    private fun atomicWrite(
        target: File,
        config: JsonObject,
        onAtomicFallback: (Exception) -> Unit
    ) {
        val parentDir = target.parentFile ?: target.absoluteFile.parentFile ?: File(".")
        parentDir.mkdirs()
        val encoded = prettyJson.encodeToString(JsonObject.serializer(), config)
        val tempFile = Files.createTempFile(parentDir.toPath(), "${target.name}.", ".tmp").toFile()
        try {
            tempFile.writeText(encoded, Charsets.UTF_8)
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            onAtomicFallback(e)
            target.writeText(encoded, Charsets.UTF_8)
        } finally {
            runCatching { Files.deleteIfExists(tempFile.toPath()) }
        }
    }

    private fun readJsonObjectOrNull(file: File): JsonObject? {
        if (!file.exists()) return null
        return runCatching {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }

    private fun readJsonObjectForWrite(file: File): JsonObject {
        if (!file.exists()) return buildJsonObject { }
        return try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: throw IllegalStateException("mcp.json 根节点不是 JSON Object")
        } catch (e: Exception) {
            throw IllegalStateException("解析 ${file.path} 失败: ${e.message}", e)
        }
    }
}
