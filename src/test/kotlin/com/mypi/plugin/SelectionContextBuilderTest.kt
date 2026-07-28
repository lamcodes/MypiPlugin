package com.mypi.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SelectionContextBuilder 纯逻辑测试(Tier1,JUnit 5 jupiter,零 IntelliJ 依赖)。
 *
 * 决策更新(哥反馈):build 产出 "@<path>#L<行号>" 行锚点格式(GitHub 风格),
 * path 原样透传(相对项目根或降级裸文件名),不发代码内容。
 * 覆盖:多行选区、起止行相同(单行)、无选区(传 caretLine 两次)、多点文件名、长区间、路径透传。
 */
class SelectionContextBuilderTest {

    @Test
    fun `多行选区定位串为 at 路径井 L 起止行`() {
        assertEquals(
            "@src/main/java/com/x/UserService.java#L10-20",
            SelectionContextBuilder.build("src/main/java/com/x/UserService.java", 10, 20)
        )
    }

    @Test
    fun `起止行相同时合并为单行`() {
        assertEquals("@a.kt#L5", SelectionContextBuilder.build("a.kt", 5, 5))
    }

    @Test
    fun `无选区传光标行两次产出单行定位`() {
        // 无选区复用 build:caretLine 作为 startLine 与 endLine 同时传入
        assertEquals("@Foo.java#L42", SelectionContextBuilder.build("Foo.java", 42, 42))
    }

    @Test
    fun `多点文件名原样保留`() {
        assertEquals(
            "@my.service.spec.ts#L1-9",
            SelectionContextBuilder.build("my.service.spec.ts", 1, 9)
        )
    }

    @Test
    fun `build 输出仅为定位串不含任何代码`() {
        val prompt = SelectionContextBuilder.build("App.kt", 1, 100)
        assertEquals("@App.kt#L1-100", prompt)
    }

    @Test
    fun `路径透传不改写分隔符`() {
        // Builder 不关心 path 是相对路径还是裸文件名,原样拼入
        assertEquals(
            "@src/test/kotlin/App.kt#L7",
            SelectionContextBuilder.build("src/test/kotlin/App.kt", 7, 7)
        )
    }
}
