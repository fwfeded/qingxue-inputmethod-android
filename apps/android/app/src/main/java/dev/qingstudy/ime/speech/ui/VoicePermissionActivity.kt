package dev.qingstudy.ime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle

class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { finish(); return }
        val offline = VoiceSettings.read(this).optString("provider", "system") == "offline"
        AlertDialog.Builder(this).setTitle(if (offline) "使用离线语音" else "使用手机语音服务")
            .setMessage("青学只在你开始说话时使用麦克风。" +
                (if (offline) "录音在本机识别。" else "录音交由手机已安装的识别服务处理，该服务可能联网。可在语音设置切换为开源离线识别。") +
                "青学不保存录音文件。授权后请回到键盘重新开始。")
            .setPositiveButton("继续") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
            .setNegativeButton("暂不使用") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
