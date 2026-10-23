//! AudioRecord 生命周期与可选蓝牙路由；取消可跨线程关闭，路由结束后恢复。
package dev.qingstudy.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

internal class MicrophoneSource(private val context: Context, private val bluetooth: Boolean) : VoiceAudioSource {
    private val gate = Any()
    private val closed = AtomicBoolean(false)
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var recorder: AudioRecord? = null
    private var oldMode: Int? = null
    private var oldDevice: AudioDeviceInfo? = null
    private var selectedDevice: AudioDeviceInfo? = null
    private val pcm = ShortArray(640)

    override fun start() = synchronized(gate) {
        check(!closed.get()) { "录音已取消" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "请先在语音设置中允许麦克风权限"
        }
        try {
            if (bluetooth) configureBluetooth()
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持当前录音格式" }
            val recording = AudioRecord.Builder()
                .setAudioSource(if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 16000)).build()
            recorder = recording
            check(recording.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
            if (bluetooth) {
                val input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isBluetooth(it.type) }
                check(input != null && recording.setPreferredDevice(input)) { "无法使用蓝牙耳机麦克风" }
            }
            recording.startRecording()
            check(recording.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风正在被其他应用占用" }
        } catch (failure: Throwable) { close(); throw failure }
    }

    override fun read(destination: FloatArray): Int {
        if (closed.get()) return -1
        val current = recorder ?: return -1
        val count = current.read(pcm, 0, minOf(pcm.size, destination.size), AudioRecord.READ_BLOCKING)
        if (closed.get()) return -1
        check(count >= 0) { "麦克风已断开或不可用，请重试" }
        if (Build.VERSION.SDK_INT >= 29) check(current.activeRecordingConfiguration?.isClientSilenced != true) {
            "麦克风被系统静音或被其他应用占用"
        }
        if (bluetooth && count > 0) {
            val routed = current.routedDevice
            check(routed != null && isBluetooth(routed.type)) { "蓝牙麦克风连接失败，请关闭蓝牙输入后重试" }
        }
        for (index in 0 until count) destination[index] = pcm[index] / 32768f
        return count
    }

    private fun configureBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                "请先在语音设置中允许蓝牙连接权限"
            }
            val target = audio.availableCommunicationDevices.firstOrNull { isBluetooth(it.type) }
            check(target != null) { "未连接可用的蓝牙耳机麦克风" }
            oldMode = audio.mode
            oldDevice = audio.communicationDevice
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            selectedDevice = target
            check(audio.setCommunicationDevice(target)) { "无法切换到蓝牙耳机麦克风" }
        } else {
            error("蓝牙语音输入需要 Android 12 或以上版本")
        }
    }

    override fun close() = synchronized(gate) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        val current = recorder
        recorder = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
        if (Build.VERSION.SDK_INT >= 31 && selectedDevice != null) {
            runCatching {
                if (audio.communicationDevice?.id == selectedDevice?.id) {
                    oldDevice?.let { audio.setCommunicationDevice(it) } ?: audio.clearCommunicationDevice()
                }
                oldMode?.let { if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = it }
            }
        }
        pcm.fill(0)
    }

    private fun isBluetooth(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
}
