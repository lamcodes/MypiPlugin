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

## MypiPlugin 自动化验证禁则:`./gradlew test`(全量) 和 Tier2 平台测试禁止在 CLI 执行

**硬规则**:本项目 `./gradlew test`(全量)或任何指向 Tier2 平台测试(`BasePlatformTestCase` 子类:`SendSelectionActionTest`、`PiConfigurableTest`、`PiSettingsPlatformTest`)的 CLI 命令,都会触发 `prepareTestSandbox` → 启动 IDEA sandbox → 协程库冲突 → 287s+ 卡死。这在子智能体中必僵死(SendMessage 无响应);在主会话中也严重拖慢。

**允许的验证方式**:
- `./gradlew compileKotlin compileTestKotlin` — 编译验证(最快,秒级)
- `./gradlew test --tests "com.mypi.plugin.SelectionContextBuilderTest"` — 仅 Tier1 纯 JUnit 测试(零 IntelliJ 依赖,秒级)
- IDEA MCP `build_project` — 编译验证(Gradle 不进 sandbox)
- IDEA MCP `execute_run_configuration` 在真实 IDE 跑 Tier2 测试(需有 run configuration)
- 手动验证(启动 IDE 实例实测)

**适用场景**:
- 任何"验证一下"、"跑个测试"、"确认都通过"的请求 → 默认用编译验证,不要下意识打 `./gradlew test`
- 子智能体的 dispatch prompt → 明确写"只用编译验证,禁止 CLI 跑 Tier2"

## MypiPlugin 子智能体卡死规避:避免 prepareTestSandbox,派发时只要求编译

**错误模式**:用 `./gradlew test`(或 `--tests` 指向 `BasePlatformTestCase` 子类)跑 Tier2 平台测试时,启动的 IDEA 2026.2 sandbox 因 `kotlinx.coroutines` debug metadata 版本冲突(`Expected: 1, got 2. Please update the Kotlin standard library.`)加载失败——首个测试跑 ~287s 才 skip、后续测试不跑;更糟的是会让执行型 subagent 僵死(SendMessage 无响应,占用 sandbox 进程)。

**踩坑实例**(MypiPlugin,2026-07-28):
- 跑 `SendSelectionActionTest`(`BasePlatformTestCase`)→ sandbox 协程库与项目 Kotlin stdlib 错位 → unhandled exception 刷屏 → 测试 287s skip,task2-impl agent 僵死,controller 两次 SendMessage 无响应,被迫接管。

**归因**:这是**项目既有的环境/依赖问题**,不是被测代码的缺陷。IDEA 2026.2 内置协程库与项目编译期 Kotlin stdlib 的 debug metadata 版本(1 vs 2)对不上。README 第 150 行已声明"平台测试难可靠复现,依赖手动验证"。

**预防规则**:
- 自动化验证只用 `./gradlew compileKotlin compileTestKotlin`(编译) + Tier1 纯 JUnit 测试(零 IntelliJ 依赖,几毫秒跑完)。
- Tier2 平台测试**不在 CLI 跑**;确需覆盖就用 IDEA MCP(`mcp__idea__execute_run_configuration`)在真实 IDE 里跑,或依赖手动验证。
- 派 implementer 时,若任务触及 Tier2 测试,dispatch 里**明确禁止 CLI 跑 Tier2**,改走编译验证——否则 agent 会卡死。

**附带——杀卡死的 IDEA sandbox**:Git Bash `ps -W` 第一列是 msys2 内部 PID(拿去 taskkill 会报"找不到进程"),**第六列 WINPID 才是 Windows PID**。用 `MSYS_NO_PATHCONV=1 taskkill /F /PID <WINPID>` 杀。识别 sandbox:进程命令行含 `idea-2026.2-win`(jbr 路径)。

## Windows 项目 push 到 GitHub 后,gradlew 必须手动加执行权限

**错误模式**:Windows 上创建的 Gradle 项目(如 IntelliJ 插件),`gradlew` 文件 git mode 默认是 `100644`(不可执行)。push 到 GitHub 后,Linux CI 跑 `./gradlew` 立即 `Permission denied`,但本地 Windows 完全正常——导致"本地能跑、CI 必挂"。

**踩坑实例**(MypiPlugin,2026-07-28):
- GitHub Actions 跑 `./gradlew buildPlugin` 报 `Permission denied`。`git ls-files -s gradlew` 显示 `100644`。本地从未报错,因为 Windows 不认 Unix 执行位。

**预防规则**:
- 任何 Windows 创建、要在 Linux/macOS CI 跑的 Gradle 项目,**首次 push 前执行** `git update-index --chmod=+x gradlew && git commit -m "chmod"`。
- CI 构建失败且本地正常时,`git ls-files -s <脚本>` 看 mode:`100644`=不可执行(坑),`100755`=可执行。
- 同理适用于其他 shell 脚本(`.sh`),不止 `gradlew`。

## GitHub Actions `${{ }}` 表达式不要写进 bash heredoc,改用 env 变量

**错误模式**:在 `run: |` 的 heredoc(`<< EOF`)里直接内联 `${{ steps.xxx.outputs.yyy }}`,GitHub Actions 表达式与 heredoc 文本交互会产生意外字符注入(实测 `${{ steps.version.outputs.version }}` 后紧跟 `>` 时,生成结果多出一个 `}`)。

**踩坑实例**(MypiPlugin,2026-07-28):
- workflow 用 `cat > updatePlugins.xml << EOF ... version="${{ steps.version.outputs.version }}>" ... EOF` 生成 XML,产物里 `version="0.1.0}"` 多了花括号,IDE 无法识别插件版本。同行的 `url=".../${{ steps.xxx }}/..."` 却正常,问题只出在表达式紧跟特定字符的行。

**预防规则**:
- heredoc 里需要 GitHub Actions 变量时,**先把 `${{ }}` 赋给 `env:` 再在 heredoc 里用 bash 变量**:
  ```yaml
  - run: |
      cat > file << EOF
      val="${MY_VAR}"
      EOF
    env:
      MY_VAR: ${{ steps.x.outputs.y }}
  ```
- `${{ }}` 表达式只用在 YAML 键值、`with:`、`env:` 里,不直接写进 heredoc / 复杂 shell 字符串,避免转义与字符注入坑。

## GitHub Actions 首次引入 workflow 文件的那次 tag push 不触发该 workflow

**错误模式**:新建 workflow 文件 + 在**同一个 commit** 里打 tag 并 push,该 tag push 事件不会触发这个 workflow——因为 GitHub 还没为这个新 workflow 注册触发器,事件被静默丢弃(API 显示 `total_count: 0`,workflow 本身 `state: active`)。

**踩坑实例**(MypiPlugin,2026-07-28):
- commit 4b51638 同时新增 `.github/workflows/release.yml` 和 tag `v0.1.0`,push 后 Actions 零触发。workflow 列表 `active` 但 runs `0`。

**预防规则**:
- 新 workflow 首次引入后,**先把含 workflow 文件的 commit 单独 push,再打 tag push**;或删 tag 重新打(`git tag -d` → `git push origin :refs/tags` → 重打重推),第二次 push 时 workflow 已注册,能正常触发。
- CI 零触发排查顺序:① `actions/workflows` 看 `state: active`(排除语法错/被禁) → ② `actions/runs` 看 `total_count`(看有没有事件到达) → ③ 若 active 但 runs=0,优先怀疑"首次引入未注册"或 tag/分支名不匹配 `on:` 触发器。

## 插件描述单一真相源 + CHANGELOG 是发版硬依赖(MypiPlugin 发版文档治理)

**错误模式**:插件描述在多处重复维护(`plugin.xml` 一份、`release.yml` 里 `updatePlugins.xml` 硬编码一份),改行为时只改一处 → 漂移。实测 v0.2.0 发版时三处描述全不一致:实际行为 `@path:行号`、`plugin.xml` 写 "默认 path:行号"(缺 `@`)、`release.yml` 还写 "@相对路径#L行号"(旧版)。根因是"不该有两处"而非"忘了改两处"。

**踩坑实例**(MypiPlugin,2026-08-06):
- v0.2.0 加 `@` 前缀(hotfix)漏改 `plugin.xml` description;Task 6 改描述漏改 `release.yml:59` 硬编码。
- `generate_release_notes: true` 对 main 直推(无 PR)项目几乎无效,Release body 只有一行 compare 链接,团队成员看不出更新内容。

**根治措施**(已在 release.yml 固化,2026-08-06):
- `release.yml` 用 sed 从 `plugin.xml` 提取 CDATA description 填进 `updatePlugins.xml`,**plugin.xml 成为唯一描述真相源**。
- `release.yml` 用 awk 从 `CHANGELOG.md` 按 `## [版本号]` 提取对应段作为 Release body;**段缺失则 `exit 1` fail fast**,强制发版前写 CHANGELOG。
- 本地验证过的提取命令(发版改造时可复用):
  - `sed -n '/<description><!\[CDATA\[/,/]]><\/description>/{//!p;}' src/main/resources/META-INF/plugin.xml`
  - `awk -v v="$VERSION" '/^## \[/{p=index($0,"["v"]")} p&&/^## \[/&&!index($0,"["v"]"){p=0} p' CHANGELOG.md`

**预防规则**:
- 改插件描述**只改 `plugin.xml` 一处**,禁止在 `release.yml` 重复硬编码 description。
- 发版前**必须**在 `CHANGELOG.md` 顶部补 `## [版本号] - YYYY-MM-DD` 段,否则 CI fail fast。发版流程见 README「发版流程」。
- `generate_release_notes: true` 只对 PR-driven 工作流有效;main 直推项目靠 CHANGELOG 提取,别指望它生成可用 notes。

