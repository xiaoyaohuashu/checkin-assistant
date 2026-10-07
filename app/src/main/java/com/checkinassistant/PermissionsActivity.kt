package com.checkinassistant

import android.Manifest
import android.app.NotificationManager
import android.app.AlarmManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.checkinassistant.reminder.ReminderEngine

class PermissionsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    override fun onCreate(state: Bundle?) { super.onCreate(state); container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,24,32,24) }; setContentView(ScrollView(this).apply { addView(container) }) }
    override fun onResume() { super.onResume(); if (::container.isInitialized) render() }
    private fun render() {
        container.removeAllViews(); container.addView(TextView(this).apply { text = "权限自检"; textSize = 25f })
        container.addView(TextView(this).apply { text = "识别到的设备品牌：${Build.MANUFACTURER} / ${Build.BRAND}"; setPadding(0,8,0,18) })
        addRow("定位权限", if (has(Manifest.permission.ACCESS_FINE_LOCATION) && hasBackgroundLocation()) "已开启" else "未开启", "需要精确定位；后台围栏还需在系统页面选择“始终允许”。") { requestLocation() }
        addRow("通知权限", if (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)) "已开启" else "未开启", "用于到达提醒和已连接荣耀手环的系统通知同步。") { requestNotifications() }
        val power = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        addRow("电池优化", if (power) "已开启豁免" else "未开启", "请允许不受电池优化限制。") { openSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        val exactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        addRow("精确闹钟", if (exactAlarm) "已开启" else "未开启", "25 秒定位复核必需；请在“闹钟和提醒”中允许本 App。") { requestExactAlarm() }
        // v4 §2.2: full-screen reminder permission guide (Android 14+ gates it off by default;
        // when missing the reminder degrades to a heads-up banner automatically).
        val fullscreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        addRow("全屏提醒", if (fullscreen) "已开启" else "未开启", "锁屏全屏弹出提醒（像闹钟）；未开启时降级为横幅通知。") { requestFullScreenIntent() }
        val dnd = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        addRow("勿扰模式通知权限", if (dnd) "已开启" else "未开启", "渠道已请求绕过勿扰；仍须在系统中允许本 App。") { openSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        addRow("后台 / 自启动", "无法由系统 API 自动检测，请手动确认", vendorGuide()) { openVendorOrAppSettings() }
        addRow("后台监测服务", "请在主页面点击“启动后台监测”", "服务会显示“别错过站正在后台运行”。") { openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
    private fun addRow(title: String, status: String, detail: String, action: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,18,0,18) }
        box.addView(TextView(this).apply { text = "$title：$status"; textSize = 17f })
        box.addView(TextView(this).apply { text = detail; textSize = 13f; setPadding(0,6,0,8) })
        box.addView(Button(this).apply { text = "去设置"; setOnClickListener { action() } }); container.addView(box)
    }
    private fun requestLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
        else if (Build.VERSION.SDK_INT == 29 && !hasBackgroundLocation()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 12)
        else openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
    private fun requestNotifications() { if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11) }

    /** v4 §2.2: guide the user to grant USE_FULL_SCREEN_INTENT (Android 14+ gate). */
    private fun requestFullScreenIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            openSafely(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        } else {
            openVendorOrAppSettings()
        }
    }
    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }
    private fun has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun hasBackgroundLocation() = Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    private fun openVendorOrAppSettings() {
        val manufacturer = (Build.MANUFACTURER + Build.BRAND).lowercase(); val intent = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName)
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") -> Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> Intent("com.vivo.permissionmanager").setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            else -> null
        }; if (intent != null) openSafely(intent) else openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
    private fun openSafely(intent: Intent) { try { startActivity(intent) } catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {} } }
    private fun vendorGuide(): String { val v = (Build.MANUFACTURER + Build.BRAND).lowercase(); return when { v.contains("xiaomi") || v.contains("redmi") -> "设置 → 应用设置 → 应用管理 → 本 App → 省电策略 → 无限制；安全中心 → 自启动管理。"; v.contains("huawei") || v.contains("honor") -> "设置 → 电池 → 更多电池设置 → 应用启动管理 → 本 App；开启自启动、关联启动、后台活动。"; v.contains("oppo") || v.contains("oneplus") -> "设置 → 电池 → 应用耗电管理 → 本 App → 允许后台运行；设置 → 应用管理 → 自启动。"; v.contains("vivo") || v.contains("iqoo") -> "设置 → 电池 → 后台高耗电 → 允许；i管家 → 应用管理 → 自启动管理。"; else -> "请允许后台定位、通知、忽略电池优化和后台运行。" } }
}
