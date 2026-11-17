package com.checkinassistant.ui

import com.checkinassistant.data.RecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 问题 2：一次性提醒点完成后，长按菜单里要有「恢复」。PointAdapter 弹菜单用的就是这个函数。 */
class PointMenuTest {
    @Test
    fun completedOnce_hasRestore() {
        assertEquals(
            listOf("今天跳过", "恢复", "删除"),
            PointMenu.items("ONCE", "COMPLETED", RecordStatus.COMPLETED.name)
        )
        assertEquals(listOf("今天跳过", "恢复", "删除"), PointMenu.items("ONCE", "COMPLETED", null))
    }

    @Test
    fun completedOnceUnacknowledged_hasAcknowledgeThenRestore() {
        val items = PointMenu.items("ONCE", "COMPLETED", RecordStatus.IGNORED.name)
        assertEquals(listOf("今天跳过", "确认已处理", "恢复", "删除"), items)
        assertTrue(items.indexOf(PointMenu.RESTORE) < items.indexOf(PointMenu.DELETE))
    }

    @Test
    fun pendingOnceAndRepeating_noRestore() {
        val plain = listOf("今天跳过", "删除")
        assertEquals(plain, PointMenu.items("ONCE", "PENDING", null))
        assertEquals(plain, PointMenu.items("ONCE", "PENDING", RecordStatus.IGNORED.name))
        assertEquals(plain, PointMenu.items("DAILY", null, RecordStatus.IGNORED.name))
        assertEquals(plain, PointMenu.items("WEEKLY", null, RecordStatus.COMPLETED.name))
        // 重复的点即使残留了 COMPLETED 字样也不出「恢复」
        assertEquals(plain, PointMenu.items("DAILY", "COMPLETED", null))
    }
}
