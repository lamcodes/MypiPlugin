# 选区格式多选项

日期: 2026-08-06

## 概述

`SelectionContextBuilder` 当前固定输出 GitHub 行锚点格式(`@path#L行号`)。新增冒号格式(`path:行号`)选项，用户在 Settings → Tools → Pi 中通过下拉框选择默认格式。默认使用冒号格式。

## 枚举

`SelectionFormat` — 两种行锚点格式:

| 枚举值 | 单行产物 | 多行产物 |
|---|---|---|
| `COLON_LINE` (默认) | `path:78` | `path:78-85` |
| `GITHUB_ANCHOR` | `@path#L78` | `@path#L78-85` |

## 变更点

### SelectionContextBuilder.kt

`build(path, startLine, endLine)` → `build(path, startLine, endLine, format: SelectionFormat = SelectionFormat.COLON_LINE)`。根据 `format` 分支:

- `COLON_LINE`: `"$path:$lineSpec"` (lineSpec 同现逻辑: 单行 `"78"`, 多行 `"78-85"`)
- `GITHUB_ANCHOR`: `"@$path#L$lineSpec"` (现有行为)

### PiSettings.kt

新增字段: `var selectionFormat: String = ""`。空串等价 `COLON_LINE`。持久化到 `pi-settings.xml`。

### PiConfigurable.kt

新增下拉框控件 `JComboBox<String>`，选项:
- "冒号风格 (path:行号)"
- "GitHub 风格 (@path#L行号)"

位置: 路径输入框之下、MCP 开关之上。`isModified()`、`apply()`、`reset()` 同步处理。

### SendSelectionAction.kt

从 `PiSettings.getInstance(project).selectionFormat` 读取格式枚举，传给 `SelectionContextBuilder.build()`。

### SelectionContextBuilderTest.kt

新增冒号格式的对称测试用例 (单行、多行、无选区、多点文件名、纯定位、路径透传)。

## 兼容性

- `PiSettings.selectionFormat` 空串 → `COLON_LINE` (默认)
- 老用户升级: 首次注入自动用冒号格式；切回 GitHub 格式需去设置页选一次
- `GITHUB_ANCHOR` 保持与旧版 `SelectionContextBuilder.build(path, startLine, endLine)` 完全一致的行为
