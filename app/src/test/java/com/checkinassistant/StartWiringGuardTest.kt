package com.checkinassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 问题 1 的「接线守卫」：直接读源码文字，防止以后有人不小心又加一条能绕过「手动停止」的启动路。
 * 这只是回归守卫，不能证明真机上不会被拉起——那要按真机清单验证。
 */
class StartWiringGuardTest {
    private val sources: List<File> by lazy {
        var dir: File? = File("").absoluteFile
        var root: File? = null
        while (dir != null && root == null) {
            root = listOf("app/src/main/java/com/checkinassistant", "src/main/java/com/checkinassistant")
                .map { File(dir, it) }.firstOrNull { it.isDirectory }
            dir = dir.parentFile
        }
        if (root == null) fail("找不到主源码目录，守卫测试无法运行")
        root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun filesContaining(text: String) = sources.filter { it.readText().contains(text) }.map { it.name }
    private fun count(text: String) = sources.sumOf { it.readText().split(text).size - 1 }

    @Test fun sourcesAreFound() {
        assertTrue(sources.any { it.name == "GeofenceMonitoringService.kt" })
        assertTrue(sources.any { it.name == "MainActivity.kt" })
    }

    @Test fun onlyOnePlaceClearsUserStopped() {
        assertEquals(1, count("setUserStopped(context, false)"))
        assertEquals(listOf("GeofenceMonitoringService.kt"), filesContaining("setUserStopped(context, false)"))
        // 别的写法（比如换个变量名传 false）也不许有：全工程调用 setUserStopped 的只有服务里的「启动」和「停止」两处
        assertEquals(2, count("MonitorKeeper.setUserStopped("))
    }

    @Test fun startByUserIsCalledOnlyFromHomeButton() {
        // 定义在服务里（fun startByUser(），调用只在首页
        assertEquals(listOf("MainActivity.kt"), filesContaining(".startByUser("))
        assertEquals(1, count(".startByUser("))
    }

    @Test fun serviceIsStartedOnlyThroughItsOwnGatedEntrances() {
        assertEquals(listOf("GeofenceMonitoringService.kt"), filesContaining("startForegroundService("))
    }

    /** v2.2.26：能停掉后台服务的只有服务自己（手动停止、自停）和设置页切到「始终高德」时的重建。 */
    @Test fun serviceIsStoppedOnlyByItselfOrTheRecreateHelper() {
        assertEquals(listOf("GeofenceMonitoringService.kt", "MonitorRestart.kt"), filesContaining("stopService(").sorted())
        // 重建走的是带「手动停止」闸门的普通入口
        val restart = sources.first { it.name == "MonitorRestart.kt" }.readText()
        assertTrue(restart.contains("MonitorKeeper.userStopped(app) || !GeofenceMonitoringService.isRunning) return null"))
        assertTrue(restart.contains("GeofenceMonitoringService.tryStart(app)"))
        assertEquals(listOf("SettingsFragment.kt"), filesContaining("MonitorRestart.recreateService("))
    }
}
