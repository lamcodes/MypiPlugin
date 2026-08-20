# 选区格式多选项 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Settings → Tools → Pi 增加选区格式下拉框,支持冒号格式(@path:行号)和 GitHub 格式(@path#L行号)两种选项,默认冒号格式。

**Architecture:** `SelectionFormat` 枚举定义两种格式策略;`SelectionContextBuilder.build()` 新增 format 参数按枚举分支拼串;`PiSettings` 持久化用户选择;`PiConfigurable` 加下拉框 UI;`SendSelectionAction` 读设置传枚举。纯逻辑无外部依赖。

**Tech Stack:** Kotlin, IntelliJ Platform Plugin (2026.2), JUnit 5

## Global Constraints

- 默认格式: `COLON_LINE`(冒号风格 `@path:行号`)
- 空配置串等价 `COLON_LINE`
- `GITHUB_ANCHOR` 保持与旧版 `build(path, startLine, endLine)` 完全一致的输出
- 下拉框位于路径输入框之下、MCP 开关之上
- 单行合并: startLine == endLine 时只写一个行号

---

### Task 1: 定义 SelectionFormat 枚举,修改 build() 签名

**Files:**
- Modify: `src/main/kotlin/com/mypi/plugin/SelectionContextBuilder.kt` (全文件重写)

**Interfaces:**
- Produces: `enum class SelectionFormat { COLON_LINE, GITHUB_ANCHOR }`
- Produces: `SelectionContextBuilder.build(path: String, startLine: Int, endLine: Int, format: SelectionFormat = SelectionFormat.COLON_LINE): String`

- [ ] **Step 1: 重写 SelectionContextBuilder.kt**

```kotlin
package com.mypi.plugin

/**
 * 选区格式枚举:两种行锚点风格。
 *
 * COLON_LINE:    @path:78 (单行) / @path:78-85 (多行)
 * GITHUB_ANCHOR: @path#L78 (单行) / @path#L78-85 (多行)
 */
enum class SelectionFormat {
    COLON_LINE,
    GITHUB_ANCHOR
}

/**
 * 选区 → pi 终端注入的"定位串"构造器(纯逻辑,零 IntelliJ 依赖)。
 *
 * 产物根据 [format] 参数采用不同行锚点风格:
 *  - COLON_LINE: `@<path>:<行号>`,如 `@src/App.kt:10-20`
 *  - GITHUB_ANCHOR: `@<path>#L<行号>`,如 `@src/App.kt#L10-20`
 *  - 行号:单行 `15`;多行 `10-20`(仅首行带 L)
 *
 * path 由调用方(SendSelectionAction)解析为"相对项目根路径",项目外本地文件使用绝对路径;此处原样透传。
 *
 * 只发定位、不发代码内容:pi 在项目目录跑(终端方案),作为 coding agent 能自行读取项目文件,
 * 只发定位可保持 pi 输入行简洁;加 @/L 或冒号前缀让 pi 更可靠识别为文件引用。
 */
object SelectionContextBuilder {

    /**
     * 产出 pi 注入串。
     *
     * @param path      已解析的文件路径(相对项目根,或项目外本地文件的绝对路径),原样透传
     * @param startLine 起始行(1-based)
     * @param endLine   结束行(1-based);与 startLine 相同时合并为单行号
     * @param format    行锚点格式,默认 [SelectionFormat.COLON_LINE]
     */
    fun build(path: String, startLine: Int, endLine: Int, format: SelectionFormat = SelectionFormat.COLON_LINE): String {
        val lineSpec = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
        return when (format) {
            SelectionFormat.COLON_LINE -> "@$path:$lineSpec"
            SelectionFormat.GITHUB_ANCHOR -> "@$path#L$lineSpec"
        }
    }
}
```

- [ ] **Step 2: 运行现有测试,确认 GITHUB_ANCHOR 行为不变**

Run: 在 IDEA 中运行 `SelectionContextBuilderTest` 全部 6 个测试
Expected: 6/6 PASS(因为旧测试调用 `build(path, start, end)` 无 format 参数,用默认值 COLON_LINE,会失败!)

Wait — 需要先更新测试。让我修正这个步骤。

- [ ] **Step 2: 编译检查**

Run: IDEA → Build → Build Project
Expected: BUILD SUCCESSFUL。`build()` 新增默认参数,旧调用方 `SendSelectionAction` 无编译错误。

---

### Task 2: 更新现有测试以适配默认 COLON_LINE,并新增 COLON_LINE 测试

**Files:**
- Modify: `src/test/kotlin/com/mypi/plugin/SelectionContextBuilderTest.kt` (全文件重写)

**Interfaces:**
- Consumes: `SelectionContextBuilder.build(path, startLine, endLine, format)`
- Consumes: `SelectionFormat.COLON_LINE`, `SelectionFormat.GITHUB_ANCHOR`

- [ ] **Step 1: 重写 SelectionContextBuilderTest.kt**

```kotlin
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
```

- [ ] **Step 2: 运行全部测试**

Run: IDEA → 右键 `SelectionContextBuilderTest` → Run
Expected: 14/14 PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/mypi/plugin/SelectionContextBuilder.kt src/test/kotlin/com/mypi/plugin/SelectionContextBuilderTest.kt
git commit -m "feat: SelectionFormat 枚举 + build() 支持格式参数"
```

---

### Task 3: PiSettings 新增 selectionFormat 持久化字段

**Files:**
- Modify: `src/main/kotlin/com/mypi/plugin/PiSettings.kt` (编辑三处)

**Interfaces:**
- Produces: `PiSettings.selectionFormat: String` (空串 = COLON_LINE)

- [ ] **Step 1: PiSettings.kt 加字段声明**

在 `perfLoggingEnabled` 之后添加:

```kotlin
    /** 选区格式:"COLON_LINE"=冒号风格(@path:行号);"GITHUB_ANCHOR"=GitHub 风格(@path#L行号)。空串等价 COLON_LINE。 */
    @Volatile
    var selectionFormat: String = ""
```

- [ ] **Step 2: PiSettings.kt loadState() 加拷贝**

在 `loadState()` 方法内 `perfLoggingEnabled = state.perfLoggingEnabled` 之后添加:

```kotlin
        selectionFormat = state.selectionFormat
```

- [ ] **Step 3: IDE 编译检查**

Run: IDEA → Build → Rebuild Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/mypi/plugin/PiSettings.kt
git commit -m "feat: PiSettings 新增 selectionFormat 持久化字段"
```

---

### Task 4: PiConfigurable UI 新增格式下拉框

**Files:**
- Modify: `src/main/kotlin/com/mypi/plugin/PiConfigurable.kt` (多处编辑)

**Interfaces:**
- Consumes: `PiSettings.selectionFormat: String`
- Produces: 设置页下拉框 UI

- [ ] **Step 1: 添加导入和下拉框字段**

在文件顶部 `import` 区域新增:

```kotlin
import javax.swing.JComboBox
```

在 `perfLoggingCheckBox` 声明之后、`getDisplayName()` 之前添加:

```kotlin
    internal val formatCombo = JComboBox(arrayOf("冒号风格 (@path:行号)", "GitHub 风格 (@path#L行号)"))
```

- [ ] **Step 2: 在 createComponent() 中加入下拉框**

在 `panel.add(mcpCheckBox, gbc)` **之前**插入(GridBagConstraints gbc 已在前面声明):

```kotlin
        // 选区格式下拉框
        val formatLabeled = LabeledComponent.create(formatCombo, "选区格式:")
        panel.add(formatLabeled, gbc)
```

- [ ] **Step 3: 更新 isModified()**

在 `isModified()` 方法 return 表达式中,`perfLoggingCheckBox.isSelected != settings.perfLoggingEnabled` 之后加入:

```kotlin
            formatCombo.selectedIndex != formatIndexFromSetting(settings.selectionFormat) ||
```

- [ ] **Step 4: 更新 apply()**

在 `apply()` 方法内,`perfLoggingCheckBox.isSelected` 赋值之后添加:

```kotlin
        settings.selectionFormat = if (formatCombo.selectedIndex == 0) "" else SelectionFormat.GITHUB_ANCHOR.name
```

- [ ] **Step 5: 更新 reset()**

在 `reset()` 方法内,`perfLoggingCheckBox.isSelected` 赋值之后添加:

```kotlin
        formatCombo.selectedIndex = formatIndexFromSetting(settings.selectionFormat)
```

- [ ] **Step 6: 添加辅助方法**

在 `reset()` 方法之后、类结束 `}` 之前添加:

```kotlin
    /** 将 selectionFormat 持久化值映射为下拉框索引:空串/"COLON_LINE"→0, "GITHUB_ANCHOR"→1 */
    private fun formatIndexFromSetting(raw: String): Int {
        return if (raw == SelectionFormat.GITHUB_ANCHOR.name) 1 else 0
    }
```

- [ ] **Step 7: 编译和检查**

Run: IDEA → Build → Build Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/mypi/plugin/PiConfigurable.kt
git commit -m "feat: PiConfigurable 新增选区格式下拉框"
```

---

### Task 5: SendSelectionAction 读取格式并传递

**Files:**
- Modify: `src/main/kotlin/com/mypi/plugin/SendSelectionAction.kt` (两处编辑)

**Interfaces:**
- Consumes: `PiSettings.getInstance(project).selectionFormat`
- Consumes: `SelectionContextBuilder.build(path, startLine, endLine, format)`

- [ ] **Step 1: 在 actionPerformed() 开头读取格式**

在 `val doc = editor.document` 之后添加:

```kotlin
        val settings = PiSettings.getInstance(project)
        val format = parseFormat(settings.selectionFormat)
```

修改两处 `SelectionContextBuilder.build()` 调用,在末尾加 `, format`:

```kotlin
        val text = if (!selectedText.isNullOrEmpty()) {
            val startLine = doc.getLineNumber(selModel.selectionStart) + 1
            val endLine = doc.getLineNumber(selModel.selectionEnd) + 1
            SelectionContextBuilder.build(path, startLine, endLine, format)
        } else {
            val caretLine = doc.getLineNumber(editor.caretModel.offset) + 1
            SelectionContextBuilder.build(path, caretLine, caretLine, format)
        }
```

- [ ] **Step 2: 添加 parseFormat 私有方法**

在 `resolvePath()` 方法之后、类结束 `}` 之前添加:

```kotlin
    /**
     * 将持久化的格式字符串解析为 [SelectionFormat] 枚举。
     * 空串或非法值回退到 [SelectionFormat.COLON_LINE]。
     */
    private fun parseFormat(raw: String): SelectionFormat {
        if (raw.isEmpty()) return SelectionFormat.COLON_LINE
        return try {
            SelectionFormat.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            SelectionFormat.COLON_LINE
        }
    }
```

- [ ] **Step 3: 编译检查**

Run: IDEA → Build → Build Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 运行全部测试**

Run: IDEA → 右键 `src/test` → Run 'All Tests'
Expected: ALL TESTS PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/mypi/plugin/SendSelectionAction.kt
git commit -m "feat: SendSelectionAction 读取格式设置并传递 build()"
```

---

### Task 6: 集成验证与清理

**Files:**
- Modify: 检查并更新 README.md 中关于格式的描述

- [ ] **Step 1: 更新 README.md 格式描述**

Check inline: `@相对路径#L起-止行` → `@路径:起-止行(默认) / @路径#L起-止行(GitHub 风格,可在设置中切换)`

- [ ] **Step 2: 再次运行全部测试确认**

Run: IDEA → 右键 `src/test` → Run 'All Tests'
Expected: ALL TESTS PASS

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: README 更新选区格式描述,反映新增冒号格式选项"
```

---

### 验证清单

- [ ] `SelectionContextBuilderTest` 15 个测试全部通过
- [ ] 默认格式(不传 format)产出冒号格式 `@path:行号`
- [ ] `COLON_LINE` 显式传入产出 `@path:行号`
- [ ] `GITHUB_ANCHOR` 显式传入产出 `@path#L行号`
- [ ] PiConfigurable 下拉框默认选中"冒号风格"
- [ ] 下拉框切换到 "GitHub 风格" 后 apply,重启后仍为 GitHub 风格
- [ ] 空配置串回退到 COLON_LINE
- [ ] 编译无警告
