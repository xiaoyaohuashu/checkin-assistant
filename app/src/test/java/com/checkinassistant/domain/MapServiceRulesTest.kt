package com.checkinassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapServiceRulesTest {
    private val bools = listOf(true, false)

    /** 关键：有 Google Play 服务 + 不在大陆 + 自动 → 一定是谷歌、不提示、不问隐私（= v2.2.24 的行为）。 */
    @Test fun autoWithGmsOutsideMainlandIsAlwaysPlainGoogle() {
        for (key in bools) for (p in AmapPrivacy.values()) {
            assertEquals(MapDecision.Use(MapBackend.GOOGLE, null), MapServiceRules.decide(MapServiceSetting.AUTO, true, false, key, p))
        }
    }

    @Test fun alwaysGoogleWithGmsIsPlainGoogleEverywhere() {
        for (inCn in bools) for (key in bools) for (p in AmapPrivacy.values()) {
            assertEquals(MapDecision.Use(MapBackend.GOOGLE, null), MapServiceRules.decide(MapServiceSetting.GOOGLE, true, inCn, key, p))
        }
    }

    /** 穷举 3×2×2×2×3 种组合，逐条对照需求规则。 */
    @Test fun exhaustiveTable() {
        for (s in MapServiceSetting.values()) for (gms in bools) for (inCn in bools) for (key in bools) for (p in AmapPrivacy.values()) {
            val d = MapServiceRules.decide(s, gms, inCn, key, p)
            val wantAmap = when (s) { MapServiceSetting.AUTO -> !gms || inCn; MapServiceSetting.GOOGLE -> !gms; MapServiceSetting.AMAP -> true }
            val label = "$s gms=$gms cn=$inCn key=$key $p → $d"
            when {
                !wantAmap -> assertEquals(label, MapDecision.Use(MapBackend.GOOGLE, null), d)
                !key -> { assertTrue(label, d is MapDecision.Use && d.backend == MapBackend.GOOGLE && d.notice!!.contains("未配置高德 Key")) }
                p == AmapPrivacy.UNKNOWN -> assertEquals(label, MapDecision.AskPrivacy, d)
                p == AmapPrivacy.DECLINED -> {
                    assertTrue(label, d is MapDecision.Use && d.backend == MapBackend.GOOGLE)
                    assertEquals(label, !gms, (d as MapDecision.Use).notice?.contains("地图和定位不可用") == true)
                }
                else -> {
                    assertTrue(label, d is MapDecision.Use && d.backend == MapBackend.AMAP)
                    assertEquals(label, s == MapServiceSetting.GOOGLE, (d as MapDecision.Use).notice != null)
                }
            }
        }
    }

    @Test fun parsing() {
        assertEquals(MapServiceSetting.AUTO, MapServiceRules.parseSetting(null))
        assertEquals(MapServiceSetting.AUTO, MapServiceRules.parseSetting("x"))
        assertEquals(MapServiceSetting.AMAP, MapServiceRules.parseSetting("AMAP"))
        assertEquals(AmapPrivacy.UNKNOWN, MapServiceRules.parsePrivacy(null))
        assertEquals(AmapPrivacy.DECLINED, MapServiceRules.parsePrivacy("DECLINED"))
    }

    @Test fun lastKnownAge() {
        assertTrue(MapServiceRules.usableLastKnown(0))
        assertTrue(MapServiceRules.usableLastKnown(6 * 3600_000L))
        assertFalse(MapServiceRules.usableLastKnown(6 * 3600_000L + 1))
        assertFalse(MapServiceRules.usableLastKnown(-1))
    }

    /** v2.2.26（中 2）：只有 SUCCESS(0) 算有；没装(1)、需要更新(2)、被停用(3)、无效(9)、正在更新(18)、权限缺失(19) 等一律算没有。 */
    @Test fun playServicesResultCodes() {
        assertTrue(PlayServicesRules.isInstalled(0))
        for (code in listOf(1, 2, 3, 9, 18, 19, 99, -1)) assertFalse("code=$code", PlayServicesRules.isInstalled(code))
    }
}
