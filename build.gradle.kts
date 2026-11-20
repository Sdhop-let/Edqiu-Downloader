plugins {
    // 2026-11-20 构建链升级：AGP 9.3.2→9.5.1（最低 Gradle 9.6.0，最高支持 API 37）、
    // Kotlin 插件 2.4.10→2.4.21（2.4 系列最新 bugfix，KSP 2.3.12 与其解耦保持不动）
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
