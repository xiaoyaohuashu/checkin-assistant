package com.checkinassistant.reminder

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView

/**
 * v5 任务一: wheel-style time picker (two columns, hour 0-23 + minute 0-59).
 * Replaces the old TimePickerDialog for start/end time selection.
 */
class TimeWheelDialog(
    context: Context,
    initialMinutes: Int?,
    private val onPicked: (hour: Int, minute: Int) -> Unit
) {
    private val dialog: AlertDialog

    init {
        val ctx = context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 16), dp(ctx, 24), dp(ctx, 16))
        }
        root.addView(TextView(ctx).apply {
            text = "选择时间"; textSize = 16f; setTextColor(Color.rgb(31, 41, 55))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(ctx, 12))
        })

        val hour = NumberPicker(ctx).apply {
            minValue = 0; maxValue = 23
            value = (initialMinutes ?: 8 * 60) / 60
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val minute = NumberPicker(ctx).apply {
            minValue = 0; maxValue = 59
            value = (initialMinutes ?: 8 * 60) % 60
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val colon = TextView(ctx).apply {
            text = ":"; textSize = 22f; setTextColor(Color.rgb(31, 41, 55)); gravity = Gravity.CENTER
        }
        val wheelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(hour, LinearLayout.LayoutParams(dp(ctx, 72), dp(ctx, 140)))
            addView(colon, LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 140)))
            addView(minute, LinearLayout.LayoutParams(dp(ctx, 72), dp(ctx, 140)))
        }
        root.addView(wheelRow)

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, dp(ctx, 12), 0, 0)
        }
        val cancel = Button(ctx).apply { text = "取消"; setBackgroundColor(Color.TRANSPARENT) }
        val ok = Button(ctx).apply { text = "确定"; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.rgb(37, 99, 235)) }
        buttonRow.addView(cancel); buttonRow.addView(ok)
        root.addView(buttonRow)

        dialog = AlertDialog.Builder(ctx).setView(root).create()
        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener {
            onPicked(hour.value, minute.value)
            dialog.dismiss()
        }
    }

    fun show() = dialog.show()

    companion object {
        private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    }
}
