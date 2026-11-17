package com.checkinassistant.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFlightTest {
    private var starts = 0
    private var stops = 0
    private val flight = SharedFlight<String>(start = { starts++ }, stop = { stops++ })
    private val got = mutableListOf<String>()
    private fun join(name: String) = flight.join({ got += "$name=$it" }, { got += "$name!${it.message}" })

    @Test fun twoJoinersShareOneFlight() {
        join("a"); join("b")
        assertEquals(1, starts)
        assertTrue(flight.active)
        flight.deliver("x")
        assertEquals(listOf("a=x", "b=x"), got)
        assertEquals(1, stops)
        assertFalse(flight.active)
    }

    @Test fun onlyLastCancelStops() {
        val cancelA = join("a")
        val cancelB = join("b")
        cancelA()
        assertEquals(0, stops)
        assertTrue(flight.active)
        cancelB()
        assertEquals(1, stops)
        assertFalse(flight.active)
        cancelB(); cancelA() // 重复取消无害
        assertEquals(1, stops)
        flight.deliver("late") // 没人等了，什么都不发生
        assertTrue(got.isEmpty())
        assertEquals(1, stops)
    }

    @Test fun cancelledJoinerGetsNothing() {
        val cancelA = join("a"); join("b")
        cancelA()
        flight.deliver("x")
        assertEquals(listOf("b=x"), got)
    }

    @Test fun afterFinishANewJoinStartsAgain() {
        join("a"); flight.fail(IllegalStateException("boom"))
        assertEquals(listOf("a!boom"), got)
        assertEquals(1, stops)
        join("b")
        assertEquals(2, starts)
        flight.deliver("y")
        assertEquals(listOf("a!boom", "b=y"), got)
    }

    @Test fun startFailureIsDeliveredAsError() {
        val broken = SharedFlight<String>(start = { throw IllegalStateException("no client") }, stop = { stops++ })
        broken.join({ got += "ok" }, { got += "err:${it.message}" })
        assertEquals(listOf("err:no client"), got)
        assertFalse(broken.active)
    }

    @Test fun joiningFromInsideACallbackStartsANewFlight() {
        flight.join({ got += "first=$it"; join("second") }, {})
        flight.deliver("x")
        assertEquals(2, starts)
        assertTrue(flight.active)
        flight.deliver("y")
        assertEquals(listOf("first=x", "second=y"), got)
    }

    @Test fun oneBadCallbackDoesNotBlockOthers() {
        flight.join({ throw IllegalStateException("bad listener") }, {})
        join("b")
        flight.deliver("x")
        assertEquals(listOf("b=x"), got)
    }

    /** 已知限制（用测试固定下来）：后并入的人和先到的人一起收到失败。 */
    @Test fun lateJoinerInheritsTheFailure_knownLimitation() {
        join("early"); join("late")
        flight.fail(IllegalStateException("timeout"))
        assertEquals(listOf("early!timeout", "late!timeout"), got)
    }
}
