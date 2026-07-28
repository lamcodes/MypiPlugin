# 经验教训

## JVM / IntelliJ 配置坐标必须查证官方,严禁凭记忆

**错误模式**:写依赖坐标或平台配置时凭记忆,导致"Could not find"类错误被误判成网络问题。

**两次具体踩坑**(MypiPlugin 项目):
1. Gson groupId 是 `com.google.code.gson`(带 `code`),不是 `com.google.gson`。写错 → Maven Central 404 → Gradle 报 "Could not find com.google.gson:gson"。
2. IntelliJ Platform Gradle Plugin 2.x:`intellijIdea(version)` 默认拉 **Ultimate(IU)**(要授权),要 Community 必须用 `intellijIdeaCommunity(version)`(对 2024.2 这种 < 2025.3 的版本)。

**归因规则**(对齐 AGENTS.md 执行报错归因规范):
- 遇到 "Could not find <坐标>",**先 `curl` 那个具体 URL** 看 HTTP code:
  - `404` = 坐标/版本写错(依赖错误)
  - 连不上/超时 = 网络(DNS/连接错误)
- 不要跳过这步直接归因"国内网络不稳"。本项目实测 Maven Central 首页 200(0.33s),完全可访问。

**预防规则**:写 JVM 依赖坐标、IntelliJ Platform type、Gradle 版本下限前,查官方文档或 curl 验证 URL,不凭记忆。

## Tier2 平台测试别在 CLI 跑(IDEA sandbox 协程库冲突会卡死)

**错误模式**:用 `./gradlew test`(或 `--tests` 指向 `BasePlatformTestCase` 子类)跑 Tier2 平台测试时,启动的 IDEA 2026.2 sandbox 因 `kotlinx.coroutines` debug metadata 版本冲突(`Expected: 1, got 2. Please update the Kotlin standard library.`)加载失败——首个测试跑 ~287s 才 skip、后续测试不跑;更糟的是会让执行型 subagent 僵死(SendMessage 无响应,占用 sandbox 进程)。

**踩坑实例**(MypiPlugin,2026-07-28):
- 跑 `SendSelectionActionTest`(`BasePlatformTestCase`)→ sandbox 协程库与项目 Kotlin stdlib 错位 → unhandled exception 刷屏 → 测试 287s skip,task2-impl agent 僵死,controller 两次 SendMessage 无响应,被迫接管。

**归因**:这是**项目既有的环境/依赖问题**,不是被测代码的缺陷。IDEA 2026.2 内置协程库与项目编译期 Kotlin stdlib 的 debug metadata 版本(1 vs 2)对不上。README 第 150 行已声明"平台测试难可靠复现,依赖手动验证"。

**预防规则**:
- 自动化验证只用 `./gradlew compileKotlin compileTestKotlin`(编译) + Tier1 纯 JUnit 测试(零 IntelliJ 依赖,几毫秒跑完)。
- Tier2 平台测试**不在 CLI 跑**;确需覆盖就用 IDEA MCP(`mcp__idea__execute_run_configuration`)在真实 IDE 里跑,或依赖手动验证。
- 派 implementer 时,若任务触及 Tier2 测试,dispatch 里**明确禁止 CLI 跑 Tier2**,改走编译验证——否则 agent 会卡死。

**附带——杀卡死的 IDEA sandbox**:Git Bash `ps -W` 第一列是 msys2 内部 PID(拿去 taskkill 会报"找不到进程"),**第六列 WINPID 才是 Windows PID**。用 `MSYS_NO_PATHCONV=1 taskkill /F /PID <WINPID>` 杀。识别 sandbox:进程命令行含 `idea-2026.2-win`(jbr 路径)。
