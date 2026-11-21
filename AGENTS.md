# MypiPlugin 项目指令

本文件补充项目级硬约束与文档路由，与用户级 `AGENTS.md` 协同生效。

## 硬约束

- **禁止 CLI 跑全量 `./gradlew test` 或 Tier2 平台测试**：`BasePlatformTestCase` 子类（`SendSelectionActionTest`、`PiConfigurableTest`、`PiSettingsPlatformTest`）会触发 IDEA 2026.2 sandbox 协程 metadata 冲突并长时间卡死；CLI 仅允许编译验证与 Tier1 纯逻辑单测。
- **单一真相源（SSOT）**：插件版本、编译目标 `platformVersion` 与兼容范围仅维护在 `gradle.properties`；插件展示描述仅维护在 `src/main/resources/META-INF/plugin.xml`；版本更新说明维护在 `CHANGELOG.md`（CI 发版强制校验对应段存在）。
- **保持工具链版本**：`gradle.properties` / `build.gradle.kts` 使用 JDK 21 与 Kotlin 2.4.0，不可降级 Kotlin 编译器（IDEA 2026.2 平台类为 Kotlin 2.4.0 metadata）；`gradlew` 在 Git 中须保持 `100755` 可执行位。

## 常用验证命令

```bash
./gradlew compileKotlin compileTestKotlin
./gradlew test --tests "com.mypi.plugin.SelectionContextBuilderTest" --tests "com.mypi.plugin.PiSettingsStateTest" --tests "com.mypi.plugin.PiMcpConfigStoreTest" --tests "com.mypi.plugin.PiExecutableResolverTest"
```

## 文档路由

- 知识地图：[docs/README.md](docs/README.md)
- 术语定义：[docs/领域词汇表.md](docs/领域词汇表.md)
- 架构与线程/并发边界：[docs/架构说明.md](docs/架构说明.md)
- 构建、发版与排障：[docs/开发与发布指南.md](docs/开发与发布指南.md)
