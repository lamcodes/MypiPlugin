package com.mypi.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * SelectionContextBuilder 纯逻辑测试(Tier1,JUnit 5 jupiter,零 IntelliJ 依赖)。
 *
 * 覆盖两种 SelectionFormat:COLON_LINE(默认)和 GITHUB_ANCHOR。
 * 每个格式覆盖:多行选区、起止行相同(单行)、无选区(传 caretLine 两次)、
 * 多点文件名、长区间、路径透传。
 */
class SelectionContextBuilderTest {

    // ── COLON_LINE 格式 ──

    @Test
    fun `COLON_LINE 多行选区定位串 path 冒号 起止行`() {
        assertEquals(
            "@src/main/java/com/x/UserService.java:10-20",
            SelectionContextBuilder.build("src/main/java/com/x/UserService.java", 10, 20, SelectionFormat.COLON_LINE)
        )
    }

    @Test
    fun `COLON_LINE 起止行相同时合并为单行`() {
        assertEquals("@a.kt:5", SelectionContextBuilder.build("a.kt", 5, 5, SelectionFormat.COLON_LINE))
    }

    @Test
    fun `COLON_LINE 无选区传光标行两次产出单行定位`() {
        assertEquals("@Foo.java:42", SelectionContextBuilder.build("Foo.java", 42, 42, SelectionFormat.COLON_LINE))
    }

    @Test
    fun `COLON_LINE 多点文件名原样保留`() {
        assertEquals(
            "@my.service.spec.ts:1-9",
            SelectionContextBuilder.build("my.service.spec.ts", 1, 9, SelectionFormat.COLON_LINE)
        )
    }

    @Test
    fun `COLON_LINE build 输出仅为定位串不含任何代码`() {
        val prompt = SelectionContextBuilder.build("App.kt", 1, 100, SelectionFormat.COLON_LINE)
        assertEquals("@App.kt:1-100", prompt)
    }

    @Test
    fun `COLON_LINE 路径透传不改写分隔符`() {
        assertEquals(
            "@src/test/kotlin/App.kt:7",
            SelectionContextBuilder.build("src/test/kotlin/App.kt", 7, 7, SelectionFormat.COLON_LINE)
        )
    }

    @Test
    fun `COLON_LINE Windows 绝对路径保留盘符并使用末尾行号`() {
        assertEquals(
            "@C:/work/shared/Config.kt:10",
            SelectionContextBuilder.build("C:/work/shared/Config.kt", 10, 10, SelectionFormat.COLON_LINE)
        )
    }

    // ── 默认格式(无 format 参数)= COLON_LINE ──

    @Test
    fun `默认格式为 COLON_LINE 多行`() {
        assertEquals("@Foo.kt:3-7", SelectionContextBuilder.build("Foo.kt", 3, 7))
    }

    @Test
    fun `默认格式为 COLON_LINE 单行`() {
        assertEquals("@Foo.kt:3", SelectionContextBuilder.build("Foo.kt", 3, 3))
    }

    // ── GITHUB_ANCHOR 格式(保持向后兼容) ──

    @Test
    fun `GITHUB_ANCHOR 多行选区定位串为 at 路径井 L 起止行`() {
        assertEquals(
            "@src/main/java/com/x/UserService.java#L10-20",
            SelectionContextBuilder.build("src/main/java/com/x/UserService.java", 10, 20, SelectionFormat.GITHUB_ANCHOR)
        )
    }

    @Test
    fun `GITHUB_ANCHOR 起止行相同时合并为单行`() {
        assertEquals("@a.kt#L5", SelectionContextBuilder.build("a.kt", 5, 5, SelectionFormat.GITHUB_ANCHOR))
    }

    @Test
    fun `GITHUB_ANCHOR 无选区传光标行两次产出单行定位`() {
        assertEquals("@Foo.java#L42", SelectionContextBuilder.build("Foo.java", 42, 42, SelectionFormat.GITHUB_ANCHOR))
    }

    @Test
    fun `GITHUB_ANCHOR 多点文件名原样保留`() {
        assertEquals(
            "@my.service.spec.ts#L1-9",
            SelectionContextBuilder.build("my.service.spec.ts", 1, 9, SelectionFormat.GITHUB_ANCHOR)
        )
    }

    @Test
    fun `GITHUB_ANCHOR build 输出仅为定位串不含任何代码`() {
        val prompt = SelectionContextBuilder.build("App.kt", 1, 100, SelectionFormat.GITHUB_ANCHOR)
        assertEquals("@App.kt#L1-100", prompt)
    }

    @Test
    fun `GITHUB_ANCHOR 路径透传不改写分隔符`() {
        assertEquals(
            "@src/test/kotlin/App.kt#L7",
            SelectionContextBuilder.build("src/test/kotlin/App.kt", 7, 7, SelectionFormat.GITHUB_ANCHOR)
        )
    }

    // ── SelectionFormat 持久化解析与回退 ──

    @Test
    fun `SelectionFormat fromSetting 空串和非法值回退 COLON_LINE`() {
        assertEquals(SelectionFormat.COLON_LINE, SelectionFormat.fromSetting(null))
        assertEquals(SelectionFormat.COLON_LINE, SelectionFormat.fromSetting(""))
        assertEquals(SelectionFormat.COLON_LINE, SelectionFormat.fromSetting("COLON_LINE"))
        assertEquals(SelectionFormat.COLON_LINE, SelectionFormat.fromSetting("UNKNOWN_FORMAT"))
        assertEquals(SelectionFormat.GITHUB_ANCHOR, SelectionFormat.fromSetting("GITHUB_ANCHOR"))
    }

    @Test
    fun `SelectionFormat toSettingValue 保持历史 XML 兼容`() {
        assertEquals("", SelectionFormat.COLON_LINE.toSettingValue())
        assertEquals("GITHUB_ANCHOR", SelectionFormat.GITHUB_ANCHOR.toSettingValue())
    }

    // ── resolveLineRange 整行末尾换行边界 ──

    @Test
    fun `resolveLineRange 普通行内选区保持原起止行`() {
        // 第 1 行(0-based: 0) 偏移 0..3，未跨到下一行行首
        assertEquals(
            1 to 1,
            SelectionContextBuilder.resolveLineRange(
                selectionStart = 0,
                selectionEnd = 3,
                startLine0 = 0,
                endLine0 = 0,
                endLineStartOffset = 0
            )
        )
    }

    @Test
    fun `resolveLineRange 整行选中含末尾换行时不把下一行算入`() {
        // 选中第 10 行整行含 '\n'，selectionEnd 落在第 11 行(0-based: 10)第 0 列
        assertEquals(
            10 to 10,
            SelectionContextBuilder.resolveLineRange(
                selectionStart = 100,
                selectionEnd = 120,
                startLine0 = 9,
                endLine0 = 10,
                endLineStartOffset = 120
            )
        )
    }

    @Test
    fun `resolveLineRange 多行整行选中含末尾换行时回退一行`() {
        // 选中第 10-12 行整行含末尾 '\n'，selectionEnd 落在第 13 行(0-based: 12)第 0 列
        assertEquals(
            10 to 12,
            SelectionContextBuilder.resolveLineRange(
                selectionStart = 100,
                selectionEnd = 180,
                startLine0 = 9,
                endLine0 = 12,
                endLineStartOffset = 180
            )
        )
    }

    @Test
    fun `resolveLineRange 跨行且末行有实际选中字符时不回退`() {
        // 选中第 10 行到第 11 行第 2 列（endLineStartOffset=120 != selectionEnd=122）
        assertEquals(
            10 to 11,
            SelectionContextBuilder.resolveLineRange(
                selectionStart = 100,
                selectionEnd = 122,
                startLine0 = 9,
                endLine0 = 10,
                endLineStartOffset = 120
            )
        )
    }

    @Test
    fun `resolveLineRange 空选区在行首时不误回退`() {
        assertEquals(
            5 to 5,
            SelectionContextBuilder.resolveLineRange(
                selectionStart = 40,
                selectionEnd = 40,
                startLine0 = 4,
                endLine0 = 4,
                endLineStartOffset = 40
            )
        )
    }
}
