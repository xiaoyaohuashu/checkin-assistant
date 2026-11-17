package com.checkinassistant.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存里的假数据库：记下每一步调用，用来测导入的先后顺序和失败分支。 */
class FakeBackupStore(var current: BackupData) : BackupStore {
    val calls = mutableListOf<String>()
    var failUndo = false
    var failReplace = false
    var monitor: MonitorOutcome = MonitorOutcome.Started
    var replaceGate: CompletableDeferred<Unit>? = null
    var settingsWritten: BackupSettings? = null
    var silenced: Set<Long> = emptySet()
    val undoFiles = mutableMapOf<String, String>()

    override suspend fun readSnapshot(): BackupData { calls += "readSnapshot"; return current }
    override suspend fun writeUndoFile(json: String, kind: UndoKind): String {
        calls += "writeUndo:${kind.prefix}"
        if (failUndo) throw java.io.IOException("磁盘满了")
        val name = "${kind.prefix}-${undoFiles.size}.json"; undoFiles[name] = json; return name
    }
    override suspend fun replaceAll(data: BackupData) {
        calls += "replaceAll"
        replaceGate?.await()
        if (failReplace) throw IllegalStateException("UNIQUE constraint failed")
        // 和真表一样：reminder_states 被清空且不插入
        current = data.copy(reminderStates = emptyList(), settings = current.settings)
    }
    override fun applySettings(settings: BackupSettings): List<String> {
        calls += "applySettings"; settingsWritten = settings; current = current.copy(settings = settings); return emptyList()
    }
    override fun silenceReminders(pointIds: Set<Long>) { calls += "silence"; silenced = pointIds }
    override fun restartMonitoring(): MonitorOutcome { calls += "restartMonitoring"; return monitor }
    override suspend fun log(message: String) { calls += "log" }
    override fun exportMeta() = BackupFixtures.META
}

class BackupImporterTest {
    private val existing = BackupData(
        points = listOf(CheckInPoint(id = 100, name = "旧点", latitude = 1.0, longitude = 1.0)),
        records = emptyList(), dayNotes = emptyList(), skipEvents = emptyList(), enabledEvents = emptyList(),
        reminderStates = listOf(ReminderState(pointId = 200, recordId = 1, reminderStartTime = 1, reminderCount = 1)),
        settings = BackupSettings("", 10, null)
    )

    private fun decoded(data: BackupData = BackupFixtures.data()) = BackupCodec.decode(BackupCodec.encode(data, BackupFixtures.META))

    @Test fun success_followsFixedOrder() = runBlocking {
        val store = FakeBackupStore(existing)
        val result = BackupImporter.importAll(store, decoded(), UndoKind.BEFORE_IMPORT)
        assertTrue(result is ImportResult.Success)
        assertEquals(listOf("readSnapshot", "writeUndo:before-import", "replaceAll", "applySettings", "silence", "restartMonitoring", "log"), store.calls)
        // 停的是：旧点、旧提醒状态里的点、新点
        assertEquals(setOf(100L, 200L, 7L, 8L), store.silenced)
        // 撤销文件里是导入前的数据
        assertEquals(existing, BackupCodec.decode(store.undoFiles.values.single()).data)
        val s = result as ImportResult.Success
        assertEquals(2, s.points); assertEquals(2, s.records)
        assertTrue(s.notices.any { it.contains("提醒状态不会恢复") })
    }

    @Test fun undoFileFailure_nothingImported() = runBlocking {
        val store = FakeBackupStore(existing).apply { failUndo = true }
        val result = BackupImporter.importAll(store, decoded(), UndoKind.BEFORE_IMPORT)
        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).reason.contains("没有导入"))
        assertEquals(listOf("readSnapshot", "writeUndo:before-import"), store.calls)
        assertEquals(existing, store.current)
    }

    @Test fun transactionFailure_noSettingsNoSilenceNoRestart() = runBlocking {
        val store = FakeBackupStore(existing).apply { failReplace = true }
        val result = BackupImporter.importAll(store, decoded(), UndoKind.BEFORE_IMPORT)
        assertTrue(result is ImportResult.Failure)
        val reason = (result as ImportResult.Failure).reason
        assertTrue(reason, reason.contains("UNIQUE constraint failed") && reason.contains("现有数据没有改动"))
        assertEquals(listOf("readSnapshot", "writeUndo:before-import", "replaceAll", "log"), store.calls)
        assertEquals(existing, store.current)
    }

    @Test fun userStoppedMonitoring_staysStopped_stillSuccess() = runBlocking {
        val store = FakeBackupStore(existing).apply { monitor = MonitorOutcome.StillStoppedByUser }
        val result = BackupImporter.importAll(store, decoded(), UndoKind.BEFORE_IMPORT) as ImportResult.Success
        assertEquals(MonitorOutcome.StillStoppedByUser, result.monitor)
        assertTrue(BackupImportRules.resultText(result, isUndo = false).contains("仍保持停止"))
    }

    @Test fun undoWritesBeforeUndoFile() = runBlocking {
        val store = FakeBackupStore(existing)
        BackupImporter.importAll(store, decoded(), UndoKind.BEFORE_UNDO)
        assertEquals("writeUndo:before-undo", store.calls[1])
    }

    @Test fun secondImportWhileRunningIsBusy() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val store = FakeBackupStore(existing).apply { replaceGate = gate }
        val first = async(Dispatchers.Default) { BackupImporter.importAll(store, decoded(), UndoKind.BEFORE_IMPORT) }
        while (!store.calls.contains("replaceAll")) Thread.sleep(5)
        val second = BackupImporter.importAll(FakeBackupStore(existing), decoded(), UndoKind.BEFORE_IMPORT)
        gate.complete(Unit)
        assertEquals(ImportResult.Busy, second)
        assertTrue(first.await() is ImportResult.Success)
    }

    /** 需求 3：新格式「导出 → 导入」后数据完全一致（提醒状态按设计不恢复）。 */
    @Test fun exportThenImport_dataIdentical() = runBlocking {
        val source = BackupFixtures.data()
        val exported = BackupCodec.encode(FakeBackupStore(source).readSnapshot(), BackupFixtures.META)
        val target = FakeBackupStore(existing)
        val result = BackupImporter.importAll(target, BackupCodec.decode(exported), UndoKind.BEFORE_IMPORT)
        assertTrue(result is ImportResult.Success)
        // 夹具里没有进行中的状态，所以除了提醒状态（有意不恢复）以外应完全相等
        assertEquals(source.copy(reminderStates = emptyList()), target.current)
        assertEquals(source.settings, target.settingsWritten)
    }

    /** 需求 3：坏文件被拒绝且原数据不变——解析失败时根本走不到数据库。 */
    @Test fun badFile_neverReachesStore() = runBlocking {
        val store = FakeBackupStore(existing)
        for (bad in listOf("", "abc", "{}", """{"format":"checkin-assistant-backup","formatVersion":9}""")) {
            val outcome = runCatching { BackupCodec.decode(bad) }.map { BackupImporter.importAll(store, it, UndoKind.BEFORE_IMPORT) }
            assertTrue(outcome.exceptionOrNull() is BackupFormatException)
        }
        assertTrue(store.calls.isEmpty())
        assertEquals(existing, store.current)
    }

    @Test fun legacyImport_settingsResetToDefaultWithNotice() = runBlocking {
        val store = FakeBackupStore(existing)
        val result = BackupImporter.importAll(store, BackupCodec.decode(BackupFixtures.LEGACY_V2224), UndoKind.BEFORE_IMPORT) as ImportResult.Success
        assertEquals(BackupSettings(null, 30, "AUTO"), store.settingsWritten)
        assertTrue(result.notices.any { it.contains("已恢复为默认铃声") })
        assertTrue(result.notices.any { it.startsWith("旧备份里没有：每日次数上限") })
    }
}
