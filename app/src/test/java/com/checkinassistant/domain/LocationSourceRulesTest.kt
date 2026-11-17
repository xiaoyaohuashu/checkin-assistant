package com.checkinassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSourceRulesTest {
    private val bools = listOf(true, false)

    /** 3×2×2 全枚举，逐条对照规则。 */
    @Test fun pick_exhaustive() {
        for (s in MapServiceSetting.values()) for (gms in bools) for (amap in bools) {
            val expected = when {
                s == MapServiceSetting.AMAP && amap -> LocationSource.AMAP
                gms -> LocationSource.GOOGLE
                amap -> LocationSource.AMAP
                else -> LocationSource.GOOGLE
            }
            assertEquals("$s gms=$gms amap=$amap", expected, LocationSourceRules.pick(s, gms, amap))
        }
    }

    /** 「自动」「始终谷歌」+ 有 Google Play 服务：一定是谷歌，不管高德能不能用（后台定位规则不变）。 */
    @Test fun autoAndGoogleWithGmsAlwaysUseGoogle() {
        for (s in listOf(MapServiceSetting.AUTO, MapServiceSetting.GOOGLE)) for (amap in bools) {
            assertEquals(LocationSource.GOOGLE, LocationSourceRules.pick(s, hasGms = true, amapUsable = amap))
            assertFalse(LocationSourceRules.needsDynamic(s, hasGms = true))
        }
    }

    @Test fun alwaysAmap_usesAmapWhenUsable_elseFallsBack() {
        for (gms in bools) assertEquals(LocationSource.AMAP, LocationSourceRules.pick(MapServiceSetting.AMAP, gms, amapUsable = true))
        for (gms in bools) assertEquals(LocationSource.GOOGLE, LocationSourceRules.pick(MapServiceSetting.AMAP, gms, amapUsable = false))
    }

    /** 高德不可用（没 Key、没同意、撤回了同意）时，任何情况下都不会选高德。 */
    @Test fun neverAmapWhenNotUsable() {
        for (s in MapServiceSetting.values()) for (gms in bools) {
            assertEquals(LocationSource.GOOGLE, LocationSourceRules.pick(s, gms, amapUsable = false))
        }
    }

    @Test fun needsDynamic_table() {
        for (s in MapServiceSetting.values()) for (gms in bools) {
            assertEquals("$s gms=$gms", s == MapServiceSetting.AMAP || !gms, LocationSourceRules.needsDynamic(s, gms))
        }
    }

    /** 只有「有 Google Play 服务 + 从别的选项切到始终高德」才重建后台服务。 */
    @Test fun needsServiceRecreate_exhaustive() {
        for (old in MapServiceSetting.values()) for (new in MapServiceSetting.values()) for (gms in bools) {
            val expected = gms && old != MapServiceSetting.AMAP && new == MapServiceSetting.AMAP
            assertEquals("$old→$new gms=$gms", expected, LocationSourceRules.needsServiceRecreate(old, new, gms))
        }
        assertTrue(LocationSourceRules.needsServiceRecreate(MapServiceSetting.AUTO, MapServiceSetting.AMAP, true))
        assertFalse(LocationSourceRules.needsServiceRecreate(MapServiceSetting.AMAP, MapServiceSetting.AUTO, true))
        assertFalse(LocationSourceRules.needsServiceRecreate(MapServiceSetting.AUTO, MapServiceSetting.AMAP, false))
    }

    /** v2.2.26（中 5）：3×2×2×3 全枚举。 */
    @Test fun block_exhaustive() {
        for (s in MapServiceSetting.values()) for (gms in bools) for (key in bools) for (p in AmapPrivacy.values()) {
            val expected = when {
                s != MapServiceSetting.AMAP && gms -> LocationBlock.NONE
                !key -> if (gms) LocationBlock.AMAP_OFF_USING_GOOGLE else LocationBlock.AMAP_KEY_MISSING_NO_GMS
                p == AmapPrivacy.AGREED -> LocationBlock.NONE
                p == AmapPrivacy.UNKNOWN -> LocationBlock.AMAP_PRIVACY_NEEDED
                gms -> LocationBlock.AMAP_OFF_USING_GOOGLE
                else -> LocationBlock.AMAP_PRIVACY_NEEDED
            }
            val label = "$s gms=$gms key=$key $p"
            val block = LocationSourceRules.block(s, gms, key, p)
            assertEquals(label, expected, block)
            // 有原因就一定有文字，没原因就一定没文字
            assertEquals(label, block == LocationBlock.NONE, LocationSourceRules.blockText(block, gms, key) == null)
        }
    }

    /** 谷歌这条路（有 Google Play 服务、自动或始终谷歌）：卡片永远没有原因，也永远不发通知。 */
    @Test fun googlePathNeverShowsAReason() {
        for (s in listOf(MapServiceSetting.AUTO, MapServiceSetting.GOOGLE)) for (key in bools) for (p in AmapPrivacy.values()) {
            assertEquals(LocationBlock.NONE, LocationSourceRules.block(s, hasGms = true, keyConfigured = key, privacy = p))
        }
    }

    @Test fun blockText_saysWhatIsWrong() {
        assertTrue(LocationSourceRules.blockText(LocationBlock.AMAP_PRIVACY_NEEDED, hasGms = false, keyConfigured = true)!!.contains("后台定位不可用"))
        assertTrue(LocationSourceRules.blockText(LocationBlock.AMAP_PRIVACY_NEEDED, hasGms = true, keyConfigured = true)!!.contains("仍用谷歌"))
        assertTrue(LocationSourceRules.blockText(LocationBlock.AMAP_KEY_MISSING_NO_GMS, hasGms = false, keyConfigured = false)!!.contains("高德 Key"))
        assertTrue(LocationSourceRules.blockText(LocationBlock.AMAP_OFF_USING_GOOGLE, hasGms = true, keyConfigured = false)!!.contains("高德 Key"))
        assertTrue(LocationSourceRules.blockText(LocationBlock.AMAP_OFF_USING_GOOGLE, hasGms = true, keyConfigured = true)!!.contains("没有同意"))
    }

    @Test fun shouldNotify_onceEvery24Hours() {
        val day = 24 * 60 * 60 * 1000L
        assertTrue(LocationSourceRules.shouldNotify(lastAtMs = 0L, nowMs = 1_800_000_000_000L)) // 从没发过
        assertFalse(LocationSourceRules.shouldNotify(lastAtMs = 1_000_000L, nowMs = 1_000_000L)) // 刚发过（撤回同意时也这样记）
        assertFalse(LocationSourceRules.shouldNotify(lastAtMs = 1_000_000L, nowMs = 1_000_000L + day - 1))
        assertTrue(LocationSourceRules.shouldNotify(lastAtMs = 1_000_000L, nowMs = 1_000_000L + day))
        assertTrue(LocationSourceRules.shouldNotify(lastAtMs = 2_000_000L, nowMs = 1_000_000L)) // 手机时间被往回调
    }
}
