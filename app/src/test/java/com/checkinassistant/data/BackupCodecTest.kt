package com.checkinassistant.data

import com.checkinassistant.TestSupport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class BackupCodecTest {
    private lateinit var previousZone: TimeZone
    @Before fun setUp() { previousZone = TestSupport.useKualaLumpur() }
    @After fun tearDown() { TimeZone.setDefault(previousZone) }

    private fun roundTrip(data: BackupData) = BackupCodec.decode(BackupCodec.encode(data, BackupFixtures.META))

    @Test fun newFormat_everyFieldSurvivesRoundTrip() {
        val decoded = roundTrip(BackupFixtures.data())
        assertEquals(BackupFixtures.data(), decoded.data)
        assertFalse(decoded.legacy)
        assertEquals(BackupFixtures.META.exportedAt, decoded.exportedAt)
        assertEquals("2.2.25", decoded.appVersionName)
        assertTrue(decoded.missing.isEmpty())
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test fun newFormat_ringtoneThreeStatesAndOptionalMapService() {
        for (s in listOf(BackupSettings(null, 30, null), BackupSettings("", 3650, "AUTO"), BackupSettings("file:///x.mp3", 7, "GOOGLE"))) {
            assertEquals(s, roundTrip(BackupFixtures.data(s)).data.settings)
        }
    }

    @Test fun newFormat_emptyTablesRoundTrip() {
        val empty = BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), BackupSettings(null, 30, null))
        assertEquals(empty, roundTrip(empty).data)
    }

    @Test fun newFormat_nullsAreWrittenNotOmitted() {
        val json = JSONObject(BackupCodec.encode(BackupFixtures.data(), BackupFixtures.META))
        val nullPoint = json.getJSONArray("points").getJSONObject(1)
        assertEquals(20, nullPoint.length())
        assertTrue(nullPoint.isNull("color"))
        assertEquals(15, json.getJSONArray("records").getJSONObject(1).length())
        assertEquals(2, json.getInt("formatVersion"))
        assertEquals("checkin-assistant-backup", json.getString("format"))
    }

    @Test fun bomIsAccepted() {
        val text = "﻿" + BackupCodec.encode(BackupFixtures.data(), BackupFixtures.META)
        assertEquals(BackupFixtures.data(), BackupCodec.decode(text).data)
    }

    @Test fun unknownTriggerStateIsDowngradedNotRejected() {
        val json = JSONObject(BackupCodec.encode(BackupFixtures.data(), BackupFixtures.META))
        json.getJSONArray("points").getJSONObject(0).put("triggerState", "FLYING")
        val decoded = BackupCodec.decode(json.toString())
        assertEquals(PointState.ARMED.name, decoded.data.points[0].triggerState)
        assertNull(decoded.data.points[0].enteredAt)
        assertEquals(1, decoded.warnings.size)
    }

    @Test fun legacyV2224_importsWithDefaults() {
        val decoded = BackupCodec.decode(BackupFixtures.LEGACY_V2224, now = 1L)
        assertTrue(decoded.legacy)
        assertNull(decoded.data.settings)
        val p1 = decoded.data.points[0]
        assertEquals(CheckInPoint(
            id = 1, name = "公司", latitude = 3.139, longitude = 101.6869, radiusMeters = 200,
            repeatMode = "WEEKLY", activeDays = 31, activeStartMinute = 480, activeEndMinute = 570, enabled = true
        ), p1)
        assertEquals(25, p1.dwellSeconds)
        assertEquals(PointState.ARMED.name, p1.triggerState)
        assertNull(p1.dailyTriggerLimit); assertNull(p1.color); assertNull(p1.onceDate)
        val p2 = decoded.data.points[1]
        assertEquals("ONCE", p2.repeatMode); assertEquals("PENDING", p2.onceStatus); assertFalse(p2.enabled)
        assertNull(p2.activeStartMinute)

        // 调试日志被跳过；id 交给数据库编号；日历字段按吉隆坡时间
        assertEquals(2, decoded.data.records.size)
        val r = decoded.data.records[0]
        assertEquals(0L, r.id)
        val expected = TestSupport.at("2026-11-13", "09:00")
        assertEquals(expected, r.triggeredAt)
        assertEquals(listOf(2026, 11, 13, 9, 0, 0), listOf(r.year, r.month, r.day, r.hour, r.minute, r.second))
        assertEquals(java.util.Calendar.FRIDAY, r.weekday)
        assertEquals(0, r.reminderCount); assertNull(r.actionAt); assertFalse(r.simulated)
        assertEquals(RecordStatus.IGNORED.name, decoded.data.records[1].status)

        assertEquals(listOf(DayNote("2026-11-12", "休息", 1_763_000_000_000L)), decoded.data.dayNotes)
        assertTrue(decoded.data.skipEvents.isEmpty() && decoded.data.enabledEvents.isEmpty() && decoded.data.reminderStates.isEmpty())
        assertTrue(decoded.missing.any { it.startsWith("每日次数上限") })
        assertTrue(decoded.missing.any { it.contains("1 个「仅一次」提醒点没有日期") })
        assertFalse(decoded.missing.contains("日期备注"))
    }

    @Test fun legacyWithoutDayNotesAndOldestFields() {
        val text = """{"app":"checkin-assistant","points":[{"id":3,"name":"站","latitude":1,"longitude":2,"radiusMeters":100}],"records":[]}"""
        val decoded = BackupCodec.decode(text, now = 42L)
        assertTrue(decoded.legacy)
        assertEquals(CheckInPoint(id = 3, name = "站", latitude = 1.0, longitude = 2.0, radiusMeters = 100), decoded.data.points[0])
        assertTrue(decoded.data.dayNotes.isEmpty())
        assertTrue(decoded.missing.contains("日期备注"))
        assertNull(decoded.exportedAt)
    }

    private fun assertRejected(text: String, hint: String? = null) {
        try {
            BackupCodec.decode(text)
            fail("应该被拒绝：$text".take(200))
        } catch (e: BackupFormatException) {
            if (hint != null) assertTrue("原因「${e.message}」应包含「$hint」", e.message!!.contains(hint))
        }
    }

    private fun mutated(block: (JSONObject) -> Unit): String {
        val json = JSONObject(BackupCodec.encode(BackupFixtures.data(), BackupFixtures.META))
        block(json)
        return json.toString()
    }

    @Test fun badFilesAreRejected() {
        assertRejected("", "空")
        assertRejected("   ")
        assertRejected("abc", "JSON")
        assertRejected("[]", "JSON")
        assertRejected("\u0089PNG\r\n")
        assertRejected("""{"name":"别的 App","points":[]}""", "不是「别错过站」")
        assertRejected("""{"app":"checkin-assistant","points":[]}""")
        assertRejected(mutated { it.put("format", "other") }, "不认识")
        assertRejected(mutated { it.put("formatVersion", 3) }, "升级")
        assertRejected(mutated { it.put("formatVersion", 1) }, "版本")
        assertRejected(mutated { it.put("formatVersion", "2") })
        assertRejected(mutated { it.remove("points") }, "points")
        assertRejected(mutated { it.remove("settings") }, "settings")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).remove("latitude") }, "第 1 个提醒点：缺少 latitude")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(1).remove("color") }, "第 2 个提醒点：缺少 color")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("latitude", "abc") }, "latitude")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("latitude", "3.1") }, "latitude")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("latitude", 91) }, "latitude")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("longitude", -180.5) }, "longitude")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("radiusMeters", 0) }, "radiusMeters")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("radiusMeters", 1.5) }, "整数")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("id", 0) }, "id")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("id", Int.MAX_VALUE.toLong() + 1) }, "id")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(1).put("id", 7) }, "提醒点 id 重复")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("repeatMode", "HOURLY") }, "repeatMode")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("enabled", "true") }, "enabled")
        assertRejected(mutated { it.getJSONArray("points").getJSONObject(0).put("name", JSONObject.NULL) }, "name")
        assertRejected(mutated { it.getJSONArray("records").getJSONObject(1).put("id", 3) }, "到达记录 id 重复")
        assertRejected(mutated { it.getJSONArray("records").getJSONObject(0).put("status", "DEBUG") }, "调试日志")
        assertRejected(mutated { it.getJSONArray("records").getJSONObject(0).put("status", "LOST") }, "status")
        assertRejected(mutated { it.getJSONArray("dayNotes").put(JSONObject(it.getJSONArray("dayNotes").getJSONObject(0).toString())) }, "日期备注")
        assertRejected(mutated { it.getJSONArray("skipEvents").put(JSONObject(it.getJSONArray("skipEvents").getJSONObject(0).toString())) }, "跳过记录 id 重复")
        assertRejected(mutated { it.getJSONArray("enabledEvents").put(JSONObject(it.getJSONArray("enabledEvents").getJSONObject(0).toString())) }, "启停记录 id 重复")
        assertRejected(mutated { it.getJSONArray("reminderStates").getJSONObject(1).put("pointId", 7) }, "提醒状态")
        assertRejected(mutated { it.getJSONArray("points").put("not an object") }, "第 3 个提醒点")
        assertRejected(mutated { it.getJSONObject("settings").put("debugLogRetentionDays", 0) }, "debugLogRetentionDays")
        assertRejected(mutated { it.getJSONObject("settings").put("debugLogRetentionDays", 3651) }, "debugLogRetentionDays")
        assertRejected(mutated { it.getJSONObject("settings").put("mapService", "X") }, "mapService")
        assertRejected(mutated { it.getJSONObject("settings").remove("ringtoneUri") }, "ringtoneUri")
        // 旧格式里的结构错误同样拒绝
        assertRejected(BackupFixtures.LEGACY_V2224.replace("\"latitude\": 3.139", "\"latitude\": \"x\""), "latitude")
        assertRejected(BackupFixtures.LEGACY_V2224.replace("\"id\": 2", "\"id\": 1"), "提醒点 id 重复")
        assertRejected(BackupFixtures.LEGACY_V2224.replace("\"status\": \"IGNORED\"", "\"status\": \"WHAT\""), "status")
    }

    @Test fun newFormatWithoutMapServiceIsAccepted() {
        val decoded = BackupCodec.decode(mutated { it.getJSONObject("settings").remove("mapService") })
        assertNull(decoded.data.settings!!.mapService)
    }

    // ── v2.2.26（低 1）：每日次数上限、日期格式、时间不为负 ──

    private fun point0(json: JSONObject) = json.getJSONArray("points").getJSONObject(0)

    @Test fun dailyLimitMustBeNullOrAtLeastOne() {
        assertRejected(mutated { point0(it).put("dailyTriggerLimit", 0) }, "第 1 个提醒点：dailyTriggerLimit（每日次数上限）")
        assertRejected(mutated { point0(it).put("dailyTriggerLimit", -3) }, "dailyTriggerLimit")
        assertEquals(1, BackupCodec.decode(mutated { point0(it).put("dailyTriggerLimit", 1) }).data.points[0].dailyTriggerLimit)
        assertNull(BackupCodec.decode(mutated { point0(it).put("dailyTriggerLimit", JSONObject.NULL) }).data.points[0].dailyTriggerLimit)
        assertRejected(mutated { point0(it).put("dwellSeconds", -1) }, "dwellSeconds")
    }

    @Test fun datesMustBeRealYearMonthDay() {
        for (bad in listOf("2026-2-3", "2026/11/20", "20261120", "2026-02-30", "2026-13-01", "明天", "")) {
            assertRejected(mutated { point0(it).put("onceDate", bad) }, "onceDate")
            assertRejected(mutated { point0(it).put("skipUntilDate", bad) }, "skipUntilDate")
            assertRejected(mutated { it.getJSONArray("skipEvents").getJSONObject(0).put("skipDate", bad) }, "skipDate")
            assertRejected(mutated { it.getJSONArray("dayNotes").getJSONObject(0).put("date", bad) }, "date")
        }
        assertRejected(mutated { point0(it).put("onceDate", "2026-2-3") }, "第 1 个提醒点：onceDate 应该是「年-月-日」格式")
        // 合法的边界：闰年 2 月 29 日、月底
        assertEquals("2028-02-29", BackupCodec.decode(mutated { point0(it).put("onceDate", "2028-02-29") }).data.points[0].onceDate)
        assertEquals("2026-02-28", BackupCodec.decode(mutated { point0(it).put("skipUntilDate", "2026-02-28") }).data.points[0].skipUntilDate)
        assertNull(BackupCodec.decode(mutated { point0(it).put("onceDate", JSONObject.NULL) }).data.points[0].onceDate)
    }

    @Test fun timesMustNotBeNegative() {
        assertRejected(mutated { it.put("exportedAt", -1) }, "exportedAt")
        for (key in listOf("enteredAt", "completedAt", "lastTriggeredWindowStart")) {
            assertRejected(mutated { point0(it).put(key, -1) }, "第 1 个提醒点：$key 是时间，不能是负数")
            assertEquals(BackupFixtures.data().points.size, BackupCodec.decode(mutated { point0(it).put(key, 0) }).data.points.size)
        }
        assertRejected(mutated { it.getJSONArray("records").getJSONObject(0).put("triggeredAt", -5) }, "第 1 条到达记录：triggeredAt")
        assertRejected(mutated { it.getJSONArray("records").getJSONObject(0).put("actionAt", -5) }, "actionAt")
        assertRejected(mutated { it.getJSONArray("dayNotes").getJSONObject(0).put("updatedAt", -1) }, "updatedAt")
        assertRejected(mutated { it.getJSONArray("skipEvents").getJSONObject(0).put("createdAt", -1) }, "createdAt")
        assertRejected(mutated { it.getJSONArray("enabledEvents").getJSONObject(0).put("atMillis", -1) }, "atMillis")
    }

    /** 提醒状态导入时本来就不恢复，不为它新加校验；记录的年月日时分秒只影响历史显示，也不校验。 */
    @Test fun fieldsThatAreNotValidatedOnPurpose() {
        val text = mutated {
            it.getJSONArray("reminderStates").getJSONObject(0).put("reminderStartTime", -1).put("snoozeUntil", -1)
            it.getJSONArray("records").getJSONObject(0).put("month", 13).put("weekday", 9)
        }
        assertEquals(2, BackupCodec.decode(text).data.reminderStates.size)
    }

    @Test fun legacyFormatGetsTheSameChecks() {
        assertRejected(BackupFixtures.LEGACY_V2224.replace("\"date\": \"2026-11-12\"", "\"date\": \"2026-11-32\""), "date")
        assertRejected(BackupFixtures.LEGACY_V2224.replace("\"triggeredAt\": 1794445200000", "\"triggeredAt\": -1"), "triggeredAt")
        assertRejected(BackupFixtures.LEGACY_V2224.replace("\"exportedAt\": 1763000000000", "\"exportedAt\": -1"), "exportedAt")
        // 旧备份里的调试日志整条跳过，不看它的时间
        val decoded = BackupCodec.decode(BackupFixtures.LEGACY_V2224.replace("\"triggeredAt\": 1794531601000", "\"triggeredAt\": -1"))
        assertEquals(2, decoded.data.records.size)
    }

    /** 撤销文件用宽松模式：v2.2.26 新加的取值校验都不做，原有的结构校验照旧。 */
    @Test fun lenientSkipsOnlyTheNewValueChecks() {
        val text = mutated {
            point0(it).put("dailyTriggerLimit", 0).put("onceDate", "2026-2-3").put("enteredAt", -1).put("dwellSeconds", -1)
            it.getJSONArray("dayNotes").getJSONObject(0).put("date", "随便")
            it.put("exportedAt", -1)
        }
        assertRejected(text)
        val decoded = BackupCodec.decode(text, lenient = true)
        assertEquals(0, decoded.data.points[0].dailyTriggerLimit)
        assertEquals("2026-2-3", decoded.data.points[0].onceDate)
        assertEquals("随便", decoded.data.dayNotes[0].date)
        // 结构错误在宽松模式下照样拒绝
        try {
            BackupCodec.decode(mutated { point0(it).put("latitude", 91) }, lenient = true)
            fail("纬度越界在宽松模式下也应拒绝")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains("latitude"))
        }
    }
}
