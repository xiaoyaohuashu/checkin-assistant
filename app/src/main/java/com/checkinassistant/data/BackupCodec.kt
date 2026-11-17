package com.checkinassistant.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Calendar

/**
 * v2.2.25：备份文件的编码和解码（纯函数，不碰 Context，单测直接测）。
 *
 * 新格式（formatVersion = 2）：
 *   format / formatVersion / app / appVersionName / appVersionCode / dbVersion / exportedAt
 *   points[]（20 个字段）、records[]（15 个字段，不含调试日志）、dayNotes[]、skipEvents[]、
 *   enabledEvents[]、reminderStates[]、settings{ringtoneUri, debugLogRetentionDays, mapService(可选)}
 *   空值一律写成 JSON null，键不省略。
 *
 * 旧格式（v2.2.24 及更早的 BackupExporter）：没有 format 键，app = "checkin-assistant"，
 *   只有 points（11 个字段，空值的键被省略）、records（4 个字段，含调试日志）、dayNotes（可能没有）。
 *   缺的字段用默认值补，缺了哪些写进 [DecodedBackup.missing]。
 *
 * 任何结构问题都抛 [BackupFormatException]，消息带位置（例如「第 3 个提醒点：缺少 latitude」）。
 */
object BackupCodec {
    const val FORMAT = "checkin-assistant-backup"
    const val FORMAT_VERSION = 2
    const val APP = "checkin-assistant"

    val REPEAT_MODES = setOf("DAILY", "WEEKLY", "ONCE")
    val RECORD_STATUSES = setOf(RecordStatus.COMPLETED.name, RecordStatus.UNCONFIRMED.name, RecordStatus.IGNORED.name)
    val ONCE_STATUSES = setOf("PENDING", "COMPLETED", "SKIPPED", "EXPIRED")
    val MAP_SERVICES = setOf("AUTO", "GOOGLE", "AMAP")
    private val POINT_STATES = PointState.values().map { it.name }.toSet()
    private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

    // ───────────────────────────── 编码 ─────────────────────────────

    fun encode(data: BackupData, meta: ExportMeta): String {
        val out = JSONObject()
        out.put("format", FORMAT)
        out.put("formatVersion", FORMAT_VERSION)
        out.put("app", APP)
        out.put("appVersionName", meta.appVersionName)
        out.put("appVersionCode", meta.appVersionCode)
        out.put("dbVersion", meta.dbVersion)
        out.put("exportedAt", meta.exportedAt)
        out.put("points", JSONArray().apply {
            data.points.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name)
                    put("latitude", p.latitude); put("longitude", p.longitude)
                    put("radiusMeters", p.radiusMeters); put("dwellSeconds", p.dwellSeconds)
                    put("enabled", p.enabled); put("triggerState", p.triggerState)
                    put("enteredAt", nv(p.enteredAt))
                    put("activeStartMinute", nv(p.activeStartMinute)); put("activeEndMinute", nv(p.activeEndMinute))
                    put("dailyTriggerLimit", nv(p.dailyTriggerLimit)); put("color", nv(p.color))
                    put("repeatMode", p.repeatMode); put("activeDays", p.activeDays)
                    put("onceDate", nv(p.onceDate)); put("onceStatus", nv(p.onceStatus))
                    put("completedAt", nv(p.completedAt)); put("skipUntilDate", nv(p.skipUntilDate))
                    put("lastTriggeredWindowStart", nv(p.lastTriggeredWindowStart))
                })
            }
        })
        out.put("records", JSONArray().apply {
            data.records.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id); put("pointId", r.pointId); put("pointName", r.pointName)
                    put("triggeredAt", r.triggeredAt)
                    put("year", r.year); put("month", r.month); put("day", r.day); put("weekday", r.weekday)
                    put("hour", r.hour); put("minute", r.minute); put("second", r.second)
                    put("status", r.status); put("reminderCount", r.reminderCount)
                    put("actionAt", nv(r.actionAt)); put("simulated", r.simulated)
                })
            }
        })
        out.put("dayNotes", JSONArray().apply {
            data.dayNotes.forEach { n ->
                put(JSONObject().apply { put("date", n.date); put("note", n.note); put("updatedAt", n.updatedAt) })
            }
        })
        out.put("skipEvents", JSONArray().apply {
            data.skipEvents.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id); put("reminderPointId", e.reminderPointId)
                    put("skipDate", e.skipDate); put("createdAt", e.createdAt)
                })
            }
        })
        out.put("enabledEvents", JSONArray().apply {
            data.enabledEvents.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id); put("pointId", e.pointId); put("atMillis", e.atMillis); put("enabled", e.enabled)
                })
            }
        })
        out.put("reminderStates", JSONArray().apply {
            data.reminderStates.forEach { s ->
                put(JSONObject().apply {
                    put("pointId", s.pointId); put("recordId", s.recordId)
                    put("reminderStartTime", s.reminderStartTime); put("reminderCount", s.reminderCount)
                    put("snoozeUntil", nv(s.snoozeUntil)); put("active", s.active); put("simulated", s.simulated)
                })
            }
        })
        val settings = data.settings ?: BackupSettings(null, DiagnosticRecordLogger.DEFAULT_RETENTION_DAYS, null)
        out.put("settings", JSONObject().apply {
            put("ringtoneUri", nv(settings.ringtoneUri))
            put("debugLogRetentionDays", settings.retentionDays)
            settings.mapService?.let { put("mapService", it) }
        })
        return out.toString(2)
    }

    /** 空值写成 JSON null（直接 put(key, null) 在安卓上会把键删掉）。 */
    private fun nv(value: Any?): Any = value ?: JSONObject.NULL

    // ───────────────────────────── 解码 ─────────────────────────────

    /**
     * @param lenient v2.2.26：为 true 时跳过 v2.2.26 新加的取值校验（每日次数上限、日期格式、时间不为负）。
     *   只给 App 自己写的撤销文件用：v2.2.25 写下的撤销文件、或手机里已有的不合规旧数据，不能因为新规则就撤销不了。
     */
    fun decode(text: String, now: Long = System.currentTimeMillis(), lenient: Boolean = false): DecodedBackup {
        val body = text.removePrefix("﻿").trim()
        if (body.isEmpty()) throw BackupFormatException("文件是空的")
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw BackupFormatException("不是有效的备份文件（内容不是 JSON 对象）")
        } catch (e: RuntimeException) {
            throw BackupFormatException("不是有效的备份文件（内容无法解析）")
        }
        val format = root.opt("format")
        if (format != null && format != JSONObject.NULL) {
            if (format != FORMAT) throw BackupFormatException("不认识的文件（format 不是「别错过站」的备份）")
            val f = Fields(root, "文件头", strict = true)
            val version = f.long("formatVersion")
            if (version > FORMAT_VERSION) {
                throw BackupFormatException("这个备份来自更新版本的 App（格式版本 $version），请先升级 App 再导入")
            }
            if (version != FORMAT_VERSION.toLong()) throw BackupFormatException("不认识的备份格式版本（$version）")
            return decodeV2(root, lenient)
        }
        if (root.opt("app") == APP && root.opt("points") is JSONArray && root.opt("records") is JSONArray) {
            return decodeLegacy(root, now, lenient)
        }
        throw BackupFormatException("不是「别错过站」的备份文件")
    }

    private fun decodeV2(root: JSONObject, lenient: Boolean): DecodedBackup {
        val head = Fields(root, "文件头", strict = true, lenient = lenient)
        val exportedAt = head.time("exportedAt")
        val versionName = head.stringOrNull("appVersionName")
        val warnings = mutableListOf<String>()

        val points = objects(root, "points", "个提醒点", lenient = lenient).map { (f, _) -> point(f, legacy = false, warnings) }
        val records = objects(root, "records", "条到达记录", lenient = lenient).map { (f, _) -> recordV2(f) }
        val notes = objects(root, "dayNotes", "条日期备注", lenient = lenient).map { (f, _) ->
            DayNote(date = f.date("date"), note = f.string("note"), updatedAt = f.time("updatedAt"))
        }
        val skips = objects(root, "skipEvents", "条跳过记录", lenient = lenient).map { (f, _) ->
            PointSkipEvent(
                id = f.positiveId("id"), reminderPointId = f.long("reminderPointId"),
                skipDate = f.date("skipDate"), createdAt = f.time("createdAt")
            )
        }
        val enabledEvents = objects(root, "enabledEvents", "条启停记录", lenient = lenient).map { (f, _) ->
            PointEnabledEvent(id = f.positiveId("id"), pointId = f.long("pointId"), atMillis = f.time("atMillis"), enabled = f.bool("enabled"))
        }
        val states = objects(root, "reminderStates", "条提醒状态", lenient = lenient).map { (f, _) ->
            ReminderState(
                pointId = f.long("pointId"), recordId = f.long("recordId"),
                reminderStartTime = f.long("reminderStartTime"), reminderCount = f.int("reminderCount"),
                snoozeUntil = f.longOrNull("snoozeUntil"), active = f.bool("active"), simulated = f.bool("simulated")
            )
        }
        val settingsObj = root.opt("settings") as? JSONObject ?: throw BackupFormatException("缺少 settings（设置）")
        val sf = Fields(settingsObj, "设置", strict = true)
        val retention = sf.int("debugLogRetentionDays")
        if (retention !in 1..3650) sf.fail("debugLogRetentionDays 应在 1 到 3650 之间")
        val mapService = if (settingsObj.has("mapService")) sf.stringOrNull("mapService") else null
        if (mapService != null && mapService !in MAP_SERVICES) sf.fail("mapService 只能是 AUTO、GOOGLE、AMAP")
        val settings = BackupSettings(sf.stringOrNull("ringtoneUri"), retention, mapService)

        unique(points.map { it.id }, "提醒点 id")
        unique(records.map { it.id }, "到达记录 id")
        unique(notes.map { it.date }, "日期备注的日期")
        unique(skips.map { it.id }, "跳过记录 id")
        unique(enabledEvents.map { it.id }, "启停记录 id")
        unique(states.map { it.pointId }, "提醒状态的提醒点 id")

        return DecodedBackup(
            data = BackupData(points, records, notes, skips, enabledEvents, states, settings),
            legacy = false, exportedAt = exportedAt, appVersionName = versionName,
            missing = emptyList(), warnings = warnings
        )
    }

    private fun decodeLegacy(root: JSONObject, now: Long, lenient: Boolean): DecodedBackup {
        val head = Fields(root, "文件头", strict = false, lenient = lenient)
        val exportedAt = head.timeOrNull("exportedAt")
        val warnings = mutableListOf<String>()
        val points = objects(root, "points", "个提醒点", strict = false, lenient = lenient).map { (f, _) -> point(f, legacy = true, warnings) }
        val records = objects(root, "records", "条到达记录", strict = false, lenient = lenient).mapNotNull { (f, _) -> recordLegacy(f) }
        val hasNotes = root.has("dayNotes")
        val notes = if (!hasNotes) emptyList() else objects(root, "dayNotes", "条日期备注", strict = false, lenient = lenient).map { (f, _) ->
            DayNote(date = f.date("date"), note = f.string("note"), updatedAt = exportedAt ?: now)
        }
        unique(points.map { it.id }, "提醒点 id")
        unique(notes.map { it.date }, "日期备注的日期")

        val missing = mutableListOf(
            "每日次数上限（导入后都是「不限」）",
            "提醒点颜色（导入后按默认颜色显示）",
            "「仅一次」提醒的日期",
            "「今天跳过」的设置",
            "到达记录的提醒轮数和处理时间",
            "跳过记录、启停记录（历史统计里被跳过或停用的日子可能显示为未到达）",
            "提醒状态"
        )
        if (!hasNotes) missing.add("日期备注")
        val onceWithoutDate = points.count { it.repeatMode == "ONCE" }
        if (onceWithoutDate > 0) {
            missing.add("其中 $onceWithoutDate 个「仅一次」提醒点没有日期，不会提醒，请到首页编辑、重新设置日期")
        }
        return DecodedBackup(
            data = BackupData(points, records, notes, emptyList(), emptyList(), emptyList(), settings = null),
            legacy = true, exportedAt = exportedAt, appVersionName = null,
            missing = missing, warnings = warnings
        )
    }

    private fun point(f: Fields, legacy: Boolean, warnings: MutableList<String>): CheckInPoint {
        val id = f.long("id")
        if (id < 1 || id > Int.MAX_VALUE) f.fail("id 应在 1 到 ${Int.MAX_VALUE} 之间")
        val latitude = f.double("latitude")
        if (latitude < -90.0 || latitude > 90.0) f.fail("latitude 超出范围（-90 到 90）")
        val longitude = f.double("longitude")
        if (longitude < -180.0 || longitude > 180.0) f.fail("longitude 超出范围（-180 到 180）")
        val radius = f.int("radiusMeters")
        if (radius <= 0) f.fail("radiusMeters 应大于 0")
        val repeatMode = if (legacy) f.stringOrNull("repeatMode") ?: "DAILY" else f.string("repeatMode")
        if (repeatMode !in REPEAT_MODES) f.fail("repeatMode 只能是 DAILY、WEEKLY、ONCE")
        val activeDays = if (legacy) f.intOrNull("activeDays") ?: 127 else f.int("activeDays")
        if (activeDays !in 0..127) f.fail("activeDays 应在 0 到 127 之间")
        val start = f.intOrNull("activeStartMinute")
        val end = f.intOrNull("activeEndMinute")
        if (start != null && start !in 0..1439) f.fail("activeStartMinute 应在 0 到 1439 之间")
        if (end != null && end !in 0..1439) f.fail("activeEndMinute 应在 0 到 1439 之间")
        val enabled = if (legacy) f.boolOrNull("enabled") ?: true else f.bool("enabled")
        var onceStatus = f.stringOrNull("onceStatus")
        if (onceStatus != null && onceStatus !in ONCE_STATUSES) {
            warnings.add("${f.where}：一次性状态「$onceStatus」不认识，已清空")
            onceStatus = null
        }
        val name = f.string("name")
        if (legacy) {
            return CheckInPoint(
                id = id, name = name, latitude = latitude, longitude = longitude, radiusMeters = radius,
                enabled = enabled, activeStartMinute = start, activeEndMinute = end,
                repeatMode = repeatMode, activeDays = activeDays, onceStatus = onceStatus
            )
        }
        var triggerState = f.string("triggerState")
        var enteredAt = f.timeOrNull("enteredAt")
        if (triggerState !in POINT_STATES) {
            warnings.add("${f.where}：状态「$triggerState」不认识，已改为等待进入")
            triggerState = PointState.ARMED.name
            enteredAt = null
        }
        return CheckInPoint(
            id = id, name = name, latitude = latitude, longitude = longitude, radiusMeters = radius,
            dwellSeconds = f.int("dwellSeconds").also { v -> f.check(v >= 0) { "dwellSeconds 不能是负数（现在是 $v）" } }, enabled = enabled,
            triggerState = triggerState, enteredAt = enteredAt,
            activeStartMinute = start, activeEndMinute = end,
            dailyTriggerLimit = f.intOrNull("dailyTriggerLimit")?.also { v ->
                f.check(v >= 1) { "dailyTriggerLimit（每日次数上限）应该是空（不限）或至少 1（现在是 $v）" }
            },
            color = f.intOrNull("color"),
            repeatMode = repeatMode, activeDays = activeDays,
            onceDate = f.dateOrNull("onceDate"), onceStatus = onceStatus,
            completedAt = f.timeOrNull("completedAt"), skipUntilDate = f.dateOrNull("skipUntilDate"),
            lastTriggeredWindowStart = f.timeOrNull("lastTriggeredWindowStart")
        )
    }

    private fun recordV2(f: Fields): CheckInRecord {
        val status = f.string("status")
        if (status == RecordStatus.DEBUG.name) f.fail("新格式备份里不应包含调试日志")
        if (status !in RECORD_STATUSES) f.fail("status 只能是 COMPLETED、UNCONFIRMED、IGNORED")
        return CheckInRecord(
            id = f.positiveId("id"), pointId = f.long("pointId"), pointName = f.string("pointName"),
            triggeredAt = f.time("triggeredAt"),
            year = f.int("year"), month = f.int("month"), day = f.int("day"), weekday = f.int("weekday"),
            hour = f.int("hour"), minute = f.int("minute"), second = f.int("second"),
            status = status, reminderCount = f.int("reminderCount"),
            actionAt = f.timeOrNull("actionAt"), simulated = f.bool("simulated")
        )
    }

    /** 旧格式记录：跳过调试日志；id 交给数据库自动编号；日历字段按 triggeredAt 用本机时区算（同 ReminderEngine.newRecord）。 */
    private fun recordLegacy(f: Fields): CheckInRecord? {
        val status = f.string("status")
        if (status == RecordStatus.DEBUG.name) return null
        if (status !in RECORD_STATUSES) f.fail("status 只能是 COMPLETED、UNCONFIRMED、IGNORED、DEBUG")
        val time = f.time("triggeredAt")
        val c = Calendar.getInstance().apply { timeInMillis = time }
        return CheckInRecord(
            pointId = f.long("pointId"), pointName = f.string("pointName"), triggeredAt = time,
            year = c.get(Calendar.YEAR), month = c.get(Calendar.MONTH) + 1, day = c.get(Calendar.DAY_OF_MONTH),
            weekday = c.get(Calendar.DAY_OF_WEEK), hour = c.get(Calendar.HOUR_OF_DAY),
            minute = c.get(Calendar.MINUTE), second = c.get(Calendar.SECOND),
            status = status
        )
    }

    private fun objects(root: JSONObject, key: String, unit: String, strict: Boolean = true, lenient: Boolean = false): List<Pair<Fields, Int>> {
        val array = root.opt(key) as? JSONArray ?: throw BackupFormatException("缺少 $key 列表")
        return (0 until array.length()).map { i ->
            val where = "第 ${i + 1} $unit"
            val obj = array.opt(i) as? JSONObject ?: throw BackupFormatException("$where：格式不对")
            Fields(obj, where, strict, lenient) to i
        }
    }

    private fun <T> unique(values: List<T>, what: String) {
        val seen = HashSet<T>()
        values.forEach { if (!seen.add(it)) throw BackupFormatException("$what 重复：$it") }
    }

    /**
     * 严格读取字段：数字只认数字（不把 "12" 当成 12），整数字段不许有小数。
     * strict = true（新格式）：每个键都必须存在，可空字段允许 null。
     * strict = false（旧格式）：可空字段缺了当 null。
     */
    private class Fields(private val obj: JSONObject, val where: String, private val strict: Boolean, val lenient: Boolean = false) {
        fun fail(message: String): Nothing = throw BackupFormatException("$where：$message")

        private fun value(key: String, nullable: Boolean): Any? {
            if (!obj.has(key)) {
                if (strict || !nullable) fail("缺少 $key")
                return null
            }
            val v = obj.opt(key)
            if (v == null || v == JSONObject.NULL) {
                if (nullable) return null
                fail("$key 不能为空")
            }
            return v
        }

        private fun asString(key: String, v: Any): String = v as? String ?: fail("$key 应该是文字")
        private fun asBool(key: String, v: Any): Boolean = v as? Boolean ?: fail("$key 应该是 true 或 false")
        private fun asLong(key: String, v: Any): Long {
            if (v !is Number) fail("$key 应该是数字")
            val decimal = try { BigDecimal(v.toString()) } catch (e: NumberFormatException) { fail("$key 不是有效数字") }
            return try { decimal.longValueExact() } catch (e: ArithmeticException) { fail("$key 应该是整数") }
        }
        private fun asInt(key: String, v: Any): Int {
            val n = asLong(key, v)
            if (n < Int.MIN_VALUE || n > Int.MAX_VALUE) fail("$key 超出范围")
            return n.toInt()
        }
        private fun asDouble(key: String, v: Any): Double {
            if (v !is Number) fail("$key 应该是数字")
            val d = v.toDouble()
            if (d.isNaN() || d.isInfinite()) fail("$key 不是有效数字")
            return d
        }

        fun string(key: String) = asString(key, value(key, false)!!)
        fun nonBlank(key: String) = string(key).also { if (it.isBlank()) fail("$key 不能为空") }
        fun stringOrNull(key: String) = value(key, true)?.let { asString(key, it) }
        fun bool(key: String) = asBool(key, value(key, false)!!)
        fun boolOrNull(key: String) = value(key, true)?.let { asBool(key, it) }
        fun long(key: String) = asLong(key, value(key, false)!!)
        fun longOrNull(key: String) = value(key, true)?.let { asLong(key, it) }
        fun int(key: String) = asInt(key, value(key, false)!!)
        fun intOrNull(key: String) = value(key, true)?.let { asInt(key, it) }
        fun double(key: String) = asDouble(key, value(key, false)!!)
        fun positiveId(key: String) = long(key).also { if (it < 1) fail("$key 应大于 0") }

        // ── v2.2.26（低 1）新加的取值校验；lenient 时跳过 ──
        /** 时间（毫秒）：不能是负数。 */
        fun time(key: String) = long(key).also { checkTime(key, it) }
        fun timeOrNull(key: String) = longOrNull(key)?.also { checkTime(key, it) }
        /** 日期：必须是 yyyy-MM-dd 且真实存在（App 自己写的就是这个格式）。 */
        fun date(key: String) = nonBlank(key).also { checkDate(key, it) }
        fun dateOrNull(key: String) = stringOrNull(key)?.also { checkDate(key, it) }

        private fun checkTime(key: String, value: Long) {
            if (!lenient && value < 0) fail("$key 是时间，不能是负数（现在是 $value）")
        }

        private fun checkDate(key: String, value: String) {
            if (lenient) return
            val ok = DATE_PATTERN.matches(value) && try { java.time.LocalDate.parse(value); true } catch (e: Exception) { false }
            if (!ok) fail("$key 应该是「年-月-日」格式的真实日期，例如 2026-11-20（现在是「$value」）")
        }

        fun check(condition: Boolean, message: () -> String) {
            if (!lenient && !condition) fail(message())
        }
    }
}
