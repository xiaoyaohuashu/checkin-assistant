package com.checkinassistant.reminder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.checkinassistant.R
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.CheckInPoint
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.data.PointState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v4 §2: alarm-style full-screen reminder, shown over the lock screen.
 *
 * Ringer/vibration reuse ReminderEngine's process-wide alert session so the two
 * paths never overlap; the user's action stops the loop and routes through
 * ReminderEngine.finish / snooze exactly like the notification actions.
 *
 * v2.2.14：本页有独立的 taskAffinity（manifest），关闭时只用 finish()。以前它和主界面同在一个
 * 任务里，点按钮后 finishAndRemoveTask() 把整个任务移除，触发了前台服务的 onTaskRemoved
 * （日志里的 MONITOR_SERVICE_TASK_REMOVED，用户并没有划掉 App）。
 */
class FullscreenReminderActivity : AppCompatActivity() {

    private var pointId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pointId = intent.getLongExtra(ReminderEngine.EXTRA_POINT_ID, -1L)

        // v2.2.28：真正全屏——内容画到系统栏和刘海区域下面，系统栏隐藏，从边缘滑动可临时呼出。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Show over the lock screen and keep the screen on (spec §2.3).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val point = intent.getStringExtra(EXTRA_POINT_NAME).orEmpty()
        val count = intent.getIntExtra(EXTRA_ROUND, 1)

        // v2.2.28：界面改为 XML 布局（只改外观），两个按钮仍分别调用 confirm() / close()。
        setContentView(R.layout.activity_fullscreen_reminder)
        findViewById<TextView>(R.id.reminder_title).text =
            getString(R.string.reminder_title, point.ifEmpty { getString(R.string.reminder_default_point) })
        findViewById<TextView>(R.id.reminder_subtitle).text =
            getString(R.string.reminder_subtitle, count, ReminderEngine.MAX_REMINDERS)
        findViewById<Button>(R.id.reminder_arrive).setOnClickListener { confirm() }
        findViewById<Button>(R.id.reminder_snooze).setOnClickListener { close() }
        hideSystemBars()

        // The engine's startAlert loop is already running for this point (the
        // notification path starts it); nothing extra to launch here.
        CoroutineScope(Dispatchers.IO).launch {
            DiagnosticRecordLogger.geofence(this@FullscreenReminderActivity, "FULLSCREEN_SHOWN：#$pointId $point round=$count")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** v2.2.28：隐藏状态栏和导航栏；用户从边缘滑动时临时显示，过一会儿自动收起。 */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun confirm() {
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            ReminderEngine.stopAlert(app, pointId)
            ReminderEngine.complete(app, pointId)
            DiagnosticRecordLogger.geofence(app, "FULLSCREEN_ACTION：#$pointId 我已到达")
        }
        finish()
    }

    private fun close() {
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            ReminderEngine.stopAlert(app, pointId)
            ReminderEngine.snooze(app, pointId)
            DiagnosticRecordLogger.geofence(app, "FULLSCREEN_ACTION：#$pointId 稍后提醒")
        }
        finish()
    }

    companion object {
        const val EXTRA_POINT_NAME = "point_name"
        const val EXTRA_ROUND = "round"

        fun intent(context: Context, pointId: Long, pointName: String, round: Int): Intent =
            Intent(context, FullscreenReminderActivity::class.java)
                .setAction("com.checkinassistant.FULLSCREEN_REMINDER")
                .putExtra(ReminderEngine.EXTRA_POINT_ID, pointId)
                .putExtra(EXTRA_POINT_NAME, pointName)
                .putExtra(EXTRA_ROUND, round)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
