package com.checkinassistant.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.checkinassistant.data.DiagnosticRecordLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 到达提醒的声音（v2.2.18 起由 App 自己播放，通知渠道本身静音）。
 *
 * - 连着蓝牙耳机（A2DP / BLE 耳机 / 助听器）：USAGE_MEDIA + setPreferredDevice 指定耳机，
 *   只在耳机里响；媒体音量过低时临时调高，停止时恢复。
 * - 没连耳机：USAGE_ALARM 外放。
 * - 响铃中耳机断开（AudioDeviceCallback）：自动切成外放继续响。
 * - 振动模式（2.2.20 起）：连着蓝牙耳机就照样在耳机里响（USAGE_MEDIA 不受振动模式静音）；
 *   没连耳机不出声，响铃中耳机断开就停声，不会外放。
 * - 静音模式：不出声。
 *
 * 振动不在这里处理，仍由 [ReminderEngine] 负责。
 */
internal class AlertSoundPlayer(
    private val context: Context,
    private val pointId: Long,
    private val uri: Uri?,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var headsetDevice: AudioDeviceInfo? = null
    /** 临时调高媒体音量前的原值；null 表示没有调过。 */
    private var savedMusicVolume: Int? = null
    private var raisedMusicVolume: Int? = null
    private var deviceCallbackRegistered = false
    /** 振动模式：只允许走耳机，绝不外放。 */
    private var headsetOnly = false
    @Volatile private var released = false

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            synchronized(this@AlertSoundPlayer) {
                val current = headsetDevice ?: return
                if (released) return
                val gone = removedDevices.any { it.id == current.id }
                if (!gone) return
                // 还有别的蓝牙耳机就换过去；没有就外放。
                val other = findBluetoothOutput()
                if (other != null) {
                    log("ALERT_SOUND_SWITCH：#$pointId 耳机「${current.label()}」断开，改用另一个耳机「${other.label()}」")
                    startHeadset(other)
                } else {
                    speakerOrSilence("ALERT_SOUND_SWITCH：#$pointId 耳机「${current.label()}」断开")
                }
            }
        }
    }

    @Synchronized fun start() {
        if (uri == null) {
            log("ALERT_SOUND：#$pointId 铃声设为静音，本次不出声（仅振动）")
            return
        }
        when (val ringerMode = audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> headsetOnly = false
            AudioManager.RINGER_MODE_VIBRATE -> headsetOnly = true
            else -> {
                log("ALERT_SOUND：#$pointId 手机在静音模式（ringerMode=$ringerMode），本次不出声（仅振动）")
                return
            }
        }
        val headset = findBluetoothOutput()
        if (headset == null && headsetOnly) {
            log("ALERT_SOUND：#$pointId 振动模式且没连蓝牙耳机，本次不出声（仅振动）")
            return
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        deviceCallbackRegistered = true
        if (headset != null) startHeadset(headset) else startSpeaker()
    }

    @Synchronized fun stop() {
        released = true
        if (deviceCallbackRegistered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            deviceCallbackRegistered = false
        }
        releasePlayer()
        restoreMusicVolume()
        abandonFocus()
        headsetDevice = null
    }

    private fun startHeadset(device: AudioDeviceInfo) {
        releasePlayer()
        abandonFocus()
        headsetDevice = device
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        raiseMusicVolumeIfLow()
        requestFocus(attributes)
        val how = startPlayback(attributes, device)
        if (how == null) {
            speakerOrSilence("ALERT_SOUND_ERROR：#$pointId 耳机播放器创建失败")
            return
        }
        log("ALERT_SOUND：#$pointId 本次走耳机「${device.label()}」(type=${device.type})${if (headsetOnly) "（振动模式）" else ""}，USAGE_MEDIA，" +
            "$how，媒体音量=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" +
            (savedMusicVolume?.let { "（原音量 $it，已临时调高）" } ?: ""))
    }

    /** 耳机用不了时：响铃模式改外放；振动模式停声，只剩振动。 */
    private fun speakerOrSilence(reason: String) {
        if (headsetOnly) {
            log("$reason，振动模式不外放，停止出声（仅振动）")
            releasePlayer()
            restoreMusicVolume()
            abandonFocus()
            headsetDevice = null
        } else {
            log("$reason，自动切换外放继续响")
            startSpeaker()
        }
    }

    private fun startSpeaker() {
        releasePlayer()
        restoreMusicVolume()
        abandonFocus()
        headsetDevice = null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        requestFocus(attributes)
        // 明确指定内置扬声器，避免切换瞬间系统仍把声音路由到刚断开的蓝牙设备。
        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val how = startPlayback(attributes, speaker)
        if (how == null) {
            log("ALERT_SOUND_ERROR：#$pointId 外放播放器创建失败，本次不出声（仅振动）")
            return
        }
        log("ALERT_SOUND：#$pointId 本次走外放，USAGE_ALARM，$how，闹钟音量=${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
    }

    /**
     * 按顺序尝试：① App 用 MediaPlayer 直接放选定铃声（可 setPreferredDevice）；
     * ② 读不到（多半是没给「音乐和音频」权限）就用系统默认来电/闹钟铃声，仍由 App 自己播放。
     * 2.2.21 起不再交给系统铃声播放器（Ringtone）：它在系统进程里放，App 崩了也不停。
     * @return 本次播放方式的说明（写进日志）；null 表示全部失败。
     */
    private fun startPlayback(attributes: AudioAttributes, preferredDevice: AudioDeviceInfo?): String? {
        val selected = uri ?: return null
        val (mp, error) = prepareMediaPlayer(selected, attributes)
        if (mp != null) return startMediaPlayer(mp, preferredDevice, "App 直接播放")
        val fallbacks = listOf(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        ).filter { it != selected }
        for (candidate in fallbacks) {
            val fallback = prepareMediaPlayer(candidate, attributes).first ?: continue
            return startMediaPlayer(fallback, preferredDevice, "选定铃声无法播放（$error${if (RingtonePermission.granted(context)) "" else "，未授予音乐和音频权限"}），改用系统默认铃声")
        }
        return null
    }

    private fun startMediaPlayer(mp: MediaPlayer, preferredDevice: AudioDeviceInfo?, how: String): String {
        val preferred = preferredDevice?.let { d -> runCatching { mp.setPreferredDevice(d) }.getOrDefault(false) } ?: false
        runCatching { mp.start() }
        player = mp
        return "$how，setPreferredDevice=$preferred"
    }

    /** @return 准备好的播放器，或 null 加失败原因。 */
    private fun prepareMediaPlayer(candidate: Uri, attributes: AudioAttributes): Pair<MediaPlayer?, String?> {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(context, candidate)
            mp.isLooping = true
            mp.setOnErrorListener { _, what, extra ->
                log("ALERT_SOUND_ERROR：#$pointId 播放出错 what=$what extra=$extra")
                true
            }
            mp.prepare()
            mp to null
        } catch (e: Exception) {
            runCatching { mp.release() }
            null to "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun releasePlayer() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
    }

    private fun requestFocus(attributes: AudioAttributes) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun raiseMusicVolumeIfLow() {
        if (savedMusicVolume != null) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (max * MIN_HEADSET_VOLUME_RATIO).toInt().coerceAtLeast(1)
        if (current >= target) return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            .onSuccess { savedMusicVolume = current; raisedMusicVolume = target }
            .onFailure { log("ALERT_SOUND_ERROR：#$pointId 调高媒体音量失败：${it.message}") }
    }

    private fun restoreMusicVolume() {
        val original = savedMusicVolume ?: return
        val raised = raisedMusicVolume
        savedMusicVolume = null; raisedMusicVolume = null
        // 响铃期间用户自己调过音量就不覆盖。
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != raised) return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) }
        log("ALERT_SOUND：#$pointId 媒体音量已恢复为 $original")
    }

    private fun findBluetoothOutput(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return BLUETOOTH_TYPES.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
    }

    private fun AudioDeviceInfo.label(): String = productName?.toString()?.takeIf { it.isNotBlank() } ?: "type$type"

    private fun log(message: String) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch { runCatching { DiagnosticRecordLogger.geofence(app, message) } }
    }

    companion object {
        /** 耳机播放时媒体音量至少到最大值的这个比例。 */
        private const val MIN_HEADSET_VOLUME_RATIO = 0.5

        /** 按优先级排列的蓝牙音频输出类型（不含通话用的 SCO）。 */
        private val BLUETOOTH_TYPES: List<Int> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }
}
