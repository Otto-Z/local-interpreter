package com.localinterpreter.tablet.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.localinterpreter.tablet.util.AppLog

/**
 * 监听耳机 / 蓝牙麦克风的插拔与切换。
 *
 * Android 不会把已创建的 AudioRecord 自动迁到新设备：拔掉蓝牙耳机后，录音可能继续从一个
 * 已经不存在的设备上读，表现为电平为 0 或 read 返回错误。这里只负责把变化上报，
 * 由 LiveController 决定是重建采集还是提示用户。
 */
class AudioRouteMonitor(context: Context, private val onChange: (String) -> Unit) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            report("added", addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            report("removed", removedDevices)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                AppLog.d("AudioRoute", "ACTION_AUDIO_BECOMING_NOISY")
                onChange("noisy")
            }
        }
    }

    private fun report(kind: String, devices: Array<out AudioDeviceInfo>) {
        val leaHeadset = 26 // AudioDeviceInfo.TYPE_BLE_HEADSET，API 31 才公开常量
        val mic = devices.firstOrNull {
            it.isSource && (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == leaHeadset)
        } ?: return
        AppLog.d("AudioRoute", "$kind device=${mic.productName} type=${mic.type}")
        // 新设备刚接入时输入路由可能还没生效，稍等再让上层重建采集。
        handler.postDelayed({ onChange(kind) }, 400)
    }

    fun start() {
        if (registered) return
        registered = true
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, handler) }
        runCatching {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            appContext.registerReceiver(noisyReceiver, filter)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        handler.removeCallbacksAndMessages(null)
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }
}
