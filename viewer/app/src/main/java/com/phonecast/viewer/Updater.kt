package com.phonecast.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 更新检查: 查 GitHub Releases 最新 tag, 比当前版本新就提示,
 * 确认后用系统浏览器下载 APK(安装由系统接管)。
 */
object Updater {
    private const val API = "https://api.github.com/repos/iccyuan/phonecast/releases/latest"
    private const val ASSET = "phonecast-viewer.apk"
    private const val PREFS = "phonecast_update"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 自动检查最快 6 小时一次

    fun currentVersion(a: Activity): String =
        runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }
            .getOrNull() ?: "0"

    /** manual=false 为静默自动检查, 无新版本不打扰, 失败也不提示 */
    fun check(a: Activity, manual: Boolean) {
        val prefs = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("last", 0) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong("last", now).apply()

        if (manual) Toast.makeText(a, "正在检查更新...", Toast.LENGTH_SHORT).show()
        thread(name = "update-check") {
            val result = runCatching { fetchLatest() }.getOrNull()
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (result == null) {
                    if (manual) Toast.makeText(a, "检查更新失败,请检查网络", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val (latest, url) = result
                val cur = currentVersion(a)
                if (!isNewer(latest, cur)) {
                    if (manual) Toast.makeText(a, "当前 v$cur 已是最新版本", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("发现新版本 v$latest")
                    .setMessage("当前版本 v$cur。点「下载」用浏览器获取新版 APK,下载完成后按系统提示安装。")
                    .setNegativeButton("以后再说", null)
                    .setPositiveButton("下载") { _, _ ->
                        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .show()
            }
        }
    }

    /** 返回 (版本号, APK 下载地址) */
    private fun fetchLatest(): Pair<String, String> {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name").removePrefix("v")
            var url = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name") == ASSET) url = a.optString("browser_download_url")
                }
            }
            require(tag.isNotEmpty() && url.isNotEmpty())
            return tag to url
        } finally {
            conn.disconnect()
        }
    }

    /** 逐段数值比较 "0.5.0" 形式的版本号 */
    private fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            val xi = x.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val yi = y.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (xi != yi) return xi > yi
        }
        return false
    }
}
