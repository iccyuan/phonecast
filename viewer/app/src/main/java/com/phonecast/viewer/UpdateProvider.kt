package com.phonecast.viewer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 把下载好的 APK 以 content:// 暴露给系统安装器。
 * Android 7+ 禁止直接传 file:// (会抛 FileUriExposedException), 必须经 provider 授权;
 * 这里自己实现最小版本, 免得为一个 FileProvider 引入整个 androidx.core。
 */
class UpdateProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "com.phonecast.viewer.updates"
        private const val DIR = "update"

        fun updateDir(c: Context): File = File(c.cacheDir, DIR).apply { mkdirs() }

        fun uriFor(c: Context, file: File): Uri =
            Uri.parse("content://$AUTHORITY/${file.name}")
    }

    override fun onCreate() = true

    private fun resolve(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null // 只允许目录内的裸文件名
        val f = File(updateDir(context!!), name)
        return if (f.isFile) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = resolve(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** 安装器会查文件名与大小 */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(values) }
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    // 只读 provider
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
