pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 本地构件仓（chaquopy 运行时 + material-color-utilities 5.0.1，沙箱 Gradle 无法直连 mavenCentral 拉取）。
        // 仅在本机存在该目录时注册——2026-09-30 CI 实证：Linux runner 上写死的 Windows 路径
        // 会被解析成不支持的仓库协议 "D"，直接炸掉依赖解析。
        if (java.io.File("D:/AndroidDev/maven-local").exists()) {
            maven { url = uri("D:/AndroidDev/maven-local") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Es Qp"
include(":app")
