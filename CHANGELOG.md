# Changelog

本文件记录 MypiPlugin 各版本的变更。发版时 GitHub Actions 会自动读取对应版本段作为 Release 说明。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号语义遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [0.2.3] - 2026-11-06

### Fixed
- 修复插件兼容范围停留在 `262.*`、导致 IntelliJ IDEA 2026.3 拒绝安装或禁用插件的问题。

### Changed
- 新增 Plugin Verifier 双端检查(IJ Ultimate 2026.2 + 2026.3),发版流水线同步加入 `verifyPlugin` 关卡,防止实验性终端 API 跨版本移除再次逃逸。
- 自定义插件仓库的兼容范围同步扩展到 `263.*`。

## [0.2.2] - 2026-08-20

### Fixed
- 修复 Pi 异步启动的 EDT 线程边界、重复启动竞态和项目关闭后的后台任务残留问题。
- 项目外本地文件使用规范化绝对路径,避免同名文件导致定位歧义。
- 收紧多项目 MCP 配置写锁范围,并增强损坏 JSON 的保护性处理。

### Changed
- 冒号风格定位串统一为 `@path:行号`,与 GitHub 风格 `@path#L行号`保持一致。

## [0.2.1] - 2026-08-06

### Fixed
- 修正插件描述与实际格式不符的问题:发版描述现已与实际注入格式(`@path:行号` / `@path#L行号`)一致。

### Changed
- 发版描述改为从 `plugin.xml` 自动提取,消除 `updatePlugins.xml` 与插件描述的双份硬编码,单一真相源。
- 新增 `CHANGELOG.md`,Release 说明从手动 commit 列表改为自动读取对应版本段,团队成员可直观看到更新内容。

## [0.2.0] - 2026-08-06

### Added
- **选区格式多选项**:Settings → Tools → Pi 新增格式下拉框,可在两种行锚点风格间切换:
  - 冒号风格(默认):`@src/App.kt:10-20`(单行 `@src/App.kt:42`)
  - GitHub 风格:`@src/App.kt#L10-20`(单行 `@src/App.kt#L42`)

### Changed
- 默认选区格式从 GitHub 风格(`@path#L行号`)改为冒号风格(`@path:行号`)。老用户若需沿用原格式,去设置页切一次即可。

## [0.1.0] - 2026-07-28

### Added
- **复用 IDE 内置终端跑 Pi**:点右侧 Pi 图标即在 IDE 终端启动 Pi coding agent,无需自建面板、无需 RPC 协议。
- **一键注入选区定位**:`Ctrl+Alt+;` 把选区/光标位置以定位串形式填入 Pi 终端输入行(只填不发送),焦点自动接力到 Pi 输入区。
- **IDE MCP 自动集成**(2025.2+):启动 Pi 时自动注入 IDEA MCP Server,让 Pi 获得读文件、搜符号、构建、调试等 IDE 工具。
- **可配置 Pi 路径**:Settings → Tools → Pi 支持自定义 Pi 可执行路径,不强制依赖 PATH。
- **稳定重启**:手动退出 Pi 后再点图标自动重启新会话。
