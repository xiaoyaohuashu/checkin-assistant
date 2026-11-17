package com.checkinassistant.data

/** v2.2.25 备份用的数据结构。都是纯 Kotlin，单测可以直接用。 */

/**
 * 备份里的设置。
 * - [ringtoneUri]：null = 默认铃声（这台手机上没选过）；"" = 静音；其它 = 选过的铃声地址。
 * - [mapService]：AUTO / GOOGLE / AMAP；null = 备份里没有这一项。
 */
data class BackupSettings(
    val ringtoneUri: String?,
    val retentionDays: Int,
    val mapService: String? = null
)

/** 一份完整数据。reminderStates 会导出，但导入时不写回（见 BackupImportRules.normalizeForImport）。 */
data class BackupData(
    val points: List<CheckInPoint>,
    val records: List<CheckInRecord>,
    val dayNotes: List<DayNote>,
    val skipEvents: List<PointSkipEvent>,
    val enabledEvents: List<PointEnabledEvent>,
    val reminderStates: List<ReminderState>,
    /** null = 备份里没有设置（旧格式）。 */
    val settings: BackupSettings?
)

data class ExportMeta(
    val exportedAt: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val dbVersion: Int
)

/**
 * 解析好的备份。
 * - [legacy]：v2.2.24 及更早导出的旧格式。
 * - [missing]：旧备份里没有的内容（导入后告诉用户）。
 * - [warnings]：个别字段不认识、已换成默认值。
 */
data class DecodedBackup(
    val data: BackupData,
    val legacy: Boolean,
    val exportedAt: Long?,
    val appVersionName: String?,
    val missing: List<String>,
    val warnings: List<String>
)

/** 文件不是能导入的备份。message 是给用户看的中文原因。 */
class BackupFormatException(message: String) : Exception(message)

/** 撤销文件的种类，体现在文件名前缀上。撤销入口只认 BEFORE_IMPORT。 */
enum class UndoKind(val prefix: String) {
    BEFORE_IMPORT("before-import"),
    BEFORE_UNDO("before-undo")
}

sealed class MonitorOutcome {
    object Started : MonitorOutcome()
    object StillStoppedByUser : MonitorOutcome()
    data class Failed(val message: String) : MonitorOutcome()
}

sealed class ImportResult {
    data class Success(
        val points: Int,
        val records: Int,
        val notices: List<String>,
        val undoFile: String,
        val monitor: MonitorOutcome
    ) : ImportResult()
    data class Failure(val reason: String) : ImportResult()
    object Busy : ImportResult()
}
