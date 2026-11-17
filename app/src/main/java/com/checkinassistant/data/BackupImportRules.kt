package com.checkinassistant.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v2.2.25：导入时的纯规则（单测直接测）。 */
object BackupImportRules {
    /** 「正在进行中」的状态：导入后一律回到「等待进入」，否则导入完可能马上又响。 */
    private val TRANSIENT_STATES = setOf(
        PointState.ENTERED.name, PointState.DWELL_CONFIRMING.name, PointState.REMINDING.name, PointState.SNOOZED.name
    )

    /**
     * 导入前的规整：
     * - 提醒点的进行中状态改回 ARMED、enteredAt 清空（完成 / 忽略等状态原样保留）；
     * - 提醒状态（reminder_states）不导入：全屏提醒页如果还留在后台，点「稍后提醒」只要查得到状态行
     *   就会重新响（ReminderEngine.snooze），所以导入后这张表保持为空。
     */
    fun normalizeForImport(data: BackupData): BackupData = data.copy(
        points = data.points.map { p ->
            if (p.triggerState in TRANSIENT_STATES) p.copy(triggerState = PointState.ARMED.name, enteredAt = null) else p
        },
        reminderStates = emptyList()
    )

    /** v2.2.26（低 2）：导入文件的大小上限（原来是 50 MB）。 */
    const val MAX_IMPORT_BYTES = 10L * 1024 * 1024
    const val TOO_LARGE_TEXT = "文件太大（超过 10 MB），无法导入。v2.2.24 及更早版本导出的备份里带调试日志，可能超过这个大小"

    /** 「地图服务」的默认值。 */
    const val DEFAULT_MAP_SERVICE = "AUTO"

    /** 实际要写入的设置。备份里没有的设置项恢复默认（需求：缺的字段用默认值），并给出提示。 */
    fun effectiveSettings(settings: BackupSettings?): Pair<BackupSettings, List<String>> {
        if (settings == null) {
            return BackupSettings(null, DiagnosticRecordLogger.DEFAULT_RETENTION_DAYS, DEFAULT_MAP_SERVICE) to listOf(
                "备份里没有铃声、调试日志保留天数和地图服务设置，已恢复为默认铃声、保留 ${DiagnosticRecordLogger.DEFAULT_RETENTION_DAYS} 天、地图服务「自动」"
            )
        }
        if (settings.mapService == null) {
            return settings.copy(mapService = DEFAULT_MAP_SERVICE) to listOf("备份里没有「地图服务」设置，已恢复为「自动」")
        }
        return settings to emptyList()
    }

    /** 导入后要停掉的提醒点：导入前的（含有提醒状态的）和导入后的，取并集。 */
    fun idsToSilence(oldIds: Set<Long>, newIds: Set<Long>): Set<Long> = oldIds + newIds

    fun confirmText(backup: DecodedBackup, isUndo: Boolean, undoTime: String? = null): String {
        val d = backup.data
        val sb = StringBuilder()
        if (isUndo) {
            sb.append("将把全部数据恢复到上次导入之前${undoTime?.let { "（$it）" } ?: ""}的状态。\n\n")
        } else {
            sb.append("将用这个备份替换这台手机上的全部数据。\n\n")
        }
        sb.append("备份内容：提醒点 ${d.points.size} 个、到达记录 ${d.records.size} 条、日期备注 ${d.dayNotes.size} 条、")
        sb.append("跳过记录 ${d.skipEvents.size} 条、启停记录 ${d.enabledEvents.size} 条。\n")
        backup.exportedAt?.let { sb.append("导出时间：${formatTime(it)}") }
        backup.appVersionName?.let { sb.append("（v$it）") }
        if (backup.exportedAt != null || backup.appVersionName != null) sb.append("\n")
        if (backup.legacy) sb.append("这是 v2.2.24 及更早版本导出的旧格式备份，有些内容里面没有，导入后会列出来。\n")
        sb.append("\n注意：\n")
        sb.append("· 现有的提醒点、到达记录、日期备注、跳过/启停记录和设置都会被替换\n")
        sb.append("· 这台手机上的调试日志会被清空，需要的话先到「调试日志」页导出\n")
        sb.append("· 正在响的提醒会停止\n")
        if (!isUndo) sb.append("· 导入前会自动保存一份当前数据，可在「导入数据备份」里撤销上次导入\n")
        sb.append("· 建议在没有提醒进行时导入")
        return sb.toString()
    }

    fun resultText(result: ImportResult.Success, isUndo: Boolean): String {
        val sb = StringBuilder()
        sb.append(if (isUndo) "已恢复到上次导入之前的数据" else "导入完成")
        sb.append("：提醒点 ${result.points} 个，到达记录 ${result.records} 条。\n\n")
        sb.append(
            when (val m = result.monitor) {
                MonitorOutcome.Started -> "后台监测已重新安排。"
                MonitorOutcome.StillStoppedByUser -> "你之前手动停止了后台监测，导入后仍保持停止；需要时到首页点「启动」。"
                is MonitorOutcome.Failed -> "后台监测没能重新启动（${m.message}），请到首页检查。"
            }
        )
        if (result.notices.isNotEmpty()) {
            sb.append("\n\n请留意：\n")
            result.notices.forEach { sb.append("· ").append(it).append('\n') }
        }
        if (!isUndo) sb.append("\n导入前的数据已另存（${result.undoFile}），可在「导入数据备份」里选「撤销上次导入」恢复。")
        return sb.toString().trimEnd()
    }

    /** 撤销文件名：前缀-yyyyMMdd-HHmmss-SSS.json */
    private val UNDO_NAME = Regex("(${UndoKind.BEFORE_IMPORT.prefix}|${UndoKind.BEFORE_UNDO.prefix})-(\\d{8}-\\d{6}-\\d{3})\\.json")
    const val UNDO_FILES_TO_KEEP = 3

    /**
     * v2.2.26（低 5）：撤销文件只保留最近 [keep] 份，返回该删的文件名。
     * 按文件名里的时间从新到旧排（两种前缀一起算）；但「撤销上次导入」只认「导入前」的那种，
     * 所以如果最新的几份里一份「导入前」都没有，就用最新的一份「导入前」换掉其中最旧的一份——入口不会因为清理而消失。
     * 不认识的文件名（包括写到一半的 .tmp）不动。
     */
    fun undoFilesToDelete(names: List<String>, keep: Int = UNDO_FILES_TO_KEEP): List<String> {
        val known = names.mapNotNull { name -> UNDO_NAME.matchEntire(name)?.let { Triple(name, it.groupValues[1], it.groupValues[2]) } }
            .sortedWith(compareByDescending<Triple<String, String, String>> { it.third }.thenByDescending { it.first })
        if (known.size <= keep) return emptyList()
        val kept = known.take(keep).toMutableList()
        val importPrefix = UndoKind.BEFORE_IMPORT.prefix
        if (kept.none { it.second == importPrefix }) {
            known.firstOrNull { it.second == importPrefix }?.let { newestImport ->
                kept.removeAt(kept.lastIndex)
                kept.add(newestImport)
            }
        }
        val keepNames = kept.map { it.first }.toSet()
        return known.map { it.first }.filterNot { it in keepNames }
    }

    fun formatTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
}
