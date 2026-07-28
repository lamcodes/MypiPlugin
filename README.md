# MypiPlugin

> 在 IntelliJ IDEA 里一键把「光标/选区位置」注入 Pi 终端,让 Pi coding agent 直接读你的代码。

MypiPlugin 是一个 IntelliJ Platform 插件,复用 IDE **内置终端**(Reworked Terminal)跑 [Pi](https://www.npmjs.com/package/@earendil-works/pi-coding-agent) coding agent。**选中代码 → `Ctrl+Alt+;` → 定位串注入到 pi 输入行(只填不发送)**,焦点自动切到 pi 终端,你补一句问题回车即可,全程不离开 IDE。

## 特性

- 🚀 **一键注入选区定位**:`Ctrl+Alt+;` 把当前选区/光标位置以 `@相对路径#L起-止行` 形式填入 pi 终端输入行(**只填不发送**,留给你补问题)
- 🖥️ **复用内置终端跑 pi**:点右侧 Pi 图标即在 IDE 终端启动 pi,无需自建面板、无需 RPC 协议;pi 跑在项目目录,作为 coding agent 能自行读取项目文件
- 🎯 **焦点自动接力**:注入成功后焦点自动落到 pi 终端输入区,直接打字补问题 + 回车,无需手动切窗口
- ♻️ **稳定重启**:手动退出 pi 后再点 Pi 图标会自动重启新会话(每次点图标都触发,不止首次)
- ⚙️ **可配置 Pi 路径**:Settings → Tools → Pi,支持自定义 pi 可执行路径,不强制依赖 PATH

## 前置要求

| 依赖 | 版本 / 说明 |
|---|---|
| IntelliJ IDEA | **2026.2**(Community 或 Ultimate) |
| JDK | 21(构建插件用) |
| Pi CLI | `npm install -g --ignore-scripts @earendil-works/pi-coding-agent` |
| LLM Provider | Pi 需配置 provider(如 `ANTHROPIC_API_KEY`)才能产生回复 |

> ⚠️ **关于 IDE 版本**:插件依赖 IntelliJ 2026.2 才稳定提供的 Reworked Terminal 新 API(`TerminalView.createSendTextBuilder` / `sendEndKeyBeforeText` 等)。低于 2026.2 的版本能装上,但首次注入会因缺 API 而崩溃,请务必使用 2026.2+。
>
> ⚠️ Pi 是独立的 coding agent CLI,本插件只负责 IDE 集成。Pi 的安装与 LLM 配置请参考 Pi 官方文档。

## 安装

当前为 MVP 阶段,从源码构建:

```bash
git clone <repo-url>
cd MypiPlugin
./gradlew buildPlugin
```

构建产物:`build/distributions/MypiPlugin-0.1.0.zip`。

导入 IDE:**IntelliJ IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk** → 选择上述 zip → 重启 IDE。

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
@src/main/java/com/x/UserService.java#L10-20   # 有选区:@相对路径#L起始行-结束行(1-based)
@src/main/java/com/x/UserService.java#L42       # 无选区:@相对路径#L光标行号
```

> **为什么只发定位不发代码?** pi 在项目目录下作为 coding agent 运行,能自行读取项目文件;插件只发定位串,保持 pi 输入行简洁,你补一句问题(如"这段为什么慢")直接回车即可。

### 4. 补问题并回车

焦点已在 pi 终端,直接输入问题 → `Enter` 发送给 pi。

## 配置项

| 配置 | 位置 | 默认 | 说明 |
|---|---|---|---|
| pi 可执行路径 | Settings → Tools → Pi | 空(用 PATH) | pi 的绝对路径;留空则从系统 PATH 查找 |

## 故障排查

**点 Pi 图标后显示「启动失败:未找到 pi」**
- 终端执行 `pi --version` 确认可用;若不行,在 Settings → Tools → Pi 显式配置 pi 绝对路径
- Windows 下配 `.cmd` 仍失败时,改配安装目录下的 `.exe`

**按 `Ctrl+Alt+;` 弹框「Pi 终端未开启」**
- 先点右侧 Pi 图标启动 pi,再注入

**快捷键无效 / 打出别的字符**
- `Ctrl+Alt+;` 是为避开中文输入法冲突刻意选的(原 `Ctrl+Alt+P` 会被 IME 拦截打 p,`Ctrl+Shift+*` 是输入法切换)。若仍冲突,可在 Settings → Keymap 里改绑 `Send Selection to Pi`

**插件安装后不生效 / 注入时报错**
- 确认 IDE 版本为 **2026.2+**(低于此版本缺 Reworked Terminal API)
- 重启 IDE

## 开发

### 构建命令

```bash
./gradlew compileKotlin      # 仅编译
./gradlew buildPlugin        # 打卡产出 zip
./gradlew prepareSandbox     # 构建并装入沙盒 IDE,便于直接运行调试
./gradlew test               # 跑全部测试(Tier1 纯逻辑 + Tier2 平台)
```

需要 JDK 21;项目自带 Gradle Wrapper,无需预装 Gradle。

### 项目结构

```
src/main/kotlin/com/mypi/plugin/
├── PiTerminalService.kt        # 核心:用 Reworked Terminal 跑 pi + 选区注入(单会话、焦点接力、Experimental API 降级)
├── PiToolWindowFactory.kt      # 右侧 Pi 工具窗工厂(状态 Label 兜底,启动逻辑在 Service)
├── SendSelectionAction.kt      # Ctrl+Alt+; 注入动作(有选区发范围,无选区发单行定位)
├── SelectionContextBuilder.kt  # 选区 → "@相对路径#L行号" 定位串构造(纯逻辑)
├── PiSettings.kt               # pi 路径持久化(PersistentStateComponent)
└── PiConfigurable.kt           # Settings → Tools → Pi 配置页
src/main/resources/META-INF/plugin.xml   # 插件声明(ToolWindow / Action / Configurable)
src/test/kotlin/com/mypi/plugin/         # Tier1 纯逻辑 + Tier2 平台测试
```

### 工作原理

插件不再自建 RPC 面板,而是**复用 IDE 内置终端**跑 pi:

1. **启动**:点 Pi 图标 → `PiTerminalService` 经 `TerminalToolWindowTabsManager` 在 Terminal 工具窗建标题 `Pi` 的 tab,以 `pi` 为 shell command 启动。
2. **注入**:`SendSelectionAction` 构造定位串 → `TerminalView.createSendTextBuilder()` 以 bracketed paste + sendEndKey 模式**只填不发送**到 pi 输入行。
3. **焦点接力**:注入后 `activateTerminal()` 激活 Terminal 工具窗,并 `requestFocusInWindow()` 到 `TerminalView.preferredFocusableComponent`(JediTerm 输入区),让键盘焦点直接落到 pi 输入行。

### 关键设计要点

- **Experimental API 降级**:Reworked Terminal 的 tab/注入 API 在 2026.2 仍为 `@ApiStatus.Experimental`,所有调用处用 `runCatching` 包裹,失败时通过状态回调提示,绝不让插件崩溃。
- **单会话**:同一项目只持有一个 pi 终端;已开则聚焦,不重开。tab 被用户关闭时清引用并通知面板。
- **每次点图标都触发**:`PiTerminalService` 的 `init` 订阅 `ToolWindowManagerListener.toolWindowShown`(不是只首次),解决「手动退出 pi 后再点图标无法重启」。
- **线程模型**:终端 API 标注 `@RequiresEdt`,`openPi` 经 `invokeLater` 切 EDT 调用;`piTab`/`piView` 用 `@Volatile` 保证可见性。

## 已知限制

- **只填不发送**:注入定位串后需用户手动补问题 + 回车(刻意设计,保持 pi 输入简洁)
- **不收集 pi 回复**:回复直接显示在 pi 终端内,插件不在侧栏做二次展示
- **单会话**:一个项目一个 pi 终端,暂不支持多会话/历史保留
- **PiTerminalService 未覆盖单元测试**:终端 API + EDT 难在平台测试里可靠复现,依赖手动验证(纯逻辑部分如 `SelectionContextBuilder` 已覆盖)

## 项目状态

MVP 阶段。功能与 Pi 当前版本为准。

## 致谢

- Pi coding agent —— npm 包 `@earendil-works/pi-coding-agent`
- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.gradle.org/docs/intellij-platform)
