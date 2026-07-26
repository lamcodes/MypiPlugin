// MypiPlugin 构建脚本 —— 基于 IntelliJ Platform Gradle Plugin 2.x
// 关键:plugin id 是 org.jetbrains.intellij.platform(非旧版 org.jetbrains.intellij)
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    // 与 kotlin.jvm 同版本;为后续 @Serializable 预埋,DOM 解析本身不强依赖
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
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

// 声明目标 IDE:MVP 用 IntelliJ IDEA Community 2024.2,无需额外 bundled plugin
// (不引 com.intellij.java,避开 2025.3 起 intellijIdeaCommunity 废弃坑)
dependencies {
    intellijPlatform {
        // 显式 Community(IC);intellijIdea() 默认拉 Ultimate(IU) 会要求授权/注册
        intellijIdeaCommunity("2024.2")
    }
    // JSON 解析迁移到 kotlinx.serialization(替代平台 bundle 的 Gson)。
    // 注意:implementation 必须放在顶层 dependencies 块(与 intellijPlatform{} 同级),
    // 不能嵌进 intellijPlatform{} 内部,否则插件不会把它打进 classpath。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

intellijPlatform {
    pluginConfiguration {
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "242"   // 2024.2
            untilBuild = "252.*"  // 覆盖到 2025.2
        }
    }
}

// 用本机 JDK 21 工具链编译(IntelliJ 2024.2 运行在 JBR 21,兼容)
kotlin {
    jvmToolchain(21)
}
