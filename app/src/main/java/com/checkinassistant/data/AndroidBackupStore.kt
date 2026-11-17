package com.checkinassistant.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.checkinassistant.domain.LocationSourceRules
import com.checkinassistant.domain.MapServiceRules
import com.checkinassistant.geofence.GeofenceMonitoringService
import com.checkinassistant.geofence.MonitorKeeper
import com.checkinassistant.location.PlayServices
import com.checkinassistant.map.MapSettings
import com.checkinassistant.reminder.ReminderEngine
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v2.2.25：[BackupStore] 的真机实现。 */
class AndroidBackupStore(context: Context) : BackupStore {
    private val app = context.applicationContext
    private val db get() = AppDatabase.get(app)

    override suspend fun readSnapshot(): BackupData = db.withTransaction {
        val dao = db.backupDao()
        BackupData(
            points = dao.points(), records = dao.records(), dayNotes = dao.dayNotes(),
            skipEvents = dao.skipEvents(), enabledEvents = dao.enabledEvents(),
            reminderStates = dao.reminderStates(), settings = readSettings()
        )
    }

    private fun readSettings(): BackupSettings {
        val ringtonePrefs = app.getSharedPreferences(RINGTONE_PREFS, Context.MODE_PRIVATE)
        val ringtone = if (ringtonePrefs.contains(KEY_RINGTONE_URI)) ringtonePrefs.getString(KEY_RINGTONE_URI, null) else null
        return BackupSettings(ringtone, DiagnosticRecordLogger.retentionDays(app), MapSettings.setting(app).name)
    }

    override suspend fun writeUndoFile(json: String, kind: UndoKind): String {
        migrateLegacyUndoFiles(app)
        val dir = undoDir(app).apply { mkdirs() }
        val name = "${kind.prefix}-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.json"
        val temp = File(dir, "$name.tmp")
        temp.writeText(json)
        val target = File(dir, name)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("无法写入 ${target.name}")
        }
        // v2.2.26（低 5）：只保留最近 3 份撤销文件（刚写的这份最新，一定在保留之列）；删不掉就算了
        runCatching {
            val names = dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
            BackupImportRules.undoFilesToDelete(names).forEach { old -> if (old != name) File(dir, old).delete() }
        }
        return name
    }

    override suspend fun replaceAll(data: BackupData) {
        db.withTransaction {
            val dao = db.backupDao()
            dao.deletePoints(); dao.deleteRecords(); dao.deleteDayNotes()
            dao.deleteSkipEvents(); dao.deleteEnabledEvents(); dao.deleteReminderStates()
            dao.insertPoints(data.points); dao.insertRecords(data.records); dao.insertDayNotes(data.dayNotes)
            dao.insertSkipEvents(data.skipEvents); dao.insertEnabledEvents(data.enabledEvents)
        }
    }

    override fun applySettings(settings: BackupSettings): List<String> {
        val notices = mutableListOf<String>()
        val ringtoneEditor = app.getSharedPreferences(RINGTONE_PREFS, Context.MODE_PRIVATE).edit()
        val uri = settings.ringtoneUri
        when {
            uri == null -> ringtoneEditor.remove(KEY_RINGTONE_URI)
            uri.isEmpty() -> ringtoneEditor.putString(KEY_RINGTONE_URI, "")
            ringtoneExists(uri) == false -> {
                ringtoneEditor.remove(KEY_RINGTONE_URI)
                notices.add("备份里的铃声在这台手机上找不到，已改用默认铃声")
            }
            else -> ringtoneEditor.putString(KEY_RINGTONE_URI, uri)
        }
        val retentionOk = app.getSharedPreferences(DiagnosticRecordLogger.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putInt(DiagnosticRecordLogger.KEY_RETENTION_DAYS, settings.retentionDays.coerceIn(1, 3650)).commit()
        val mapOk = settings.mapService?.let {
            val old = MapSettings.setting(app)
            val new = MapServiceRules.parseSetting(it)
            // v2.2.26（中 1）：导入可能在后台跑，这里不重建后台服务，只提示
            if (LocationSourceRules.needsServiceRecreate(old, new, PlayServices.isInstalled(app))) {
                notices.add("地图服务已改为「始终高德」，后台定位要到首页停止再启动后台监测后才改用高德")
            }
            MapSettings.setSetting(app, new)
        } ?: true
        if (!ringtoneEditor.commit() || !retentionOk || !mapOk) notices.add("数据已导入，但部分设置没能保存，请到设置页检查")
        return notices
    }

    /** true = 存在；false = 确认不存在；null = 无法判断（比如没有读取媒体的权限），这时保留原值。 */
    private fun ringtoneExists(uriText: String): Boolean? {
        val uri = Uri.parse(uriText)
        return try {
            when (uri.scheme) {
                "file" -> File(uri.path ?: return false).exists()
                "content" -> app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                else -> null
            }
        } catch (e: SecurityException) {
            null
        } catch (e: FileNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    override fun silenceReminders(pointIds: Set<Long>) {
        ReminderEngine.stopAlert(app, null)
        pointIds.forEach { id ->
            runCatching { ReminderEngine.cancel(app, id) }
            runCatching { ReminderEngine.dismissNotifications(app, id) }
        }
    }

    override fun restartMonitoring(): MonitorOutcome {
        if (MonitorKeeper.userStopped(app)) return MonitorOutcome.StillStoppedByUser
        val error = GeofenceMonitoringService.tryStart(app) ?: return MonitorOutcome.Started
        return MonitorOutcome.Failed(error.javaClass.simpleName + (error.message?.let { "：$it" } ?: ""))
    }

    override suspend fun log(message: String) {
        DiagnosticRecordLogger.geofence(app, message)
    }

    override fun exportMeta(): ExportMeta {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return ExportMeta(System.currentTimeMillis(), info.versionName ?: "?", code, db.openHelper.readableDatabase.version)
    }

    companion object {
        private const val RINGTONE_PREFS = "reminder_settings"
        private const val KEY_RINGTONE_URI = "ringtone_uri"

        /**
         * 撤销文件放 App 私有的「不参与系统备份」目录（不会是 null，也不需要权限）。
         * v2.2.26（低 5）：从 files/backup-undo 挪到 no_backup/backup-undo，系统云备份 / 换机迁移不会带走它。
         */
        fun undoDir(context: Context) = File(context.applicationContext.noBackupFilesDir, "backup-undo")

        private fun legacyUndoDir(context: Context) = File(context.applicationContext.filesDir, "backup-undo")

        /** 把 v2.2.25 留在旧目录里的撤销文件搬到新目录。同名的不覆盖，搬不动的留在原处；旧目录空了就删掉。可以重复调用。 */
        fun migrateLegacyUndoFiles(context: Context) {
            runCatching {
                val old = legacyUndoDir(context)
                val files = old.listFiles() ?: return
                val target = undoDir(context).apply { mkdirs() }
                files.filter { it.isFile }.forEach { f ->
                    val to = File(target, f.name)
                    if (!to.exists()) f.renameTo(to)
                }
                if (old.listFiles()?.isEmpty() == true) old.delete()
            }
        }

        /** 最近一次「导入前」的撤销文件；没有就是 null。 */
        fun latestUndoFile(context: Context): File? = undoDir(context.also(::migrateLegacyUndoFiles))
            .listFiles { f -> f.isFile && f.name.startsWith(UndoKind.BEFORE_IMPORT.prefix + "-") && f.name.endsWith(".json") }
            ?.maxByOrNull { it.name }
    }
}
