package com.checkinassistant.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
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
 * - 手机不在响铃模式（静音/振动）：不出声，与旧版 USAGE_NOTIFICATION_RINGTONE 被系统静音的行为一致。
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
    /** App 自己读不到选定铃声时（自加歌曲需要音频权限），交给系统铃声播放器播放。 */
    private var ringtone: Ringtone? = null
    private var focusRequest: AudioFocusRequest? = null
    private var headsetDevice: AudioDeviceInfo? = null
    /** 临时调高媒体音量前的原值；null 表示没有调过。 */
    private var savedMusicVolume: Int? = null
    private var raisedMusicVolume: Int? = null
    private var deviceCallbackRegistered = false
    @Volatile private var released = false

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true || ringtone?.isPlaying == true }.getOrDefault(false)

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
                    log("ALERT_SOUND_SWITCH：#$pointId 耳机「${current.label()}」断开，自动切换外放继续响")
                    startSpeaker()
                }
            }
        }
    }

    @Synchronized fun start() {
        if (uri == null) {
            log("ALERT_SOUND：#$pointId 铃声设为静音，本次不出声（仅振动）")
            return
        }
        val ringerMode = audioManager.ringerMode
        if (ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            log("ALERT_SOUND：#$pointId 手机不在响铃模式（ringerMode=$ringerMode），本次不出声（仅振动）")
            return
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        deviceCallbackRegistered = true
        val headset = findBluetoothOutput()
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
            log("ALERT_SOUND_ERROR：#$pointId 耳机播放器创建失败，改为外放")
            startSpeaker()
            return
        }
        log("ALERT_SOUND：#$pointId 本次走耳机「${device.label()}」(type=${device.type})，USAGE_MEDIA，" +
            "$how，媒体音量=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" +
            (savedMusicVolume?.let { "（原音量 $it，已临时调高）" } ?: ""))
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
     * ② App 读不到时交给系统铃声播放器（Ringtone 由系统代放，不需要 App 有音频权限，
     *    路由按 AudioAttributes 走：USAGE_MEDIA 进耳机、USAGE_ALARM 外放）；
     * ③ 都不行再用系统默认来电/闹钟铃声。
     * @return 本次播放方式的说明（写进日志）；null 表示全部失败。
     */
    private fun startPlayback(attributes: AudioAttributes, preferredDevice: AudioDeviceInfo?): String? {
        val selected = uri ?: return null
        val (mp, error) = prepareMediaPlayer(selected, attributes)
        if (mp != null) return startMediaPlayer(mp, preferredDevice, "App 直接播放")
        val rt = try {
            RingtoneManager.getRingtone(context, selected)?.apply {
                audioAttributes = attributes
                isLooping = true
                play()
            }
        } catch (e: Exception) {
            log("ALERT_SOUND_ERROR：#$pointId 系统铃声播放器也打不开选定铃声：${e.javaClass.simpleName} ${e.message}")
            null
        }
        if (rt != null) {
            ringtone = rt
            return "选定铃声交给系统播放器（App 无法直接读取：$error）"
        }
        val fallbacks = listOf(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        ).filter { it != selected }
        for (candidate in fallbacks) {
            val fallback = prepareMediaPlayer(candidate, attributes).first ?: continue
            return startMediaPlayer(fallback, preferredDevice, "选定铃声无法播放（$error），改用系统默认铃声")
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
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
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
