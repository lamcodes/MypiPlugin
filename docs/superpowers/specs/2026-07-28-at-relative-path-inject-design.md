# 选中代码注入格式调整为 `@相对路径#L行号`

- 日期: 2026-07-28
- 状态: 待哥批准
- 关联: `SelectionContextBuilder` / `SendSelectionAction`

## 背景

当前 `SendSelectionAction`(Ctrl+Alt+;)经 `SelectionContextBuilder` 产出"文件名:行号"定位串注入 pi 终端:

- 有选区: `build(fileName, start, end)` → `UserService.java:10-20`
- 无选区: `buildFileLocator(fileName, line)` → `UserService.java:15`

只发定位、不发代码内容是哥既定决策 (理由:`pi` 跑在项目目录、作为 coding agent 能自行读文件;且只发定位可保持 pi
输入行简洁)。

哥现要求调整:加 `@` 前缀,路径从"裸文件名"改为"相对项目根的完整路径",并采用 **GitHub 行锚点风格**——`#` 分隔、行号带 `L`
前缀,让 pi 更可靠识别为文件引用。

## 目标

产物统一为 `@<相对项目根路径>#L<行号>`:

| 场景                            | 产物示例                                       |
|---------------------------------|------------------------------------------------|
| 多行选区                        | `@src/main/java/com/x/UserService.java#L10-20` |
| 单行选区 / 纯光标行             | `@src/main/java/com/x/UserService.java#L15`    |
| 项目外文件(jar / 临时文件,降级) | `@UserService.java#L15`                        |

格式约定:

- 分隔符用 `#`、行号带 `L` 前缀 (GitHub 行锚点风格,参照哥示例 `#L35-41`)。
- 多行仅首个行号带 `L`(`#L10-20`),与哥示例一致。
- 路径分隔符用 `/`(跨平台、pi 友好;`VfsUtilCore.getRelativePath` 默认即 `/`)。

## 设计

### 分层 (守住现有 Tier1 测试约束)

- **`SelectionContextBuilder`**: 纯逻辑、零 IntelliJ 依赖,只负责拼串。继续可在 Tier1 (JUnit jupiter,几毫秒)测。
- **`SendSelectionAction`**: 平台层,负责把 `VirtualFile` 解析成相对路径 (或降级)。

理由:若让 Builder 直接吃 `VirtualFile`,它会被拖进平台依赖,Tier1 测试就得降级成慢得多的 `BasePlatformTestCase`。

### `SelectionContextBuilder` 改造

合并 `build` + `buildFileLocator` 为单个方法:

```kotlin
object SelectionContextBuilder {
    /**
     * 产出 pi 注入串:@<path>#L<lineSpec>。
     *
     * @param path 已解析的文件路径(相对项目根,或降级后的裸文件名),原样透传
     * @param startLine 起始行(1-based)
     * @param endLine   结束行(1-based)
     */
    fun build(path: String, startLine: Int, endLine: Int): String {
        val lineSpec = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
        return "@$path#L$lineSpec"
    }
}
```

- `path` 原样透传:Builder 不关心它是相对路径还是裸文件名。
- `startLine == endLine` 输出单行号 (`L15`),消除原 `5-5` 写法。
- 删除 `buildFileLocator`:无选区改为传 `caretLine, caretLine` 复用 `build`。

### `SendSelectionAction` 改造

新增 private 路径解析 (用 VirtualFile 原生 API,规避 Windows 路径分隔符陷阱):

```kotlin
private fun resolvePath(project: Project, file: VirtualFile?): String {
    if (file == null) return "未知文件"
    val baseDir = ProjectUtil.guessProjectDir(project)      // 项目根 VirtualFile
    if (baseDir != null) {
        val rel = VfsUtilCore.getRelativePath(file, baseDir, '/')
        if (rel != null) return rel
    }
    return file.name   // 项目外 / jar / 临时文件 / 无 basePath:降级裸文件名
}
```

调用方:

- 有选区: `build(resolvePath(project, file), selStart, selEnd)`
- 无选区: `build(resolvePath(project, file), caretLine, caretLine)`

同步删除第 13 行"代码围栏"过时注释;类注释改为"@相对项目根路径#L行号"。

新增 import:

- `com.intellij.openapi.project.ProjectUtil`
- `com.intellij.openapi.vfs.VfsUtilCore`
- `com.intellij.openapi.vfs.VirtualFile`

### 测试更新

#### `SelectionContextBuilderTest`(Tier1,必改)

| 用例                       | 旧断言                   | 新断言                                                                                                 |
|----------------------------|--------------------------|--------------------------------------------------------------------------------------------------------|
| 多行选区                   | `UserService.java:10-20` | `@UserService.java#L10-20`                                                                             |
| 起止相同                   | `a.kt:5-5`               | `@a.kt#L5`                                                                                             |
| 无选区(`buildFileLocator`) | `Foo.java:42`            | 改用 `build("Foo.java",42,42)` → `@Foo.java#L42`                                                       |
| 多点文件名                 | `my.service.spec.ts:1-9` | `@my.service.spec.ts#L1-9`                                                                             |
| 长区间                     | `App.kt:1-100`           | `@App.kt#L1-100`                                                                                       |
| **新增** 路径透传          | —                        | `build("src/main/java/com/x/UserService.java",10,20)` → `@src/main/java/com/x/UserService.java#L10-20` |

#### `SendSelectionActionTest`

`update` 启用逻辑不变,L35-41 继续适用,断言不动。仅把类/行内注释里"文件名:行号"措辞对齐为"@相对路径#L行号"(保持文档准确)。

#### `README.md`

第 123 行目录树注释、第 150 行说明中的"文件名:行号",更新为"@相对项目根路径#L行号 (项目内);@文件名#L行号 (项目外降级)"。

## 非目标 (YAGNI)

- 不恢复发代码内容 (维持只发定位)。
- 不引入配置项切换"相对 / 绝对 / 裸文件名"(暂无需求)。
- 不对 `resolvePath` 加单测 (平台依赖,降级逻辑仅一行 `?:`,与 `actionPerformed` 同档靠手动验证)。

## 风险与验证

- **风险1 (路径分隔符)**: 已通过选用 `VfsUtilCore.getRelativePath(file, baseDir, '/')`(VirtualFile 原生、全程 `/` 规范化)
  规避;不使用 `FileUtil.getRelativePath(字符串,字符串)`。产出统一为 `/`,与哥示例里 Windows 复制产物的 `\` 不同——这是有意为之
  (跨平台、pi 友好)。
- **风险2 (project 无 basePath)**: `ProjectUtil.guessProjectDir` 返回 null → 降级裸文件名,安全。
- **风险3 (行锚点格式)**: 多行用 `#L10-20`(仅首行带 L),严格对齐哥示例 `#L35-41`;若 pi 或后续工具要求 `#L10-L20`,再调。
- **验证**:
    1. `mvn test-compile` + `SelectionContextBuilderTest` 全绿 (Tier1);
    2. IDEA MCP 对项目内真实文件手动触发一次,确认产物为 `@src/...#L行号`;
    3. 对项目外文件 (如 jar 内反编译文件)手动触发一次,确认降级为 `@文件名#L行号`。
