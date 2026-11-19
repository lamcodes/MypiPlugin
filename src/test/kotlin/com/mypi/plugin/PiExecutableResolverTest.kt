package com.mypi.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * PiExecutableResolver 纯逻辑测试（Tier1, JUnit 5 jupiter, 零 IntelliJ 依赖）。
 */
class PiExecutableResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createExecutable(dir: File, name: String): File {
        val file = File(dir, name)
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\n")
        file.setExecutable(true)
        return file
    }

    @Test
    fun `显式配置有效可执行文件时优先返回配置路径`() {
        val customPi = createExecutable(tempDir.toFile(), "custom/pi.exe")
        val pathDir = tempDir.resolve("bin").toFile()
        createExecutable(pathDir, "pi.exe")

        val resolved = PiExecutableResolver.resolve(
            configuredPath = "  ${customPi.absolutePath}  ",
            pathEnv = pathDir.absolutePath,
            isWindows = true
        )

        assertEquals(customPi.absolutePath, resolved)
    }

    @Test
    fun `配置路径指向目录时拒绝作为可执行文件并回退 PATH`() {
        val fakeDir = tempDir.resolve("pi-dir").toFile().apply { mkdirs() }
        val pathDir = tempDir.resolve("bin").toFile()
        val pathPi = createExecutable(pathDir, "pi.cmd")

        val resolved = PiExecutableResolver.resolve(
            configuredPath = fakeDir.absolutePath,
            pathEnv = pathDir.absolutePath,
            isWindows = true
        )

        assertEquals(pathPi.absolutePath, resolved)
    }

    @Test
    fun `Windows PATH 扫描遵循 exe 优先于 cmd 优先于 bat`() {
        val dir = tempDir.resolve("win-bin").toFile()
        createExecutable(dir, "pi.bat")
        val cmd = createExecutable(dir, "pi.cmd")

        assertEquals(
            cmd.absolutePath,
            PiExecutableResolver.resolve(configuredPath = "", pathEnv = dir.absolutePath, isWindows = true)
        )

        val exe = createExecutable(dir, "pi.exe")
        assertEquals(
            exe.absolutePath,
            PiExecutableResolver.resolve(configuredPath = "", pathEnv = dir.absolutePath, isWindows = true)
        )
    }

    @Test
    fun `非 Windows 平台仅匹配名为 pi 的普通文件并跳过同名目录`() {
        val dir1 = tempDir.resolve("bin1").toFile()
        File(dir1, "pi").mkdirs() // 同名目录必须跳过
        val dir2 = tempDir.resolve("bin2").toFile()
        val unixPi = createExecutable(dir2, "pi")

        val resolved = PiExecutableResolver.resolve(
            configuredPath = "",
            pathEnv = "${dir1.absolutePath}${File.pathSeparator}${dir2.absolutePath}",
            isWindows = false
        )

        assertEquals(unixPi.absolutePath, resolved)
    }

    @Test
    fun `PATH 为空且未配置有效路径时返回 null`() {
        assertNull(PiExecutableResolver.resolve(configuredPath = "", pathEnv = null, isWindows = true))
        assertNull(PiExecutableResolver.resolve(configuredPath = "", pathEnv = "  ", isWindows = false))
    }
}
