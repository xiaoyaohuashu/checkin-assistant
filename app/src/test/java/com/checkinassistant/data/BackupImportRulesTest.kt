package com.checkinassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupImportRulesTest {
    private fun p(id: Long, state: PointState, enteredAt: Long? = 5L) =
        CheckInPoint(id = id, name = "p$id", latitude = 1.0, longitude = 1.0, triggerState = state.name, enteredAt = enteredAt)

    @Test fun transientStatesGoBackToArmed_othersKept() {
        val data = BackupFixtures.data().copy(points = PointState.values().mapIndexed { i, s -> p(i + 1L, s) })
        val out = BackupImportRules.normalizeForImport(data)
        out.points.forEach { pt ->
            val original = PointState.valueOf(data.points.first { it.id == pt.id }.triggerState)
            if (original in setOf(PointState.ENTERED, PointState.DWELL_CONFIRMING, PointState.REMINDING, PointState.SNOOZED)) {
                assertEquals(PointState.ARMED.name, pt.triggerState); assertNull(pt.enteredAt)
            } else {
                assertEquals(original.name, pt.triggerState); assertEquals(5L, pt.enteredAt)
            }
        }
        assertTrue(out.reminderStates.isEmpty())
        assertEquals(data.records, out.records)
        assertEquals(data.settings, out.settings)
    }

    @Test fun silenceIsUnion() {
        assertEquals(setOf(1L, 2L, 3L), BackupImportRules.idsToSilence(setOf(1L, 2L), setOf(2L, 3L)))
    }

    @Test fun missingSettingsFallBackToDefaults() {
        val (s, notes) = BackupImportRules.effectiveSettings(null)
        assertEquals(BackupSettings(null, 30, "AUTO"), s)
        assertEquals(1, notes.size)
        val given = BackupSettings("", 7, "AMAP")
        assertEquals(given to emptyList<String>(), BackupImportRules.effectiveSettings(given))
        // 第一件打的包导出的文件没有 mapService：按默认「自动」并提示
        val (noMap, mapNotes) = BackupImportRules.effectiveSettings(BackupSettings("", 7, null))
        assertEquals(BackupSettings("", 7, "AUTO"), noMap)
        assertTrue(mapNotes.single().contains("地图服务"))
    }

    @Test fun confirmTextShowsCountsAndWarnings() {
        val decoded = BackupCodec.decode(BackupCodec.encode(BackupFixtures.data(), BackupFixtures.META))
        val text = BackupImportRules.confirmText(decoded, isUndo = false)
        assertTrue(text, text.contains("提醒点 2 个") && text.contains("到达记录 2 条") && text.contains("日期备注 1 条"))
        assertTrue(text.contains("跳过记录 1 条") && text.contains("启停记录 1 条"))
        assertTrue(text.contains("调试日志会被清空"))
        assertTrue(text.contains("撤销上次导入"))
        assertTrue(text.contains("v2.2.25"))
        assertFalse(text.contains("旧格式"))
        val legacy = BackupImportRules.confirmText(BackupCodec.decode(BackupFixtures.LEGACY_V2224), isUndo = false)
        assertTrue(legacy.contains("旧格式") && legacy.contains("提醒点 2 个") && legacy.contains("到达记录 2 条"))
        val undo = BackupImportRules.confirmText(decoded, isUndo = true, undoTime = "2026-11-14 10:00")
        assertTrue(undo.contains("恢复到上次导入之前（2026-11-14 10:00）"))
    }

    /** v2.2.26（低 2）：导入文件上限 10 MB，提示里说明旧备份可能超限。 */
    @Test fun importSizeLimitIsTenMegabytes() {
        assertEquals(10L * 1024 * 1024, BackupImportRules.MAX_IMPORT_BYTES)
        assertTrue(BackupImportRules.TOO_LARGE_TEXT.contains("10 MB") && BackupImportRules.TOO_LARGE_TEXT.contains("调试日志"))
    }

    // ── v2.2.26（低 5）：撤销文件只留最近 3 份 ──
    private fun imp(t: String) = "before-import-$t.json"
    private fun undo(t: String) = "before-undo-$t.json"

    @Test fun undoFiles_keepNewestThree() {
        val names = listOf(
            imp("20261110-080000-000"), imp("20261111-080000-000"), imp("20261112-080000-000"),
            imp("20261113-080000-000"), imp("20261114-080000-000"),
            undo("20261110-090000-000"), undo("20261111-090000-000"), undo("20261112-090000-000"), undo("20261113-090000-000")
        )
        val deleted = BackupImportRules.undoFilesToDelete(names)
        val kept = names - deleted.toSet()
        assertEquals(setOf(imp("20261114-080000-000"), undo("20261113-090000-000"), imp("20261113-080000-000")), kept.toSet())
        assertEquals(6, deleted.size)
    }

    /** 最新 3 份全是「撤销前」时，保住最新的一份「导入前」，否则「撤销上次导入」入口会消失。 */
    @Test fun undoFiles_alwaysKeepNewestBeforeImport() {
        val names = listOf(
            imp("20261110-080000-000"), imp("20261111-080000-000"),
            undo("20261112-090000-000"), undo("20261113-090000-000"), undo("20261114-090000-000")
        )
        val kept = (names - BackupImportRules.undoFilesToDelete(names).toSet()).toSet()
        assertEquals(setOf(undo("20261114-090000-000"), undo("20261113-090000-000"), imp("20261111-080000-000")), kept)
    }

    @Test fun undoFiles_nothingDeletedWhenThreeOrFewer_andUnknownNamesUntouched() {
        assertTrue(BackupImportRules.undoFilesToDelete(emptyList()).isEmpty())
        assertTrue(BackupImportRules.undoFilesToDelete(listOf(imp("20261110-080000-000"), undo("20261110-090000-000"), imp("20261111-080000-000"))).isEmpty())
        val names = listOf(
            imp("20261110-080000-000"), imp("20261111-080000-000"), imp("20261112-080000-000"), imp("20261113-080000-000"),
            imp("20261114-080000-000") + ".tmp", "notes.txt", "before-import-old.json", "checkin-backup-1.json"
        )
        assertEquals(listOf(imp("20261110-080000-000")), BackupImportRules.undoFilesToDelete(names))
    }

    /** 只有撤销前、没有导入前的文件时，照常留最新 3 份。 */
    @Test fun undoFiles_onlyBeforeUndo() {
        val names = (10..14).map { undo("202611$it-090000-000") }
        assertEquals(listOf(undo("20261111-090000-000"), undo("20261110-090000-000")), BackupImportRules.undoFilesToDelete(names))
    }
}
