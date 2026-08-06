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
}
