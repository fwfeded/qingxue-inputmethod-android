//! 仅向系统安装器临时共享已校验 APK，不暴露模型、词库或其他文件。
package dev.qingstudy.ime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

class UpdateApkProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): java.io.File {
        require(uri.authority == context!!.packageName + ".updates" && uri.path == "/update.apk") { "不支持的更新文件" }
        return UpdateManager.apk(context!!)
    }
    override fun getType(uri: Uri): String { file(uri); return "application/vnd.android.package-archive" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "只允许读取更新文件" }
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val apk = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> {
            when (it) { OpenableColumns.DISPLAY_NAME -> "qingxue-update.apk"; OpenableColumns.SIZE -> apk.length(); else -> null }
        }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
}
