# MypiPlugin

> 在 IntelliJ IDEA 里一键把「光标/选区位置」注入 Pi 终端,让 Pi coding agent 直接读你的代码。

MypiPlugin 是一个 IntelliJ Platform 插件,复用 IDE **内置终端**(Reworked Terminal)跑 [Pi](https://www.npmjs.com/package/@earendil-works/pi-coding-agent) coding agent。**选中代码 → `Ctrl+Alt+;` → 定位串注入到 pi 输入行(只填不发送)**,焦点自动切到 pi 终端,你补一句问题回车即可,全程不离开 IDE。启动 Pi 时,在 MCP 可用且开关开启的情况下自动注入 IDEA MCP Server,让 Pi 直接拿到 IDE 的读文件、搜符号、构建、调试等工具。

## 特性

- 🚀 **一键注入选区定位**:`Ctrl+Alt+;` 把当前选区/光标位置填入 pi 终端输入行(**只填不发送**,留给你补问题);格式可在 Settings → Tools → Pi 切换(默认冒号风格 `@path:行号`,也可选 GitHub 风格 `@path#L行号`)
- 🖥️ **复用内置终端跑 pi**:点右侧 Pi 图标即在 IDE 终端启动 pi,无需自建面板、无需 RPC 协议;pi 跑在项目目录,作为 coding agent 能自行读取项目文件
- 🎯 **焦点自动接力**:注入成功后焦点自动落到 pi 终端输入区,直接打字补问题 + 回车,无需手动切窗口
- ♻️ **稳定重启**:手动退出 pi 后再点 Pi 图标会自动重启新会话(每次点图标都触发,不止首次)
- ⚙️ **可配置 Pi 路径**:Settings → Tools → Pi,支持自定义 pi 可执行路径,不强制依赖 PATH
- 🔌 **IDE MCP 自动集成**:启动 Pi 时,在 MCP 可用且开关开启的情况下自动注入 IDEA MCP Server(2025.2+),让 Pi 获得读文件、搜代码、构建、调试等 IDE 工具;可在 Settings → Tools → Pi 关闭

## 前置要求

| 依赖 | 版本 / 说明 |
|---|---|
| IntelliJ IDEA | **2026.2–2026.3**(Community 或 Ultimate) |
| JDK | 21(构建插件用) |
| Pi CLI | `npm install -g --ignore-scripts @earendil-works/pi-coding-agent` |
| LLM Provider | Pi 需配置 provider(如 `ANTHROPIC_API_KEY`)才能产生回复 |

> ⚠️ **关于 IDE 版本**:插件依赖 IntelliJ 2026.2 才稳定提供的 Reworked Terminal 新 API(`TerminalView.createSendTextBuilder` / `sendEndKeyBeforeText` 等),当前兼容 2026.2–2026.3。低于 2026.2 的版本缺少所需 API,高于 2026.3 的版本需等待兼容性验证。
>
> ⚠️ Pi 是独立的 coding agent CLI,本插件只负责 IDE 集成。Pi 的安装与 LLM 配置请参考 Pi 官方文档。

## 安装

### 方式一:添加自定义插件仓库(推荐,支持自动更新)

插件通过 GitHub Releases 分发。团队只需添加一次仓库地址,后续发版 IDE 会自动提示更新。

1. **Settings → Plugins → ⚙️ → Manage Plugin Repositories...**
2. 点 `+` 添加仓库地址:
   ```
   https://github.com/lamcodes/MypiPlugin/releases/latest/download/updatePlugins.xml
   ```
3. 回到 Marketplace,搜索 `MypiPlugin` → Install → 重启 IDE

> 仓库地址始终指向最新 Release 的 `updatePlugins.xml`,发新版后无需团队改地址。

### 方式二:从磁盘安装(单次手动)

直接下载对应版本的 zip,然后 **Settings → Plugins → ⚙️ → Install Plugin from Disk** → 选 zip → 重启。

下载地址:`https://github.com/lamcodes/MypiPlugin/releases/latest`

### 方式三:从源码构建(开发用)

```bash
git clone https://github.com/lamcodes/MypiPlugin.git
cd MypiPlugin
./gradlew buildPlugin
```

构建产物:`build/distributions/MypiPlugin-0.2.3.zip`。

## 使用

### 1. 启动 Pi

点击右侧工具栏的 **Pi** 图标。插件在 IDE 内置终端打开一个名为 `Pi` 的 tab,并在项目目录下启动 pi 进程。

- 首次点击:启动新 pi 会话
- pi 已运行:聚焦到现有 pi 终端
- 手动退出 pi 后再点:自动重启新会话

> 启动失败会在 Pi 面板显示原因(通常是没找到 pi 可执行文件,见下面「配置 Pi 路径」)。

### 2. 配置 Pi 路径(可选)

若 `pi` 已在系统 `PATH` 中,可跳过此步。否则:

**Settings → Tools → Pi** → 填入 pi 可执行文件的绝对路径 → **Apply**。

> **Windows 用户**:优先填安装目录下的 `pi.exe`;其次 `pi.cmd` / `pi.bat`。留空则从 `PATH` 查找。

### 3. 注入选区 / 光标位置

1. 在编辑器中**选中一段代码**(或不选,只用光标定位)
2. 按 **`Ctrl+Alt+;`**(macOS: `⌃⌥;`),或右键菜单 → **Send Selection to Pi**
3. 定位串自动填入 pi 终端输入行,**焦点切换到 pi 终端**

注入的内容形如:

```
@src/main/java/com/x/UserService.java:10-20      # 有选区(冒号格式,默认):路径:起始行-结束行(1-based)
@src/main/java/com/x/UserService.java:42          # 无选区(冒号格式,默认):路径:光标行号
```

项目外的本地文件使用规范化绝对路径,例如 `@C:/work/shared/Config.kt:10`;这能避免同名文件冲突,但路径可能包含本机目录信息且不适合跨机器复用。

> **为什么只发定位不发代码?** pi 在项目目录下作为 coding agent 运行,能自行读取项目文件;插件只发定位串,保持 pi 输入行简洁,你补一句问题(如"这段为什么慢")直接回车即可。

### 4. 补问题并回车

焦点已在 pi 终端,直接输入问题 → `Enter` 发送给 pi。

## 配置项

所有配置集中在 **Settings → Tools → Pi**。

| 配置 | 默认 | 说明 |
|---|---|---|
| pi 可执行路径 | 空(用 PATH) | pi 的绝对路径;留空则从系统 PATH 查找 |
| 选区格式 | 冒号风格 | 注入定位串的格式;可选"冒号风格 (@path:行号)"或"GitHub 风格 (@path#L行号)" |
| 启用 IDE MCP 集成 | 开 | 启动 Pi 时自动注入 IDEA MCP Server(需 IDEA MCP 可用),让 Pi 获得读文件/搜代码/构建/调试等工具(IDEA 2025.2+) |
| 开启去重检测 | 开 | 若 Pi 已通过 claude-code 等 import 获得 IDEA MCP,则跳过重复注入,避免 mcp.json 出现重复条目 |
| 开启性能计时日志 | 关 | 各启动阶段耗时输出到 idea.log,排查启动慢用 |

## 故障排查

**点 Pi 图标后显示「启动失败:未找到 pi」**
- 终端执行 `pi --version` 确认可用;若不行,在 Settings → Tools → Pi 显式配置 pi 绝对路径
- Windows 下配 `.cmd` 仍失败时,改配安装目录下的 `.exe`

**按 `Ctrl+Alt+;` 弹框「Pi 终端未开启」**
- 先点右侧 Pi 图标启动 pi,再注入

**快捷键无效 / 打出别的字符**
- `Ctrl+Alt+;` 是为避开中文输入法冲突刻意选的(原 `Ctrl+Alt+P` 会被 IME 拦截打 p,`Ctrl+Shift+*` 是输入法切换)。若仍冲突,可在 Settings → Keymap 里改绑 `Send Selection to Pi`

**插件安装后不生效 / 注入时报错**
- 确认 IDE 版本为 **2026.2 或 2026.3**(低于此版本缺 Reworked Terminal API,更高版本尚未验证)
- 重启 IDE

## 开发

### 构建命令

```bash
./gradlew compileKotlin      # 仅编译
./gradlew buildPlugin        # 打卡产出 zip
./gradlew verifyPlugin       # Plugin Verifier:对 IDEA 2026.2/2026.3 做二进制兼容检查
./gradlew prepareSandbox     # 构建并装入沙盒 IDE,便于直接运行调试
./gradlew runIde             # 启动沙盒 IDE 实测插件(手工冒烟入口)
./gradlew test --tests "com.mypi.plugin.SelectionContextBuilderTest"   # Tier1 纯逻辑测试
```

需要 JDK 21;项目自带 Gradle Wrapper,无需预装 Gradle。

> ⚠️ **不要在 CLI 跑全量 `./gradlew test`**:Tier2 平台测试(`BasePlatformTestCase` 子类)会启动 IDEA sandbox,当前因协程库 debug metadata 冲突长时间卡死(详见 `tasks/lessons.md`)。Tier2 用 `runIde` 起真实 IDE 手工验证,或经 IDEA MCP 在真实 IDE 中执行。

### 发版流程

发版走 GitHub Actions 自动构建并发布到 Releases。打 tag 即触发:

```bash
# 1. 更新版本号:改 build.gradle.kts 里的 version 与 intellijPlatform.pluginConfiguration.version
# 2. 在 CHANGELOG.md 顶部新增 '## [x.x.x] - YYYY-MM-DD' 段,写面向用户的更新说明
# 3. 提交并打 tag
git commit -am "release: x.x.x"
git tag vx.x.x
git push origin main --tags
```

CI 会自动:构建插件 → 对 IDEA 2026.2/2026.3 跑 Plugin Verifier 二进制兼容检查(失败即中止发版) → 从 `plugin.xml` 提取描述生成 `updatePlugins.xml`(单一真相源) → 从 `CHANGELOG.md` 读取对应版本段作为 Release 说明 → 创建 GitHub Release 并上传 zip 与索引。

> ⚠️ **CHANGELOG 是发版必需项**:CI 会从 `CHANGELOG.md` 提取 `## [版本号]` 段作为 Release body,找不到对应版本段会**构建失败**。打 tag 前务必先在 CHANGELOG.md 补写该版本段。

> ⚠️ **gradlew 必须保持可执行权限**(`100755`)。Windows 上改动后若误降权,Linux CI 会 `Permission denied`。用 `git update-index --chmod=+x gradlew` 恢复。

团队成员用「方式一」安装后,发新版无需任何操作,IDE 自动检测更新。

### 项目结构

```
src/main/kotlin/com/mypi/plugin/
├── PiTerminalService.kt        # 核心:用 Reworked Terminal 跑 pi + 选区注入(单会话、焦点接力、Experimental API 降级)
├── PiMcpConfigService.kt       # 把 IDEA MCP Server 注入 Pi 的 mcp.json(反射访问、claude-code 去重、原子写入)
├── PiToolWindowFactory.kt      # 右侧 Pi 工具窗工厂(状态 Label 兜底,启动逻辑在 Service)
├── SendSelectionAction.kt      # Ctrl+Alt+; 注入动作(有选区发范围,无选区发单行定位)
├── SelectionContextBuilder.kt  # 选区 → 定位串构造(纯逻辑,零平台依赖);支持 COLON_LINE / GITHUB_ANCHOR 两种行锚点格式
├── PiSettings.kt               # 持久化设置:pi 路径 + MCP/去重/性能日志开关(PersistentStateComponent)
└── PiConfigurable.kt           # Settings → Tools → Pi 配置页(路径框 + 格式下拉框 + 3 个开关)
src/main/resources/META-INF/plugin.xml   # 插件声明(ToolWindow / Action / Configurable + 3 个 depends)
src/test/kotlin/com/mypi/plugin/         # 测试分层见下表
```

**测试分层:**

| 文件 | 层级 | 覆盖 |
|---|---|---|
| `SelectionContextBuilderTest` | Tier1 纯逻辑(JUnit5) | 定位串拼接,覆盖 COLON_LINE / GITHUB_ANCHOR 双格式及 Windows 绝对路径(15 用例) |
| `PiSettingsStateTest` | Tier1 纯逻辑(JUnit5) | `piPath` 持久化状态(4 用例) |
| `SendSelectionActionTest` | Tier2 平台(`BasePlatformTestCase`) | `update` 启用逻辑(3 用例) |
| `PiConfigurableTest` | Tier2 平台 | 配置页与 `piPath` roundtrip(3 用例) |
| `PiSettingsPlatformTest` | Tier2 平台 | PiSettings 单例与 `piPath` 读写(3 用例) |

`PiTerminalService` / `PiMcpConfigService` / `PiToolWindowFactory` 暂未覆盖;`PiSettings` 的 MCP/去重/性能开关及 `PiConfigurable` 的对应 roundtrip 也暂未覆盖(终端 API + EDT + 反射 + 文件 IO 难以在平台测试可靠复现,依赖手动验证)。

### 工作原理

插件不再自建 RPC 面板,而是**复用 IDE 内置终端**跑 pi:

1. **启动**:点 Pi 图标 → `PiTerminalService` 经 `TerminalToolWindowTabsManager` 在 Terminal 工具窗建标题 `Pi` 的 tab,以 `pi` 为 shell command 启动。
2. **注入**:`SendSelectionAction` 构造定位串 → `TerminalView.createSendTextBuilder()` 以 bracketed paste + sendEndKey 模式**只填不发送**到 pi 输入行。
3. **焦点接力**:注入后 `activateTerminal()` 激活 Terminal 工具窗,并 `requestFocusInWindow()` 到 `TerminalView.preferredFocusableComponent`(JediTerm 输入区),让键盘焦点直接落到 pi 输入行。

### 关键设计要点

- **Experimental API 降级**:Reworked Terminal 的 tab/注入 API 在 2026.2–2026.3 仍可能变化,所有调用处用 `runCatching` 包裹,失败时通过状态回调提示,绝不让插件崩溃。
- **单会话**:同一项目只持有一个 pi 终端;已开则聚焦,不重开。tab 被用户关闭时清引用并通知面板。
- **每次点图标都触发**:`PiTerminalService` 的 `init` 订阅 `ToolWindowManagerListener.toolWindowShown`(不是只首次),解决「手动退出 pi 后再点图标无法重启」。
- **线程模型**:终端 API 标注 `@RequiresEdt`,`openPi` 经 `invokeLater` 切 EDT 调用;`piTab`/`piView` 用 `@Volatile` 保证可见性。

## 已知限制

- **只填不发送**:注入定位串后需用户手动补问题 + 回车(刻意设计,保持 pi 输入简洁)
- **不收集 pi 回复**:回复直接显示在 pi 终端内,插件不在侧栏做二次展示
- **单会话**:一个项目一个 pi 终端,暂不支持多会话/历史保留
- **平台服务与 MCP 配置未完全覆盖测试**:`PiTerminalService` / `PiMcpConfigService` / `PiToolWindowFactory` 涉及终端 API + EDT + 反射 + 文件 IO,难以在平台测试可靠复现,依赖手动验证;`SelectionContextBuilder` / `SendSelectionAction.update` 与 `piPath` 配置层已覆盖,但 MCP/去重/性能开关仍未覆盖(见上「测试分层」)

## 项目状态

MVP 阶段。功能与 Pi 当前版本为准。

## 致谢

- Pi coding agent —— npm 包 `@earendil-works/pi-coding-agent`
- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.gradle.org/docs/intellij-platform)
