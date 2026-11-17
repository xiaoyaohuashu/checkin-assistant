package com.checkinassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v2.2.25 守卫：读源码文字，保证高德代码关在 map/amap、location/amap 两个包里，
 * 谷歌路径（提醒核心、后台服务、App 启动）碰不到高德。只是回归守卫，字节码层面的证明见详细报告。
 */
class AmapIsolationGuardTest {
    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            found = listOf("app/src/main/java/com/checkinassistant", "src/main/java/com/checkinassistant")
                .map { File(dir, it) }.firstOrNull { it.isDirectory }
            dir = dir.parentFile
        }
        found ?: fail("找不到主源码目录") as Nothing
    }
    private val sources by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    private fun rel(f: File) = f.relativeTo(root).path.replace(File.separatorChar, '/')
    private fun filesContaining(text: String) = sources.filter { it.readText().contains(text) }.map(::rel).sorted()

    @Test fun amapSdkOnlyInsideAmapPackages() {
        val offenders = filesContaining("import com.amap.").filterNot { it.startsWith("map/amap/") || it.startsWith("location/amap/") }
        assertEquals(emptyList<String>(), offenders)
        assertTrue(filesContaining("import com.amap.").isNotEmpty())
    }

    @Test fun privacyCallsOnlyInAmapSdk() {
        assertEquals(listOf("map/amap/AmapSdk.kt"), filesContaining("updatePrivacyAgree("))
        assertEquals(listOf("map/amap/AmapSdk.kt"), filesContaining("updatePrivacyShow("))
    }

    /** v2.2.26（中 6）：撤回同意时三套高德 SDK 都要告知，且只在 AmapSdk 里做。 */
    @Test fun revokeTellsAllThreeSdks() {
        val sdk = File(root, "map/amap/AmapSdk.kt").readText()
        assertEquals(3, sdk.split("updatePrivacyAgree(app, false)").size - 1)
        assertEquals(listOf("map/AmapPrivacyPrompt.kt"), filesContaining("AmapSdk.revoke("))
        // 撤回的入口先把状态改成「不同意」，再通知 SDK
        val prompt = File(root, "map/AmapPrivacyPrompt.kt").readText()
        assertTrue(prompt.indexOf("MapSettings.setPrivacy(activity, AmapPrivacy.DECLINED)\n                AmapSdk.revoke(activity)") > 0)
    }

    @Test fun googleCorePathsNeverMentionAmap() {
        val core = sources.filter { rel(it).startsWith("reminder/") || rel(it).startsWith("system/") } +
            sources.filter { it.name in setOf("CheckInApp.kt", "GeofenceMonitoringService.kt", "GoogleLocationProvider.kt", "GoogleMapPickerView.kt", "GooglePlaceSearch.kt") }
        core.forEach { f -> assertTrue(rel(f), !f.readText().contains("amap", ignoreCase = true)) }
    }

    /** v2.2.26（中 4）：高德定位客户端只在 AmapLocator 里创建（复用同一个）。 */
    @Test fun amapLocationClientIsCreatedOnlyInTheLocator() {
        assertEquals(listOf("location/amap/AmapLocator.kt"), filesContaining("AMapLocationClient("))
    }

    @Test fun singleCheckpoints() {
        assertEquals(listOf("location/PlayServices.kt"), filesContaining("GoogleApiAvailability"))
        assertEquals(listOf("map/MapServiceChooser.kt"), filesContaining("getLastKnownLocation("))
        // 没有 Google Play 服务时才会构造高德/替代定位
        val providers = File(root, "location/LocationProvider.kt").readText()
        // v2.2.26：定位来源不可能是高德时（有 Google Play 服务、设置不是始终高德）直接用谷歌定位对象
        assertTrue(providers.contains("if (LocationSourceRules.needsDynamic(MapSettings.setting(context), PlayServices.isInstalled(context))) DynamicLocationProvider(context)"))
        assertTrue(providers.contains("else GoogleLocationProvider(context)"))
    }
}
