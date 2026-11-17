package com.checkinassistant.ui

/**
 * 首页长按提醒点弹出的菜单有哪几项。单独放在这里（不依赖界面）是为了能用单元测试直接测。
 */
internal object PointMenu {
    const val SKIP = "今天跳过"
    const val ACKNOWLEDGE = "确认已处理"
    const val RESTORE = "恢复"
    const val DELETE = "删除"

    /**
     * 顺序：今天跳过 →（一次性、已完成、但最近一条记录是「未理会」时）确认已处理
     * →（一次性且已完成时，v2.2.24 新增，问题 2）恢复 → 删除。
     *
     * @param latestRecordStatus 这个点最近一条到达记录的状态，没有记录传 null。
     */
    fun items(repeatMode: String, onceStatus: String?, latestRecordStatus: String?): List<String> {
        val completedOnce = repeatMode == "ONCE" && onceStatus == "COMPLETED"
        return buildList {
            add(SKIP)
            if (completedOnce && latestRecordStatus == "IGNORED") add(ACKNOWLEDGE)
            if (completedOnce) add(RESTORE)
            add(DELETE)
        }
    }
}
