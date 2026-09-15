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
        // 本地构件仓（chaquopy 运行时 + material-color-utilities 5.0.1，沙箱 Gradle 无法直连 mavenCentral 拉取）
        maven { url = uri("D:/AndroidDev/maven-local") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Es Qp"
include(":app")
