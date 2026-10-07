package com.checkinassistant.reminder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
 */
class FullscreenReminderActivity : AppCompatActivity() {

    private var pointId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pointId = intent.getLongExtra(ReminderEngine.EXTRA_POINT_ID, -1L)

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

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF101418.toInt())
        }
        val title = TextView(this).apply {
            text = "到达${point.ifEmpty { "提醒点" }}，请确认"
            textSize = 26f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "提醒 $count/${ReminderEngine.MAX_REMINDERS}"
            textSize = 14f; setTextColor(0xFF9AA3AD.toInt()); gravity = Gravity.CENTER
            setPadding(0, 24, 0, 64)
        }
        val arrive = Button(this).apply {
            text = "我已到达"; textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 48f; setColor(0xFF2563EB.toInt())
            }
            setOnClickListener { confirm() }
        }
        val snooze = Button(this).apply {
            text = "稍后提醒"; textSize = 16f
            setTextColor(0xFFCBD3DC.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 48f; setColor(0x22FFFFFF)
            }
            setOnClickListener { close() }
        }
        root.addView(title)
        root.addView(subtitle, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(arrive, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { setMargins(0, 0, 0, dp(16)) })
        root.addView(snooze, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ))
        setContentView(root)

        // The engine's startAlert loop is already running for this point (the
        // notification path starts it); nothing extra to launch here.
        CoroutineScope(Dispatchers.IO).launch {
            DiagnosticRecordLogger.geofence(this@FullscreenReminderActivity, "FULLSCREEN_SHOWN：#$pointId $point round=$count")
        }
    }

    private fun confirm() {
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            ReminderEngine.stopAlert(app, pointId)
            ReminderEngine.complete(app, pointId)
            DiagnosticRecordLogger.geofence(app, "FULLSCREEN_ACTION：#$pointId 我已到达")
        }
        finishAndRemoveTask()
    }

    private fun close() {
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            ReminderEngine.stopAlert(app, pointId)
            ReminderEngine.snooze(app, pointId)
            DiagnosticRecordLogger.geofence(app, "FULLSCREEN_ACTION：#$pointId 稍后提醒")
        }
        finishAndRemoveTask()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
