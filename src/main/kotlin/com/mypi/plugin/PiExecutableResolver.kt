package com.mypi.plugin

import java.io.File

/**
 * Pi 可执行文件解析器（零 IntelliJ 平台依赖，便于 Tier1 单测覆盖）。
 *
 * 规则：
 *  1. 优先使用用户在 Settings 中配置的路径（必须是可执行普通文件，排除目录）。
 *  2. 配置为空或不可执行时，扫描 `PATH` 环境变量：
 *     - Windows 优先匹配 `pi.exe` > `pi.cmd` > `pi.bat` > `pi`
 *     - 其它平台匹配 `pi`
 */
internal object PiExecutableResolver {

    fun resolve(
        configuredPath: String,
        pathEnv: String? = System.getenv("PATH"),
        isWindows: Boolean = System.getProperty("os.name", "").lowercase().startsWith("windows"),
        pathSeparator: String = File.pathSeparator
    ): String? {
        val trimmed = configuredPath.trim()
        if (trimmed.isNotEmpty()) {
            val configuredFile = File(trimmed)
            if (isExecutableFile(configuredFile)) {
                return trimmed
            }
        }
        if (pathEnv.isNullOrBlank()) return null

        val candidates = if (isWindows) listOf("pi.exe", "pi.cmd", "pi.bat", "pi") else listOf("pi")
        for (dir in pathEnv.split(pathSeparator)) {
            if (dir.isBlank()) continue
            for (name in candidates) {
                val candidate = File(dir, name)
                if (isExecutableFile(candidate)) {
                    return candidate.absolutePath
                }
            }
        }
        return null
    }

    /** 仅接受普通可执行文件，排除目录（Java 中目录的 `canExecute()` 也会返回 true）。 */
    private fun isExecutableFile(file: File): Boolean = file.isFile && file.canExecute()
}
