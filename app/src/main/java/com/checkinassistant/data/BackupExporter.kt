package com.checkinassistant.data

import android.content.Context

/**
 * 完整数据导出（v2.2.25 起：全部字段 + 跳过/启停记录 + 提醒状态 + 设置，格式见 [BackupCodec]）。
 * 只读，不改数据库。调试日志不导出（在「调试日志」页单独导出）。
 */
object BackupExporter {

    suspend fun exportAll(context: Context): String {
        val store = AndroidBackupStore(context.applicationContext)
        return BackupCodec.encode(store.readSnapshot(), store.exportMeta())
    }
}
