package com.checkinassistant.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 问题 22：删除提醒点时五步收尾一步不少、顺序固定。（真的停没停响铃要真机确认，这里只证明五步都会被调用。） */
class PointCleanupTest {
    @Test
    fun deletePoint_runsAllFiveStepsInOrder() {
        val calls = mutableListOf<String>()
        runBlocking {
            PointCleanup.deletePoint(
                deleteRow = { calls += "删除这一行" },
                cancelAlarmsAndAlert = { calls += "取消闹钟并停响铃" },
                dismissNotifications = { calls += "清通知" },
                deactivateReminder = { calls += "关掉提醒进行中状态" },
                reevaluateMonitoring = { calls += "重新安排后台监测" },
            )
        }
        assertEquals(
            listOf("删除这一行", "取消闹钟并停响铃", "清通知", "关掉提醒进行中状态", "重新安排后台监测"),
            calls
        )
    }
}
