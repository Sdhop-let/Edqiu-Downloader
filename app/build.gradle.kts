import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// ---- 网盘直连备份：应用资质（gradle.properties 注入，开发期可留空，运行时代码提示未配置） ----
val baiduClientId: String = (project.findProperty("BAIDU_CLIENT_ID") as? String).orEmpty()
val baiduClientSecret: String = (project.findProperty("BAIDU_CLIENT_SECRET") as? String).orEmpty()
val aliClientId: String = (project.findProperty("ALI_CLIENT_ID") as? String).orEmpty()
val aliClientSecret: String = (project.findProperty("ALI_CLIENT_SECRET") as? String).orEmpty()
val pan123ClientId: String = (project.findProperty("PAN123_CLIENT_ID") as? String).orEmpty()
val pan123ClientSecret: String = (project.findProperty("PAN123_CLIENT_SECRET") as? String).orEmpty()
val pan123RedirectUri: String = (project.findProperty("PAN123_REDIRECT_URI") as? String).orEmpty()

private fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// ---- 1.4.0 起：release 签名（keystore.properties 本地私有，已被 .gitignore 忽略） ----
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let {
    val f = File(it)
    if (f.isAbsolute) f else project.file(it)
}
val releaseStorePassword = keystoreProps.getProperty("storePassword")
val releaseKeyAlias = keystoreProps.getProperty("keyAlias")
val releaseKeyPassword = keystoreProps.getProperty("keyPassword")

android {
    namespace = "com.ed.edqiu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ed.Edqiu"
        minSdk = 24
        targetSdk = 35
        versionCode = 36
        versionName = "1.5.1"

        // 2026-09-15：只保留 arm64-v8a（Android 16/17 手机端均为 arm64），APK 从 225.9MB 降至约 80MB；
        // ffmpeg/yt-dlp 功能完整保留，x86 系模拟器不再支持
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 网盘直连备份：应用资质占位（百度/阿里/123 client_id、client_secret）
        buildConfigField("String", "BAIDU_CLIENT_ID", baiduClientId.asBuildConfigString())
        buildConfigField("String", "BAIDU_CLIENT_SECRET", baiduClientSecret.asBuildConfigString())
        buildConfigField("String", "ALI_CLIENT_ID", aliClientId.asBuildConfigString())
        buildConfigField("String", "ALI_CLIENT_SECRET", aliClientSecret.asBuildConfigString())
        buildConfigField("String", "PAN123_CLIENT_ID", pan123ClientId.asBuildConfigString())
        buildConfigField("String", "PAN123_CLIENT_SECRET", pan123ClientSecret.asBuildConfigString())
        buildConfigField("String", "PAN123_REDIRECT_URI", pan123RedirectUri.asBuildConfigString())
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null && releaseStorePassword != null &&
                releaseKeyAlias != null && releaseKeyPassword != null
            ) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // 视频帧解码：AsyncImage 直接加载本地视频文件时显示首帧缩略图（同步情况页预览用）
    implementation("io.coil-kt:coil-video:2.7.0")

    // ---- 网盘直连备份（T01）：百度/阿里 HTTP 与凭证加密 ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.sqlite:sqlite-framework:2.4.0")
}
