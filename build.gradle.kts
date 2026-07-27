// MypiPlugin 构建脚本 —— 基于 IntelliJ Platform Gradle Plugin 2.x
// 关键:plugin id 是 org.jetbrains.intellij.platform(非旧版 org.jetbrains.intellij)
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    // 与 kotlin.jvm 同版本;IntelliJ 2026.2 的平台类用 Kotlin 2.4.0 编译(metadata 2.4.0),
    // 旧编译器(1.9)只能读到 2.0.0,故必须升级到 >=2.4.0 才能读平台类
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.mypi"
version = "0.1.0"

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
        // 升到 2026.2:Reworked Terminal 的 createSendTextBuilder / sendEndKeyBeforeText 需此版本
        intellijIdea("2026.2")
        // 依赖内置终端插件(Reworked Terminal 在此,提供 TerminalView 等)
        bundledPlugin("org.jetbrains.plugins.terminal")
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
    // 测试构造 JSON(DOM)复用同一版本
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

intellijPlatform {
    pluginConfiguration {
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "253"    // 2025.3 起:跳过终端引擎断代期(Classic→Reworked),用 Reworked Terminal 新 API
            untilBuild = "262.*"  // 覆盖到 2026.2
        }
    }
}

// 用本机 JDK 21 工具链编译(IntelliJ 2024.2 运行在 JBR 21,兼容)
kotlin {
    jvmToolchain(21)
}

// 测试统一走 JUnit Platform(jupiter + vintage engine 共存,既跑 @Test 也跑 BasePlatformTestCase)
tasks.test {
    useJUnitPlatform()
}
