# MypiPlugin

> 把 IntelliJ IDEA 的代码选区一键发给 Pi coding agent,在侧边栏流式查看回复。

MypiPlugin 是一个 IntelliJ Platform 插件,通过 JSONL 子进程协议与 Pi CLI 双向通信。**选中代码 → 快捷键 → 作为上下文发送给 Pi**,在右侧工具窗实时查看流式回复,全程无需离开 IDE。

## 特性

- 🚀 **一键发送选区**:`Ctrl+Alt+P` 把当前选中的代码(带文件名、行号、语言围栏)作为上下文发给 Pi
- 💬 **流式回复面板**:右侧 Pi 工具窗实时显示回复文本,自动滚动到底部
- ⚙️ **可配置 Pi 路径**:Settings → Tools → Pi,支持自定义 pi 可执行路径,不强制依赖 PATH
- ♻️ **进程生命周期管理**:Project 级复用 pi 进程;崩溃后下次发送自动重启;项目关闭自动回收(无孤儿进程)
- 🛡️ **健壮的错误提示**:启动失败、发送失败、Pi 拒绝请求、进程异常断开等均会在面板显式提示

## 前置要求

| 依赖 | 版本 / 说明 |
|---|---|
| IntelliJ IDEA | Community 或 Ultimate,**2024.2 ~ 2025.2** |
| JDK | 21(构建插件用) |
| Pi CLI | `npm install -g --ignore-scripts @earendil-works/pi-coding-agent` |
| LLM Provider | Pi 需配置 provider(如 `ANTHROPIC_API_KEY`)才能产生回复 |

> ⚠️ Pi 是独立的 coding agent CLI,本插件只负责 IDE 集成。Pi 的安装与 LLM 配置请参考 Pi 官方文档。

## 安装

### 从源码构建(当前 MVP 阶段)

```bash
git clone <repo-url>
cd MypiPlugin
./gradlew buildPlugin
```

构建产物:`build/distributions/MypiPlugin-0.1.0.zip`。

导入 IDE:**IntelliJ IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk** → 选择上述 zip → 重启 IDE。

## 使用

### 1. 配置 Pi 路径(可选)

若 `pi` 已在系统 `PATH` 中,可跳过此步。否则:

**Settings → Tools → Pi** → 填入 pi 可执行文件的绝对路径 → **Apply**。

> **Windows 用户**:若配置的是 `.cmd` / `.bat` 脚本(如 `pi.cmd`),插件会自动经 `cmd /c` 代理执行;`.exe` 路径则直接执行。留空则从 `PATH` 查找(Windows 下走 `cmd /c pi`)。

### 2. 发送选区

1. 在编辑器中**选中一段代码**
2. 按 **`Ctrl+Alt+P`**(macOS: `⌃⌥P`),或右键菜单 → **Send Selection to Pi**
3. 右侧 **Pi** 工具窗自动打开,流式显示回复

发送的 prompt 形如:

````
用户在 IDE 中选中了以下代码(UserService.java:42-58):
```java
public User findById(Long id) {
    ...
}
```
````

(选区超过 200 行会自动截断并标注,避免撑爆上下文。)

### 3. 查看回复

- 回复文本实时追加到 Pi 面板,**自动滚动**到底部
- Pi 进程异常断开时面板提示「pi 已断开(下次发送将自动重启)」
- 启动 / 发送错误以 `[错误]` 前缀显示

## 配置项

| 配置 | 位置 | 默认 | 说明 |
|---|---|---|---|
| pi 可执行路径 | Settings → Tools → Pi | 空(用 PATH) | pi 的绝对路径;留空则从系统 PATH 查找 |

## 故障排查

**Pi 启动失败 / 面板显示「pi 启动失败」**
- 在终端执行 `pi --version` 确认可用;若不行,在 Settings → Tools → Pi 显式配置 pi 路径
- Windows 下配置 `.cmd` 仍失败时,改配安装目录下的 `.exe` 或主可执行文件

**发送后无回复 / 只看到自己发的上下文**
- 多半是 Pi 未配置 LLM provider。先在终端单独跑 `pi` 验证能否产生回复
- 握手成功但无文本流,通常是 provider 缺失(API key 未设)

**插件安装后不生效**
- 确认 IDE 版本在 2024.2 ~ 2025.2 之间
- 重启 IDE

## 开发

### 构建命令

```bash
./gradlew compileKotlin      # 仅编译
./gradlew buildPlugin        # 打卡产出 zip
./gradlew prepareSandbox     # 构建并装入沙盒 IDE,便于直接运行调试
```

需要 JDK 21;项目自带 Gradle Wrapper,无需预装 Gradle。

### 项目结构

```
src/main/kotlin/com/mypi/plugin/
├── PiRpcProcess.kt            # JSONL RPC 子进程管理(读写循环、世代号崩溃恢复、错误去重)
├── PiService.kt               # Project 级服务,持有进程,Disposable 生命周期
├── PiToolWindowFactory.kt     # 右侧 Pi 面板(流式追加、自动滚动、断开提示)
├── SendSelectionAction.kt     # Ctrl+Alt+P 选区发送动作
├── SelectionContextBuilder.kt # 选区 → prompt 构造(GitHub 式定位 + 语言围栏 + 超长截断)
├── PiSettings.kt              # pi 路径持久化(PersistentStateComponent)
└── PiConfigurable.kt          # Settings → Tools → Pi 配置页
src/main/resources/META-INF/plugin.xml   # 插件声明(ToolWindow / Action / Configurable)
pi-probe.mjs                   # Pi RPC 协议探查脚本(开发工具,生成测试夹具)
```

### 通信协议

插件通过 `pi --mode rpc --no-session` 拉起子进程,以 JSONL 双向通信:

- **请求帧**:`{ "id": "...", "type": "prompt", "message": "..." }` + `\n`
- **事件流**:
  - `message_update`(带 `assistantMessageEvent.type == "text_delta"`)→ 文本增量
  - `response`(带 `success` / `error`)→ 请求应答状态,失败时透出 error
  - `agent_end` / `agent_settled` → 一轮回答结束

JSONL **严格按 `\n` 切分**(不使用 `BufferedReader.readLine()`,避免它把 U+2028 / U+2029 也当换行,破坏 JSON 字符串)。详见 `pi-probe.mjs` 与 `PiRpcProcess.readLoop`。

### 关键设计要点

- **线程模型**:所有进程 IO 在 `Dispatchers.IO` 后台协程,绝不阻塞 EDT;UI 更新经 `invokeLater` 切回 EDT
- **崩溃恢复**:子进程退出时复位状态,下次 `sendPrompt` 自动重启;用「世代号」根治 stop→start 极快重启时的竞态,避免误清新进程引用
- **错误去重**:进程级错误(start 失败 / 崩溃 / stdin 写失败)在本生命周期内只上报一次;应用级错误(Pi 拒绝请求)每次独立上报

## 已知限制

- 面板为**只读**(MVP),暂不能在面板内直接输入;只能通过选中代码发送
- 未在真实 Pi + LLM 环境做端到端实测(协议解析基于 `pi-probe.mjs` 抓包夹具与静态推演)
- 暂不支持多会话 / 历史保留,重启后面板清空

## 项目状态

MVP 阶段。功能与协议契约以 Pi 当前版本为准。

## 致谢

- Pi coding agent —— npm 包 `@earendil-works/pi-coding-agent`
- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.gradle.org/docs/intellij-platform)
