package com.checkinassistant.data

/**
 * v2.2.25：导入流程碰到的所有外部东西（数据库、文件、设置、提醒、后台监测、日志）都在这个接口后面，
 * 这样 [BackupImporter] 的先后顺序和失败分支能在电脑上用假实现测。真机实现见 [AndroidBackupStore]。
 */
interface BackupStore {
    /** 一个读事务：6 张表（记录不含调试日志）+ 设置。 */
    suspend fun readSnapshot(): BackupData
    /** 把 json 存成撤销文件，返回文件名；失败抛异常。 */
    suspend fun writeUndoFile(json: String, kind: UndoKind): String
    /** 一个写事务：清空 6 张表，再插入 5 张表（提醒状态不插）。出错原样抛出，事务回滚。 */
    suspend fun replaceAll(data: BackupData)
    /** 写设置，返回要告诉用户的提示。 */
    fun applySettings(settings: BackupSettings): List<String>
    /** 停掉这些提醒点的响铃、闹钟和通知。 */
    fun silenceReminders(pointIds: Set<Long>)
    /** 重新安排后台监测；用户手动停止过就保持停止。 */
    fun restartMonitoring(): MonitorOutcome
    suspend fun log(message: String)
    fun exportMeta(): ExportMeta
}
