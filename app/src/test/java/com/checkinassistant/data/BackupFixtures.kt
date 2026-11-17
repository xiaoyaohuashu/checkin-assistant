package com.checkinassistant.data

/** 备份测试共用的数据。所有可空字段都分别有「有值」和「为空」两种。 */
object BackupFixtures {
    fun fullPoint() = CheckInPoint(
        id = 7, name = "公司 \"东门\"\n😀", latitude = 3.139012345678901, longitude = 101.68690123456789,
        radiusMeters = 150, dwellSeconds = 25, enabled = true, triggerState = PointState.COMPLETED.name,
        enteredAt = 1_763_000_000_123L, activeStartMinute = 480, activeEndMinute = 570,
        dailyTriggerLimit = 2, color = -16_738_680, repeatMode = "ONCE", activeDays = 31,
        onceDate = "2026-11-20", onceStatus = "PENDING", completedAt = 1_763_100_000_000L,
        skipUntilDate = "2026-11-15", lastTriggeredWindowStart = 1_763_050_000_000L
    )

    fun nullPoint() = CheckInPoint(id = 8, name = "宿舍", latitude = -90.0, longitude = 180.0, enabled = false)

    fun records() = listOf(
        CheckInRecord(
            id = 3, pointId = 7, pointName = "公司", triggeredAt = 1_763_000_000_000L,
            year = 2026, month = 11, day = 13, weekday = 6, hour = 9, minute = 5, second = 30,
            status = RecordStatus.COMPLETED.name, reminderCount = 2, actionAt = 1_763_000_100_000L, simulated = true
        ),
        CheckInRecord(
            id = 9, pointId = 99, pointName = "已删除的点", triggeredAt = 1_763_100_000_000L,
            year = 2026, month = 11, day = 14, weekday = 7, hour = 8, minute = 0, second = 0,
            status = RecordStatus.UNCONFIRMED.name
        )
    )

    fun data(settings: BackupSettings? = BackupSettings("content://media/internal/audio/media/12", 1, "AMAP")) = BackupData(
        points = listOf(fullPoint(), nullPoint()),
        records = records(),
        dayNotes = listOf(DayNote("2026-11-13", "休息，考勤算上班 \"引号\"\n第二行", 1_763_000_000_000L)),
        skipEvents = listOf(PointSkipEvent(id = 4, reminderPointId = 7, skipDate = "2026-11-12", createdAt = 1_762_900_000_000L)),
        enabledEvents = listOf(PointEnabledEvent(id = 5, pointId = 8, atMillis = 1_762_800_000_000L, enabled = false)),
        reminderStates = listOf(
            ReminderState(pointId = 7, recordId = 3, reminderStartTime = 1_763_000_000_000L, reminderCount = 2, snoozeUntil = 1_763_000_600_000L, active = false, simulated = true),
            ReminderState(pointId = 8, recordId = 9, reminderStartTime = 1_763_100_000_000L, reminderCount = 1)
        ),
        settings = settings
    )

    val META = ExportMeta(exportedAt = 1_763_200_000_000L, appVersionName = "2.2.25", appVersionCode = 48, dbVersion = 10)

    /** v2.2.24 BackupExporter 实际会产出的样子：空值的键被省略、含调试日志、schema 是随机数。 */
    val LEGACY_V2224 = """
        {
          "app": "checkin-assistant",
          "schema": 123456789,
          "exportedAt": 1763000000000,
          "points": [
            {"id": 1, "name": "公司", "latitude": 3.139, "longitude": 101.6869, "radiusMeters": 200,
             "repeatMode": "WEEKLY", "activeDays": 31, "activeStartMinute": 480, "activeEndMinute": 570, "enabled": true},
            {"id": 2, "name": "机场", "latitude": 2.7456, "longitude": 101.7099, "radiusMeters": 500,
             "repeatMode": "ONCE", "onceStatus": "PENDING", "activeDays": 127, "enabled": false}
          ],
          "records": [
            {"pointId": 1, "pointName": "公司", "triggeredAt": 1794531600000, "status": "COMPLETED"},
            {"pointId": 0, "pointName": "诊断", "triggeredAt": 1794531601000, "status": "DEBUG"},
            {"pointId": 1, "pointName": "公司", "triggeredAt": 1794445200000, "status": "IGNORED"}
          ],
          "dayNotes": [ {"date": "2026-11-12", "note": "休息"} ]
        }
    """.trimIndent()
}
