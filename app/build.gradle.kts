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

/** local.properties 里的可选项；文件或键不存在时返回空字符串。 */
fun loadOptionalLocalProperty(key: String): String {
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.exists()) return ""
    val props = Properties()
    propsFile.inputStream().use { props.load(it) }
    return props.getProperty(key)?.trim().orEmpty()
}

android {
    namespace = "com.checkinassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.checkinassistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 52
        versionName = "2.2.29"
        // v2.2.27：原生库（高德地图带来的）只保留 64 位 ARM。近几年的手机都是这种；
        // 只支持 32 位的旧手机和电脑上的 x86 模拟器装不了这个包。
        ndk { abiFilters += listOf("arm64-v8a") }

        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = loadMapsApiKey()
        // v2.2.25 高德 Key：可选，放 local.properties 的 AMAP_API_KEY（不进仓库）。没填也能编译，运行时退回谷歌。
        manifestPlaceholders["AMAP_API_KEY"] = loadOptionalLocalProperty("AMAP_API_KEY")
        // v2.2.16 应用内更新：version.json 地址放 local.properties（不进公开仓库），没配就关闭更新功能
        resValue("string", "update_manifest_url", loadOptionalLocalProperty("UPDATE_URL"))
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
    // v2.2.28：到达提醒全屏页按比例布局（本机缓存里已有 2.2.1，--offline 可用）
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-base:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.google.android.libraries.places:places:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    // v2.2.25：高德地图 + 定位 + 搜索合包（固定版本；三个单独的包放一起有重复类，编译不过）
    implementation("com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // 单元测试（只在电脑上跑，不进 APK）
    testImplementation("junit:junit:4.13.2")
    // v2.2.25：单测里的 org.json 用真实实现（Android 自带的那份在电脑上只是空壳，一调用就抛异常）
    testImplementation("org.json:json:20231013")
}
