package com.mypi.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SelectionContextBuilder 纯逻辑测试(Tier1,JUnit 5 jupiter,零 IntelliJ 依赖)。
 *
 * 决策更新(哥反馈):build 只输出"文件名:行号"定位,不发代码内容。
 * 覆盖:选区定位格式、起止行相同、无选区定位、多点文件名原样保留。
 */
class SelectionContextBuilderTest {

    @Test
    fun `选区定位串包含文件名与起止行`() {
        assertEquals(
            "UserService.java:10-20",
            SelectionContextBuilder.build("UserService.java", 10, 20)
        )
    }

    @Test
    fun `起止行相同时为单行范围`() {
        assertEquals("a.kt:5-5", SelectionContextBuilder.build("a.kt", 5, 5))
    }

    @Test
    fun `无选区定位串包含文件名与行号`() {
        assertEquals(
            "Foo.java:42",
            SelectionContextBuilder.buildFileLocator("Foo.java", 42)
        )
    }

    @Test
    fun `多点文件名原样保留`() {
        assertEquals(
            "my.service.spec.ts:1-9",
            SelectionContextBuilder.build("my.service.spec.ts", 1, 9)
        )
    }

    @Test
    fun `build 输出仅为定位串不含任何代码`() {
        // 决策更新:只发定位,build 签名已不含 selectedText,输出就是纯定位
        val prompt = SelectionContextBuilder.build("App.kt", 1, 100)
        assertEquals("App.kt:1-100", prompt)
    }
}
