package com.checkinassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 问题 1：手动停止后，只有用户在首页点「启动」才允许启动。 */
class MonitorStartRulesTest {
    @Test fun stopped_onlyUserButtonMayStart() {
        assertEquals(false, MonitorStartRules.mayStart(userStopped = true, byUser = false))
        assertEquals(true, MonitorStartRules.mayStart(userStopped = true, byUser = true))
    }

    @Test fun notStopped_anyoneMayStart() {
        assertEquals(true, MonitorStartRules.mayStart(userStopped = false, byUser = false))
        assertEquals(true, MonitorStartRules.mayStart(userStopped = false, byUser = true))
    }
}
