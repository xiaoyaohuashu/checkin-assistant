package com.checkinassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v2.2.27 界面调整的守卫（读源码文字）：清空调试日志只在调试日志页、设置页不再有「版本信息」行、
 * 保留天数的对话框和取值范围没变。只是回归守卫，界面长什么样要按真机清单看。
 */
class UiMovesGuardTest {
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
    private val settings by lazy { File(root, "SettingsFragment.kt").readText() }
    private val debugLog by lazy { File(root, "DebugLogActivity.kt").readText() }

    @Test fun clearLogsLivesOnlyOnTheDebugLogPage() {
        assertFalse(settings.contains("清空调试日志"))
        assertFalse(settings.contains("confirmClearLogs"))
        assertEquals(2, debugLog.split("清空调试日志").size - 1 - debugLog.split("「清空调试日志」").size + 1) // 按钮文字 + 对话框标题
        assertTrue(debugLog.contains("将删除全部诊断记录，且不可恢复。确定继续？"))
        assertTrue(debugLog.contains("调试日志已清空"))
        // 全工程只有这一处整表清调试日志的入口（备份导入走的是另一个 DAO）
        val callers = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("deleteByStatusBefore(RecordStatus.DEBUG.name, Long.MAX_VALUE)") }.map { it.name }.toList()
        assertEquals(listOf("DebugLogActivity.kt"), callers)
    }

    @Test fun settingsNoLongerHasAVersionRow_footerStillShowsVersion() {
        assertFalse(settings.contains("\"版本信息\""))
        assertTrue(settings.contains("\"别错过站 · v\${appVersionName()}\""))
    }

    @Test fun retentionDialogIsUnchanged() {
        assertFalse(debugLog.contains("retentionButton"))
        assertTrue(debugLog.contains("（点击修改）"))
        assertTrue(debugLog.contains("超过此天数的调试日志会自动删除（1–3650 天）。"))
        assertTrue(debugLog.contains("days !in 1..3650"))
        assertTrue(File(root, "data/DiagnosticRecordLogger.kt").readText().contains("const val DEFAULT_RETENTION_DAYS = 30"))
    }
}
