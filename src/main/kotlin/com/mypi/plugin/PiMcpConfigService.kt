package com.mypi.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 把 IDEA 的 MCP Server 连接信息注入到 Pi 的 mcp.json。
 *
 * 让 Pi 启动后能自动连接 IDE MCP，获得 read_file / search / build / debug 等工具。
 * 通过反射访问 McpServerConnectionAddressProvider，避免对 MCP 插件的编译期硬依赖：
 *  - IDEA 2025.2+ 且 MCP 插件启用时：正常注入
 *  - 旧版 IDEA 或 MCP 禁用时：反射失败，降级跳过，Pi 以纯终端模式运行
 *
 * 架构分工：
 *  - 本类负责平台交互（读取 [PiSettings]、反射获取 MCP URL、性能日志、跨 Project 全局写锁）
 *  - 纯 JSON/文件读写与去重判断下沉到 [PiMcpConfigStore]，便于 Tier1 单元测试覆盖
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
        val settings = PiSettings.getInstance(project)
        val perfLog = settings.perfLoggingEnabled
        val t0 = if (perfLog) System.currentTimeMillis() else 0

        // 1. 检查用户是否启用了 MCP 集成
        if (!settings.mcpEnabled) {
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

        val piConfig = piConfigFile()
        val claudeConfig = claudeConfigFile()

        // 3. 去重检查：用户开启去重时,检测 Pi 是否已通过 import 获得了 IDEA MCP
        val t2 = if (perfLog) System.currentTimeMillis() else 0
        if (settings.mcpDedupEnabled) {
            val alreadyAvailable = PiMcpConfigStore.isIdeaMcpAlreadyAvailable(sseUrl, piConfig, claudeConfig)
            if (alreadyAvailable) {
                if (perfLog) logger.info("injectIdeMcpServer: 去重检测通过,已配置 (${System.currentTimeMillis() - t2}ms)")
                return InjectResult.AlreadyConfigured
            }
            if (perfLog) {
                logger.info("injectIdeMcpServer: 去重检测耗时 ${System.currentTimeMillis() - t2}ms")
            }
        }

        // 4. 写入 Pi 的 mcp.json
        val t3 = if (perfLog) System.currentTimeMillis() else 0
        return try {
            // 只把 mcp.json 的读-改-写放入锁内,缩短跨 Project 的等待时间。
            val changed = synchronized(lock) {
                PiMcpConfigStore.upsertIdeMcpServer(piConfig, sseUrl) { e ->
                    logger.debug("原子写入失败,降级为普通写入: ${e.message}")
                }
            }
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
     * 从 Pi 的 mcp.json 中移除 intellij server 条目。
     * 用户在 Settings 关闭 MCP 时调用，避免残留失效 URL。
     *
     * @return true 表示有实际移除；false 表示文件不存在或无 intellij 条目
     */
    fun removeIdeMcpServer(): Boolean = synchronized(lock) {
        val piConfig = piConfigFile()
        PiMcpConfigStore.removeIdeMcpServer(
            piConfigFile = piConfig,
            onParseFailed = { logger.warn("removeIdeMcpServer: ${piConfig.path} 解析失败, 已跳过清理") },
            onAtomicFallback = { e -> logger.debug("原子写入失败,降级为普通写入: ${e.message}") }
        )
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

    private fun piConfigFile(): File = File(System.getProperty("user.home"), PI_MCP_CONFIG_PATH)

    private fun claudeConfigFile(): File = File(System.getProperty("user.home"), CLAUDE_CONFIG_PATH)

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

        /** MCP Server 连接地址提供者全限定类名（反射访问，避免编译期依赖） */
        private const val MCP_CONNECTION_PROVIDER_FQN =
            "com.intellij.mcpserver.impl.util.network.McpServerConnectionAddressProvider"

        /** Pi MCP 配置文件路径（用户主目录下） */
        private const val PI_MCP_CONFIG_PATH = ".pi/agent/mcp.json"

        /** Claude Code 配置文件路径（用户主目录下） */
        private const val CLAUDE_CONFIG_PATH = ".claude.json"
    }
}
