# @相对路径#L行号 注入格式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `SendSelectionAction` 注入到 pi 终端的定位串,从 `文件名:行号` 升级为 GitHub 行锚点风格的 `@<相对项目根路径>#L<行号>`。

**Architecture:** 分两层——`SelectionContextBuilder`(纯逻辑、零 IntelliJ 依赖,只拼 `@path#LlineSpec`)负责格式;`SendSelectionAction`(平台层)用 `VfsUtilCore.getRelativePath` 把 `VirtualFile` 解析为相对项目根路径,算不出则降级裸文件名。Builder 继续留在 Tier1 测试。

**Tech Stack:** Kotlin、IntelliJ Platform 2026.2、Gradle Wrapper(`./gradlew`)、JUnit 5 jupiter(Tier1)、`BasePlatformTestCase`(Tier2)。

## Global Constraints

(所有任务隐含遵守,源自 spec 与项目规范)

- **JDK 21**,IntelliJ Platform **2026.2+**。
- 构建工具是 **Gradle Wrapper**(`./gradlew`),**不是 Maven**;项目无 `pom.xml`。
- **优先 IDEA MCP** 做编译/测试(`mcp__idea__build_project` / `mcp__idea__execute_run_configuration`);不可用时回退 `./gradlew`。
- **产物格式(契约)**:`@<相对项目根路径>#L<行号>`;路径分隔符 `/`;单行 `#L15`,多行 `#L10-20`(仅首行带 `L`);项目外文件降级为 `@<裸文件名>#L<行号>`。
- **`SelectionContextBuilder` 必须保持零 IntelliJ 依赖**(Tier1 测试约束),路径解析不得进入此类。
- **中文注释**(方法/类/关键分支说明做什么、为什么);标识符英文;注释标点用英文标点。
- 测试同步规范:改生产代码签名时同步改测试,改完跑 `test-compile`/测试验证编译与通过。

---

## File Structure

| 文件 | 责任 | 本次动作 |
|------|------|---------|
| `src/main/kotlin/com/mypi/plugin/SelectionContextBuilder.kt` | 纯逻辑拼串:`@path#LlineSpec` | 改(合并方法、改格式) |
| `src/test/kotlin/com/mypi/plugin/SelectionContextBuilderTest.kt` | Tier1 断言(零依赖) | 改(断言全部对齐新格式) |
| `src/main/kotlin/com/mypi/plugin/SendSelectionAction.kt` | 平台层:解析相对路径 + 调 Builder + 注入 | 改(加 `resolvePath`、改调用、删过时注释) |
| `src/test/kotlin/com/mypi/plugin/SendSelectionActionTest.kt` | Tier2:update 启用逻辑 | 改(仅注释措辞,断言不动) |
| `README.md` | 用户文档 | 改(line 9 / 68-73 / 123 产物格式描述) |

---

## Task 1: SelectionContextBuilder 改为 `@path#LlineSpec`(含 Tier1 测试)

**Files:**
- Modify: `src/test/kotlin/com/mypi/plugin/SelectionContextBuilderTest.kt`(全量替换)
- Modify: `src/main/kotlin/com/mypi/plugin/SelectionContextBuilder.kt`(全量替换)

**Interfaces:**
- Produces: `SelectionContextBuilder.build(path: String, startLine: Int, endLine: Int): String`——返回 `@$path#L$lineSpec`,`startLine==endLine` 时 `lineSpec` 为单行号。删除旧 `buildFileLocator`。

- [ ] **Step 1: 写失败测试(全量替换测试文件)**

把 `src/test/kotlin/com/mypi/plugin/SelectionContextBuilderTest.kt` 整体替换为:

```kotlin
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
```

- [ ] **Step 2: 跑测试确认失败**

优先 IDEA MCP(运行 `SelectionContextBuilderTest`);回退命令行:

```bash
./gradlew test --tests "com.mypi.plugin.SelectionContextBuilderTest"
```

Expected: FAIL——旧实现产 `UserService.java:10-20` 等,与新断言 `@...#L...` 不符;`buildFileLocator` 被删后 `@Test` 引用若残留也会编译失败(本步已全量替换,无残留)。

- [ ] **Step 3: 写最小实现(全量替换生产文件)**

把 `src/main/kotlin/com/mypi/plugin/SelectionContextBuilder.kt` 整体替换为:

```kotlin
package com.mypi.plugin

/**
 * 选区 → pi 终端注入的"定位串"构造器(纯逻辑,零 IntelliJ 依赖)。
 *
 * 产物采用 GitHub 行锚点风格:@<path>#L<行号>。
 *  - path 由调用方(SendSelectionAction)解析为"相对项目根路径",项目外文件降级为裸文件名;此处原样透传。
 *  - 行号:单行 #L15;多行 #L10-20(仅首行带 L)。
 *
 * 只发定位、不发代码内容:pi 在项目目录跑(终端方案),作为 coding agent 能自行读取项目文件,
 * 只发定位可保持 pi 输入行简洁;加 @ 前缀与 #L 行锚,让 pi 更可靠识别为文件引用。
 */
object SelectionContextBuilder {

    /**
     * 产出 pi 注入串:@<path>#L<lineSpec>。
     *
     * @param path      已解析的文件路径(相对项目根,或降级后的裸文件名),原样透传
     * @param startLine 起始行(1-based)
     * @param endLine   结束行(1-based);与 startLine 相同时合并为单行号
     */
    fun build(path: String, startLine: Int, endLine: Int): String {
        val lineSpec = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
        return "@$path#L$lineSpec"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

优先 IDEA MCP;回退:

```bash
./gradlew test --tests "com.mypi.plugin.SelectionContextBuilderTest"
```

Expected: PASS(6 个用例全绿)。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/mypi/plugin/SelectionContextBuilder.kt src/test/kotlin/com/mypi/plugin/SelectionContextBuilderTest.kt
git commit -m "feat:SelectionContextBuilder 改为 @相对路径#L行号 格式,合并 build/buildFileLocator"
```

---

## Task 2: SendSelectionAction 解析相对项目根路径

**Files:**
- Modify: `src/main/kotlin/com/mypi/plugin/SendSelectionAction.kt`
- Modify: `src/test/kotlin/com/mypi/plugin/SendSelectionActionTest.kt`(仅注释)

**Interfaces:**
- Consumes: Task 1 的 `SelectionContextBuilder.build(path, startLine, endLine)`(已删除 `buildFileLocator`)。
- Produces: 注入文本改为 `@<相对项目根路径>#L<行号>`;新增 private `resolvePath(project, file)`。

- [ ] **Step 1: 改 SendSelectionAction(加 import / resolvePath / 改调用 / 删过时注释)**

在 `SendSelectionAction.kt` 顶部 import 区追加(现有 import 之后):

```kotlin
import com.intellij.openapi.project.ProjectUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
```

把整个文件替换为(保持包名与类名,更新类注释、调用、新增 resolvePath):

```kotlin
package com.mypi.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * 快捷键动作(Ctrl+Alt+;):把当前选区或光标定位注入到 pi 终端(只填不发送)。
 *
 * 上下文判断(决策#7):
 *  - 有选区 → [SelectionContextBuilder.build] 产出 "@相对路径#L起-止行"。
 *  - 无选区 → [SelectionContextBuilder.build] 产出 "@相对路径#L光标行"(传 caretLine 两次)。
 *
 * 路径经 [resolvePath] 解析为"相对项目根"(与 pi 启动的工作目录同源);项目外文件降级为裸文件名。
 * 注入经 [PiTerminalService.inject];若 pi 终端未开,弹框提示去点启动按钮(决策#10)。
 */
class SendSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val path = resolvePath(project, file)
        val doc = editor.document
        val selModel = editor.selectionModel
        val selectedText = selModel.selectedText

        // 有选区发范围;无选区发单行(传 caretLine 两次,build 内部合并为 #L单行号)
        val text = if (!selectedText.isNullOrEmpty()) {
            val startLine = doc.getLineNumber(selModel.selectionStart) + 1
            val endLine = doc.getLineNumber(selModel.selectionEnd) + 1
            SelectionContextBuilder.build(path, startLine, endLine)
        } else {
            val caretLine = doc.getLineNumber(editor.caretModel.offset) + 1
            SelectionContextBuilder.build(path, caretLine, caretLine)
        }

        // 注入到 pi 终端:按 InjectResult 分支决定聚焦或弹框提示(sealed 类型,编译期穷举)
        val service = project.service<PiTerminalService>()
        when (val result = service.inject(text)) {
            InjectResult.Success -> service.focusAndActivatePi() // 注入成功,焦点切到 pi 终端(用户直接补问题+回车)
            InjectResult.TerminalNotOpen -> Messages.showInfoMessage("Pi 终端未开启,请点右侧 Pi 面板的启动按钮", "Pi")
            is InjectResult.Failed -> Messages.showInfoMessage(result.message, "Pi") // 注入失败等异常
        }
    }

    override fun update(e: AnActionEvent) {
        // 始终启用(决策#7):无选区也允许,发"@相对路径#L行号"定位;仅需有编辑器
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    /**
     * 解析文件路径为"相对项目根"(与 pi 启动工作目录 project.basePath 同源,pi 视角等价于绝对路径)。
     * 用 VirtualFile 原生 [VfsUtilCore.getRelativePath](全程 `/` 规范化),规避 Windows basePath 反斜杠
     * 与 file.path 正斜杠混用导致的字符串误判。算不出相对路径(项目外 / jar 内 / 临时文件 / 无项目根)
     * 时降级为裸文件名,再降级"未知文件"。
     */
    private fun resolvePath(project: Project, file: VirtualFile?): String {
        if (file == null) return "未知文件"
        val baseDir = ProjectUtil.guessProjectDir(project) ?: return file.name
        return VfsUtilCore.getRelativePath(file, baseDir, '/') ?: file.name
    }
}
```

- [ ] **Step 2: 改 SendSelectionActionTest 注释措辞(断言不动)**

`update` 启用逻辑不变,三个 `testUpdate*` 用例断言保持原样。仅把类 KDoc 与行内注释里"文件名:行号"/"发定位"措辞对齐新格式:

- 第 14 行附近注释 `无选区也允许发"文件名:行号"` → `无选区也允许发"@相对路径#L行号"`
- 第 36 行注释 `无选区也启用(发"文件名:行号"定位),不再灰显` → `无选区也启用(发"@相对路径#L行号"定位),不再灰显`
- 第 40 行断言文案 `无选区时动作也应启用(发定位)` 保持不变(仍准确)

(若行号因编辑发生偏移,以"文件名:行号"字面 grep 定位替换;`assertTrue/assertFalse` 语句本身不改。)

- [ ] **Step 3: 编译验证**

优先 IDEA MCP:`mcp__idea__build_project`(或 lint `SendSelectionAction.kt` + `SelectionContextBuilder.kt`)。
回退:

```bash
./gradlew compileKotlin
```

Expected: 编译通过,无未使用 import 警告(新增三个 import 均被 `resolvePath` 使用)。

- [ ] **Step 4: 跑 SendSelectionActionTest 确认 update 不回归**

优先 IDEA MCP 运行 `SendSelectionActionTest`;回退:

```bash
./gradlew test --tests "com.mypi.plugin.SendSelectionActionTest"
```

Expected: PASS(3 个 update 用例全绿;产物格式改动不影响 update 启用逻辑)。

- [ ] **Step 5: 手动验证产物格式**

在 IDE 内(沙盒 `./gradlew prepareSandbox` 或已装插件):

1. 点 Pi 图标启动 pi 终端;
2. 打开项目内任意文件(如 `SendSelectionAction.kt`),选中第 10-20 行 → `Ctrl+Alt+;`;
3. 确认 pi 终端输入行注入文本为 **`@src/main/kotlin/com/mypi/plugin/SendSelectionAction.kt#L10-20`**(路径相对项目根、`/` 分隔);
4. 光标停在第 15 行(不选)→ `Ctrl+Alt+;`,确认注入 **`@...#L15`**;
5. (可选)打开 jar 内反编译文件选区触发,确认降级为 **`@<裸文件名>#L...`**。

Expected: 产物与契约一致。若路径为反斜杠或缺少 `@/#L`,回查 `resolvePath` 与 Builder。

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/mypi/plugin/SendSelectionAction.kt src/test/kotlin/com/mypi/plugin/SendSelectionActionTest.kt
git commit -m "feat:SendSelectionAction 解析相对项目根路径,产物改 @相对路径#L行锚"
```

---

## Task 3: README 同步产物格式

**Files:**
- Modify: `README.md`(line 9、68-73、122-123)

**Interfaces:**
- 无代码契约;仅文档与 Task 1/2 产出的实际格式对齐。

> 注:spec 原写"README 第 123/150 行"。核实后——第 150 行(测试覆盖说明)不涉及产物格式,**不改**;实际涉及格式的为 line 9、68-73、123。本任务按实际行精确改。

- [ ] **Step 1: 改 README 三处**

**① 第 9 行**(特性列表):

旧:
```
- 🚀 **一键注入选区定位**:`Ctrl+Alt+;` 把当前选区/光标位置以 `文件名:起-止行` 形式填入 pi 终端输入行(**只填不发送**,留给你补问题)
```
新:
```
- 🚀 **一键注入选区定位**:`Ctrl+Alt+;` 把当前选区/光标位置以 `@相对路径#L起-止行` 形式填入 pi 终端输入行(**只填不发送**,留给你补问题)
```

**② 第 68-73 行**(注入示例代码块):

旧:
```
注入的内容形如:

```
UserService.java:10-20      # 有选区:文件名:起始行-结束行(1-based)
UserService.java:42          # 无选区:文件名:光标行号
```
```
新:
```
注入的内容形如:

```
@src/main/java/com/x/UserService.java#L10-20   # 有选区:@相对路径#L起始行-结束行(1-based)
@src/main/java/com/x/UserService.java#L42       # 无选区:@相对路径#L光标行号
```
```

**③ 第 123 行**(项目结构目录树注释):

旧:
```
├── SelectionContextBuilder.kt  # 选区 → "文件名:行号" 定位串构造(纯逻辑)
```
新:
```
├── SelectionContextBuilder.kt  # 选区 → "@相对路径#L行号" 定位串构造(纯逻辑)
```

(第 122 行 `SendSelectionAction.kt` 注释"有选区发范围,无选区发单行定位"描述行为而非格式,保持不变。)

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs:README 同步 @相对路径#L行号 注入格式"
```

---

## Self-Review 结论

- **Spec 覆盖**:spec 的"分层 / Builder 改造 / resolvePath / 测试对照 / README / 非目标 / 风险"均有任务对应——Builder 与 Tier1 测试在 Task 1;SendSelectionAction 与 update 测试在 Task 2;README 在 Task 3;降级与路径分隔符风险在 Task 2 Step 1 注释 + Step 5 验证覆盖。✓
- **占位符**:无 TBD/TODO;每步含实际代码或精确 grep 目标。✓
- **类型一致**:Task 1 定义的 `build(path, startLine, endLine)` 与 Task 2 调用签名一致;`resolvePath(project, file)` 产出 `String` 传入 `build` 首参;`buildFileLocator` 在 Task 1 删除、Task 2 不再引用。✓
- **README 行精确化**:已在 Task 3 注明 spec 的 line 150 不改、实际改 9/68-73/123。✓
