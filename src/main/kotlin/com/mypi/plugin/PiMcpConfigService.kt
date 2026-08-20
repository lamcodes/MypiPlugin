package com.mypi.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 把 IDEA 的 MCP Server 连接信息注入到 Pi 的 mcp.json。
 *
 * 让 Pi 启动后能自动连接 IDE MCP，获得 read_file / search / build / debug 等工具。
 * 通过反射访问 McpServerConnectionAddressProvider，避免对 MCP 插件的编译期硬依赖：
 *  - IDEA 2025.2+ 且 MCP 插件启用时：正常注入
 *  - 旧版 IDEA 或 MCP 禁用时：反射失败，降级跳过，Pi 以纯终端模式运行
 *
 * 并发安全：
 *  - 写入采用「临时文件 + 原子替换」，避免 Pi 读到写了一半的文件
 *  - 读→改→写过程中保留所有非 intellij 条目，不覆盖外部对其它 server 的修改
 *  - 关闭 MCP 时主动清除 intellij 条目，不残留失效 URL
 */
@Service(Service.Level.PROJECT)
class PiMcpConfigService(private val project: Project) {

    private val logger = thisLogger()

    /**
     * 注入 IDE MCP Server 到 Pi 的 mcp.json。
     * 仅在 Pi 未运行时调用（Pi 运行中改配置不生效，需重启）。
     *
     * 去重逻辑：如果 Pi 已通过 imports(如 claude-code)获得了 IDEA MCP,则跳过注入,
     * 避免 mcpServers 中出现两个指向同一 IDEA MCP 的条目。
     *
     * 仅在读改写临界区加全局锁，避免多个 Project/窗口并发读写同一个用户级 mcp.json；
     * 反射和去重读取不持锁，避免无关操作阻塞其它 Project。
     *
     * @return [InjectResult] 注入结果，调用方可据此决定是否提示用户
     */
    fun injectIdeMcpServer(): InjectResult {
        val perfLog = PiSettings.getInstance(project).perfLoggingEnabled
        val t0 = if (perfLog) System.currentTimeMillis() else 0
        // 1. 检查用户是否启用了 MCP 集成
        if (!PiSettings.getInstance(project).mcpEnabled) {
            if (perfLog) logger.info("injectIdeMcpServer: 用户未启用 MCP, 跳过 (${System.currentTimeMillis() - t0}ms)")
            return InjectResult.Disabled
        }

        // 2. 获取 IDE MCP Server SSE URL
        val t1 = if (perfLog) System.currentTimeMillis() else 0
        val sseUrl = getMcpSseUrl() ?: run {
            if (perfLog) logger.info("injectIdeMcpServer: getMcpSseUrl 返回 null (${System.currentTimeMillis() - t1}ms), 跳过")
            return InjectResult.McpNotAvailable
        }
        if (perfLog) {
            logger.info("injectIdeMcpServer: getMcpSseUrl(反射) 耗时 ${System.currentTimeMillis() - t1}ms, url=$sseUrl")
        }

        // 3. 去重检查：用户开启去重时,检测 Pi 是否已通过 import 获得了 IDEA MCP
        val t2 = if (perfLog) System.currentTimeMillis() else 0
        if (PiSettings.getInstance(project).mcpDedupEnabled && isIdeaMcpAlreadyAvailable(sseUrl)) {
            if (perfLog) logger.info("injectIdeMcpServer: 去重检测通过,已配置 (${System.currentTimeMillis() - t2}ms)")
            return InjectResult.AlreadyConfigured
        }
        if (perfLog && PiSettings.getInstance(project).mcpDedupEnabled) {
            logger.info("injectIdeMcpServer: 去重检测耗时 ${System.currentTimeMillis() - t2}ms")
        }

        // 4. 写入 Pi 的 mcp.json
        val t3 = if (perfLog) System.currentTimeMillis() else 0
        return try {
            // 只把 mcp.json 的读-改-写放入锁内,缩短跨 Project 的等待时间。
            val changed = synchronized(lock) { writePiMcpConfig(sseUrl) }
            if (perfLog) {
                logger.info("injectIdeMcpServer: writePiMcpConfig 耗时 ${System.currentTimeMillis() - t3}ms, changed=$changed, 总耗时 ${System.currentTimeMillis() - t0}ms")
            }
            if (changed) InjectResult.Success else InjectResult.AlreadyConfigured
        } catch (e: Exception) {
            logger.warn("注入 IDE MCP 配置失败: ${e.message}", e)
            InjectResult.Failed("写入 mcp.json 失败: ${e.message}")
        }
    }

    /**
     * 检查 Pi 是否已通过 imports 机制获得了 IDEA MCP server。
     * 读取 Pi 的 mcp.json,解析 imports 列表,检查每个 import 源是否包含
     * 指向当前 IDEA MCP URL 的 server 条目。
     *
     * 当前主要检测 claude-code import(读取 ~/.claude.json)。
     */
    private fun isIdeaMcpAlreadyAvailable(sseUrl: String): Boolean {
        val perfLog = PiSettings.getInstance(project).perfLoggingEnabled
        val t0 = if (perfLog) System.currentTimeMillis() else 0
        val piConfig = File(System.getProperty("user.home"), PI_MCP_CONFIG_PATH)
        if (!piConfig.exists()) return false

        val config = readJsonObjectOrNull(piConfig) ?: return false

        // 检查 imports 列表；对格式异常保持宽容，只跳过无法识别的条目
        val imports = config["imports"] as? JsonArray ?: return false
        for (importEntry in imports) {
            val importKind = (importEntry as? JsonPrimitive)?.contentOrNull ?: continue
            if (importKind == "claude-code") {
                if (claudeCodeHasIdeaMcp(sseUrl)) return true
            }
        }
        if (perfLog) logger.info("isIdeaMcpAlreadyAvailable: 无 claude-code import 匹配 (${System.currentTimeMillis() - t0}ms)")
        return false
    }

    /**
     * 检查 Claude Code 的配置文件是否包含指向当前 IDEA MCP URL 的 server。
     * Claude Code 配置路径：~/.claude.json
     * 匹配逻辑：遍历 mcpServers,检查 url 是否包含当前 IDEA MCP 的 host:port。
     */
    private fun claudeCodeHasIdeaMcp(sseUrl: String): Boolean {
        val perfLog = PiSettings.getInstance(project).perfLoggingEnabled
        val t0 = if (perfLog) System.currentTimeMillis() else 0
        val claudeConfig = File(System.getProperty("user.home"), ".claude.json")
        if (!claudeConfig.exists()) return false

        // 从 SSE URL 提取 host:port 用于匹配(忽略路径差异 /sse vs /stream)
        val ideaHostPort = extractHostPort(sseUrl) ?: return false

        val config = readJsonObjectOrNull(claudeConfig) ?: return false

        // Claude Code 的 mcpServers 在顶层
        val servers = config["mcpServers"] as? JsonObject ?: return false
        for ((_, serverValue) in servers) {
            val server = serverValue as? JsonObject ?: continue
            // 检查 url 类型的 server
            val urlElement = server["url"] as? JsonPrimitive ?: continue
            val url = urlElement.contentOrNull ?: continue
            val hostPort = extractHostPort(url) ?: continue
            if (hostPort == ideaHostPort) {
                if (perfLog) logger.info("claudeCodeHasIdeaMcp: 匹配成功 (${System.currentTimeMillis() - t0}ms)")
                return true
            }
        }
        if (perfLog) logger.info("claudeCodeHasIdeaMcp: 无匹配 (${System.currentTimeMillis() - t0}ms)")
        return false
    }

    /** 从 URL 中提取 host:port 部分,用于匹配比较。 */
    private fun extractHostPort(url: String): String? {
        return runCatching {
            val uri = java.net.URI(url)
            "${uri.host}:${uri.port}"
        }.getOrNull()
    }

    /**
     * 从 Pi 的 mcp.json 中移除 intellij server 条目。
     * 用户在 Settings 关闭 MCP 时调用，避免残留失效 URL。
     *
     * @return true 表示有实际移除；false 表示文件不存在或无 intellij 条目
     */
    fun removeIdeMcpServer(): Boolean = synchronized(lock) {
        removeIdeMcpServerLocked()
    }

    private fun removeIdeMcpServerLocked(): Boolean {
        val piConfig = File(System.getProperty("user.home"), PI_MCP_CONFIG_PATH)
        if (!piConfig.exists()) return false

        // 文件存在但解析失败时不贸然覆盖，避免把用户已有配置清空
        val existingConfig = readJsonObjectOrNull(piConfig) ?: run {
            logger.warn("removeIdeMcpServer: ${piConfig.path} 解析失败, 已跳过清理")
            return false
        }

        val existingServers = existingConfig["mcpServers"] as? JsonObject ?: return false
        if (!existingServers.containsKey("intellij")) return false

        // 移除 intellij 条目，保留其它所有 server
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

        atomicWrite(piConfig, newConfig)
        return true
    }

    /**
     * 通过反射获取 IDE MCP Server 的 SSE URL。
     * MCP 插件未启用或不可用时返回 null（不抛异常）。
     */
    private fun getMcpSseUrl(): String? {
        return runCatching {
            val clazz = Class.forName(MCP_CONNECTION_PROVIDER_FQN)
            val app = ApplicationManager.getApplication()
            val service = app.getService(clazz)
            val method = clazz.getMethod("getServerSseUrl")
            method.invoke(service) as? String
        }.getOrElse { e ->
            logger.debug("获取 IDE MCP Server URL 失败（可能未安装 MCP 插件）: ${e.message}")
            null
        }
    }

    /**
     * 写入 Pi 的 mcp.json，添加 intellij server 条目。
     * 保留原有 imports 和其他 mcpServers 配置，只更新 intellij 条目。
     *
     * @return true 表示配置有变化；false 表示配置已是最新的
     */
    private fun writePiMcpConfig(sseUrl: String): Boolean {
        val piConfig = File(System.getProperty("user.home"), PI_MCP_CONFIG_PATH)

        // 读取现有配置；文件损坏时抛出异常，由调用方转为 Failed，避免静默清空
        val existingConfig = readJsonObjectForWrite(piConfig)

        // 检查是否已配置且 URL 未变（避免无意义写入）
        val existingServers = existingConfig["mcpServers"] as? JsonObject
        val existingIntellij = existingServers?.get("intellij") as? JsonObject
        val existingUrl = existingIntellij?.get("url") as? JsonPrimitive
        if (existingUrl?.contentOrNull == sseUrl) {
            return false // 已配置且未变化
        }

        // 构建新的 mcpServers（保留原有，添加/更新 intellij）
        val newServers = buildJsonObject {
            (existingServers ?: buildJsonObject { }).entries.forEach { (key, value) ->
                put(key, value)
            }
            put("intellij", buildJsonObject {
                put("url", sseUrl)
            })
        }

        // 合并配置
        val newConfig = buildJsonObject {
            existingConfig.entries.forEach { (key, value) ->
                if (key != "mcpServers") put(key, value)
            }
            put("mcpServers", newServers)
        }

        atomicWrite(piConfig, newConfig)
        return true
    }

    /**
     * 原子写入文件：先写临时文件，再原子替换目标文件。
     * 避免 Pi 或其它进程读到写了一半的中间状态。
     */
    private fun atomicWrite(target: File, config: JsonObject) {
        val parentDir = target.parentFile ?: target.absoluteFile.parentFile ?: File(".")
        parentDir.mkdirs()
        val tempFile = Files.createTempFile(parentDir.toPath(), "${target.name}.", ".tmp").toFile()
        try {
            tempFile.writeText(
                prettyJson.encodeToString(JsonObject.serializer(), config),
                Charsets.UTF_8
            )
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            // 原子移动在某些文件系统(如某些网络挂载)不支持,降级为普通写入
            logger.debug("原子写入失败,降级为普通写入: ${e.message}")
            target.writeText(
                prettyJson.encodeToString(JsonObject.serializer(), config),
                Charsets.UTF_8
            )
        } finally {
            runCatching { Files.deleteIfExists(tempFile.toPath()) }
        }
    }

    /**
     * 读取 JSON 文件为 [JsonObject]；文件不存在返回 null，解析失败返回 null。
     * 用于“可跳过/只读”场景（去重检测、移除配置），避免因格式异常中断主流程。
     */
    private fun readJsonObjectOrNull(file: File): JsonObject? {
        if (!file.exists()) return null
        return runCatching {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }

    /**
     * 读取 JSON 文件为 [JsonObject]；文件不存在返回空对象，解析失败抛出异常。
     * 用于“写入前读取”，宁可让调用方看到 Failed，也不静默清空用户已有配置。
     */
    private fun readJsonObjectForWrite(file: File): JsonObject {
        if (!file.exists()) return buildJsonObject { }
        return try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: throw IllegalStateException("mcp.json 根节点不是 JSON Object")
        } catch (e: Exception) {
            throw IllegalStateException("解析 ${file.path} 失败: ${e.message}", e)
        }
    }

    /** 注入结果密封类型 */
    sealed interface InjectResult {
        /** 注入成功，配置已变化 */
        data object Success : InjectResult
        /** 用户未启用 MCP 集成 */
        data object Disabled : InjectResult
        /** IDE MCP Server 不可用（旧版 IDEA 或 MCP 禁用） */
        data object McpNotAvailable : InjectResult
        /** 配置已是最新的，无需更新 */
        data object AlreadyConfigured : InjectResult
        /** 注入过程异常 */
        data class Failed(val message: String) : InjectResult
    }

    companion object {
        fun getInstance(project: Project): PiMcpConfigService = project.service()

        /**
         * 用户级 mcp.json 的全局写锁。
         * 虽然本服务是 Project 级，但多个 Project 会同时操作同一个文件，因此锁必须放在
         * companion/Application 级，而不是实例字段上。
         */
        private val lock = Any()

        /** 格式化 JSON 实例（复用，避免每次创建） */
        private val prettyJson = Json { prettyPrint = true }

        /** MCP Server 连接地址提供者全限定类名（反射访问，避免编译期依赖） */
        private const val MCP_CONNECTION_PROVIDER_FQN =
            "com.intellij.mcpserver.impl.util.network.McpServerConnectionAddressProvider"

        /** Pi MCP 配置文件路径（用户主目录下） */
        private const val PI_MCP_CONFIG_PATH = ".pi/agent/mcp.json"
    }
}
