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
