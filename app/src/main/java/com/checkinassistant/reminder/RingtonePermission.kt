package com.checkinassistant.reminder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 2.2.21：自选铃声（手机里自己加的歌）要「音乐和音频」权限 App 才能自己播放。
 * 不再交给系统铃声播放器代放：那个播放器在系统进程里，App 崩了它不会停（2.2.20 事故）。
 */
object RingtonePermission {
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** 本进程里已经弹过一次，拒绝后不反复弹。 */
    @Volatile var askedThisProcess = false

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 选定铃声是媒体库里的文件，且还没授权。 */
    fun needed(context: Context): Boolean {
        val saved = context.getSharedPreferences("reminder_settings", Context.MODE_PRIVATE)
            .getString("ringtone_uri", null)?.takeIf { it.isNotBlank() } ?: return false
        val uri = Uri.parse(saved)
        return uri.scheme == "content" && uri.authority == "media" && !granted(context)
    }
}
