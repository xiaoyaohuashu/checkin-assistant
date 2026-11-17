package com.checkinassistant.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.2.25：「覆盖全部」导入。顺序固定：
 * 1. 读当前数据 → 存撤销文件（存不了就不导入）；
 * 2. 一个事务里清空并写入（出错整体回滚，后面的步骤一个都不做）；
 * 3. 写设置 → 停掉提醒 → 重新安排后台监测（手动停止过就保持停止）→ 写日志。
 * 2、3 步不受页面关闭 / 转屏取消影响。
 */
object BackupImporter {
    private val running = AtomicBoolean(false)

    suspend fun importAll(store: BackupStore, backup: DecodedBackup, kind: UndoKind): ImportResult {
        if (!running.compareAndSet(false, true)) return ImportResult.Busy
        try {
            val current: BackupData
            val undoFile: String
            try {
                current = store.readSnapshot()
                undoFile = store.writeUndoFile(BackupCodec.encode(current, store.exportMeta()), kind)
            } catch (e: Exception) {
                return ImportResult.Failure("无法保存导入前的数据（${describe(e)}），没有导入，现有数据没有改动")
            }

            val data = BackupImportRules.normalizeForImport(backup.data)
            try {
                withContext(NonCancellable) { store.replaceAll(data) }
            } catch (e: Exception) {
                runCatching { withContext(NonCancellable) { store.log("BACKUP_IMPORT_FAILED：${describe(e)}；事务已回滚") } }
                return ImportResult.Failure("导入失败：${describe(e)}。已全部撤回，现有数据没有改动")
            }

            return withContext(NonCancellable) {
                val (settings, settingNotices) = BackupImportRules.effectiveSettings(backup.data.settings)
                val applyNotices = store.applySettings(settings)
                val oldIds = current.points.map { it.id }.toSet() + current.reminderStates.map { it.pointId }
                store.silenceReminders(BackupImportRules.idsToSilence(oldIds, data.points.map { it.id }.toSet()))
                val monitor = store.restartMonitoring()
                val monitorText = when (monitor) {
                    MonitorOutcome.Started -> "重新安排"
                    MonitorOutcome.StillStoppedByUser -> "保持停止（用户手动停止过）"
                    is MonitorOutcome.Failed -> "启动失败：${monitor.message}"
                }
                runCatching {
                    store.log(
                        "BACKUP_IMPORT：类型=${kind.prefix}，旧格式=${backup.legacy}，提醒点=${data.points.size}，" +
                            "记录=${data.records.size}，撤销文件=$undoFile，监测=$monitorText"
                    )
                }
                val notices = backup.missing.map { "旧备份里没有：$it" } + backup.warnings + settingNotices + applyNotices +
                    if (current.reminderStates.isNotEmpty() || backup.data.reminderStates.isNotEmpty()) {
                        listOf("进行中的提醒状态不会恢复，导入后所有提醒点都从「等待进入」开始")
                    } else emptyList()
                ImportResult.Success(data.points.size, data.records.size, notices, undoFile, monitor)
            }
        } finally {
            running.set(false)
        }
    }

    private fun describe(e: Throwable): String = "${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}"
}
