import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

fun loadMapsApiKey(): String {
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.exists()) {
        throw GradleException(
            "缺少 local.properties：请在项目根目录创建该文件并写入 MAPS_API_KEY=<你的 Google Maps API Key>（该文件已被 .gitignore 忽略，不会被提交）。"
        )
    }
    val props = Properties()
    propsFile.inputStream().use { props.load(it) }
    return props.getProperty("MAPS_API_KEY")?.trim().takeUnless { it.isNullOrEmpty() }
        ?: throw GradleException(
            "local.properties 中缺少 MAPS_API_KEY：请在该文件写入 MAPS_API_KEY=<你的 Google Maps API Key>。"
        )
}

android {
    namespace = "com.checkinassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.checkinassistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 35
        versionName = "2.2.13"

        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = loadMapsApiKey()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// ViewPager2 版本固定。
// androidx.recyclerview:recyclerview:1.3.2 的 Gradle Module Metadata 里带了一条
// dependencyConstraint，把 androidx.viewpager2 约束到 1.1.0-beta02；而本机只有 1.0.0
// 的缓存（--offline 构建，取不到 beta 版）。force 让版本稳定落在 1.0.0——
// 它与 recyclerview 1.3.2 搭配是常见的生产组合。
configurations.all {
    resolutionStrategy {
        force("androidx.viewpager2:viewpager2:1.0.0")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-base:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.google.android.libraries.places:places:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
