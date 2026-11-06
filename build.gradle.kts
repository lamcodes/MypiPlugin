// MypiPlugin 构建脚本 —— 基于 IntelliJ Platform Gradle Plugin 2.x
// 关键:plugin id 是 org.jetbrains.intellij.platform(非旧版 org.jetbrains.intellij)
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    // 与 kotlin.jvm 同版本;IntelliJ 2026.2 的平台类用 Kotlin 2.4.0 编译(metadata 2.4.0),
    // 旧编译器(1.9)只能读到 2.0.0,故必须升级到 >=2.4.0 才能读平台类
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

// 版本与兼容范围统一来自 gradle.properties,这里只做一次解析,
// 保证构建产物、Verifier 双端检查、发版索引三处不会各写一份而漂移。
val pluginVersion = providers.gradleProperty("pluginVersion").get()
val platformVersion = providers.gradleProperty("platformVersion").get()
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").get()
val pluginUntilBuild = providers.gradleProperty("pluginUntilBuild").get()
val verifierIdeVersions = providers.gradleProperty("verifierIdeVersions").get()
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)

group = "com.mypi"
version = pluginVersion

// IntelliJ Platform 专用仓库(放行 IntelliJ 自有依赖与 bundled plugin 元数据)
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 2025.3 起 IC 不再单独发布,统一用 intellijIdea()(平台建议);只用 platform + terminal API,与 IC 兼容
        // 以最低支持版本为编译目标(官方建议:面向最老支持版本编译,避免误用更新平台 API);
        // 2026.3(263) 的二进制兼容由 pluginVerification 的 verifyPlugin 双端检查覆盖。
        intellijIdea(platformVersion)
        // 依赖内置终端插件(Reworked Terminal 在此,提供 TerminalView 等)
        bundledPlugin("org.jetbrains.plugins.terminal")
        // 依赖 MCP Server 插件(2025.2+ 内置,提供 IDE 工具给 Pi 调用)
        // 通过反射访问,optional 依赖,旧版 IDEA 无此插件也能编译运行
        bundledPlugin("com.intellij.mcpServer")
        // Plugin Verifier CLI:verifyPlugin 任务用,做 IDE 二进制兼容检查
        pluginVerifier()
        // 测试基础设施:IntelliJ Platform 测试框架(提供 BasePlatformTestCase 等)
        testFramework(TestFrameworkType.Platform)
    }
    // JSON 解析迁移到 kotlinx.serialization(替代平台 bundle 的 Gson)。
    // 注意:implementation 必须放在顶层 dependencies 块(与 intellijPlatform{} 同级),
    // 不能嵌进 intellijPlatform{} 内部,否则插件不会把它打进 classpath。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // —— 测试依赖(顶层,不嵌进 intellijPlatform{}) ——
    // JUnit 5 jupiter:纯逻辑测试(Tier1)用
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // JUnit Platform launcher:useJUnitPlatform() 通常由 Gradle 自动注入,但 IntelliJ Platform 测试框架会改写
    // test 任务配置,显式补齐以防 "Failed to load JUnit Platform"
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    // vintage engine:让 JUnit3 风格的 BasePlatformTestCase(Tier2)在 JUnit Platform 下运行
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    // IJPL-157292 坑:显式补 opentest4j,避免平台测试运行期 NoClassDefFoundError
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    // IJPL-159134 坑:BasePlatformTestCase 继承 junit.framework.TestCase,需 junit4 在编译期可见(非仅运行期)
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion
        ideaVersion {
            // 代码硬依赖 2026.2 才稳定提供的 Reworked Terminal 新 API(PiTerminalService 里
            // createSendTextBuilder / sendEndKeyBeforeText / TerminalToolWindowTabsManager 等 10 处调用);
            // 低于 262 能装上但首次注入即 NoSuchMethodError,故从 262 起支持,上限见 gradle.properties。
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
        }
    }
    // 二进制兼容双端验证:对声明的首尾两端(最低支持版 + 最新支持版)各跑一次 Plugin Verifier,
    // 捕获实验性终端 API 跨版本移除/签名变化(0.1.0 时代 253→262 就栽过 NoSuchMethodError)。
    pluginVerification {
        ides {
            // 2.18.1 的 DSL 是 create(type, version)(非旧文档的 ide(...))
            verifierIdeVersions.forEach { create(IntelliJPlatformType.IntellijIdeaUltimate, it) }
        }
        // 插件名 MypiPlugin 含 "Plugin" 触发 Marketplace 命名模板规则;本插件经自定义仓库分发,
        // 名称是既有产品标识,故消音该规则而不是改名(Verifier 官方 mute 开关)
        freeArgs = listOf("-mute", "TemplateWordInPluginName")
    }
}

// 用本机 JDK 21 工具链编译:字节码 65 在 2026.2/2026.3 的 JBR 25 上可正常加载(向下兼容);
// 本机未装独立 JDK 25,升工具链需先确认 Kotlin 2.4.0 对 JVM 25 target 的支持,暂维持 21。
kotlin {
    jvmToolchain(21)
}

// 测试统一走 JUnit Platform(jupiter + vintage engine 共存,既跑 @Test 也跑 BasePlatformTestCase)
tasks.test {
    useJUnitPlatform()
}

// buildSearchableOptions 在中文 Locale 下失败("Locale must be default"),且只影响 Settings 搜索索引(非插件功能);
// 禁用后 IDE 双击 buildPlugin 或命令行 buildPlugin 都不用再加 -x buildSearchableOptions。
tasks.matching { it.name == "buildSearchableOptions" }.configureEach {
    enabled = false
}
