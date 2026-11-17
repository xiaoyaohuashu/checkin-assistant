package com.checkinassistant.domain

/**
 * 后台监测能不能启动（v2.2.24，问题 1）。
 *
 * 规则只有一条：用户在首页手动点过「停止后台监测」之后，只有用户自己在首页点「启动」才能再启动；
 * 重启手机、App 升级、时间段闹钟到点、改提醒点、各种兜底检查，都不许把它拉起来。
 */
internal object MonitorStartRules {
    /**
     * @param userStopped 用户是否手动停止过（还没再点启动）
     * @param byUser 这次启动是不是用户在首页点「启动」按钮
     */
    fun mayStart(userStopped: Boolean, byUser: Boolean): Boolean = byUser || !userStopped
}
