package com.checkinassistant.domain

/**
 * 删除提醒点时的收尾顺序（v2.2.24，问题 22）。这里只管「做哪几步、谁先谁后」，
 * 真正干活的五个动作由调用方（PointActions.delete）传进来，所以这个文件不碰任何安卓接口，可以直接单测。
 *
 * 顺序的理由：
 * 1. 先删提醒点这一行：之后就算有一条闹钟抢在取消之前响了，提醒流程也查不到这个点，会自己收尾，不会再响新的一轮；
 * 2. 取消它排着的闹钟（下一轮提醒 / 25 秒复核），并停掉正在响的铃声和振动；
 * 3. 清掉它的通知；
 * 4. 把它的「提醒进行中」状态关掉（重启手机时就不会被当成没响完的提醒再恢复）；
 * 5. 最后让后台监测按剩下的提醒点重新安排。
 */
internal object PointCleanup {
    suspend fun deletePoint(
        deleteRow: suspend () -> Unit,
        cancelAlarmsAndAlert: () -> Unit,
        dismissNotifications: () -> Unit,
        deactivateReminder: suspend () -> Unit,
        reevaluateMonitoring: () -> Unit,
    ) {
        deleteRow()
        cancelAlarmsAndAlert()
        dismissNotifications()
        deactivateReminder()
        reevaluateMonitoring()
    }
}
