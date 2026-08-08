package com.phonecast.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 应用内更新: 查 GitHub Releases → 下载 APK(带进度) → 自动拉起系统安装器。
 * 不跳浏览器, 用户只需在系统弹窗上点"安装"。
 */
object Updater {
    private const val API = "https://api.github.com/repos/iccyuan/phonecast/releases/latest"
    private const val ASSET = "phonecast-viewer.apk"
    private const val PREFS = "phonecast_update"
    private const val KEY_LAST = "last"
    private const val KEY_PENDING = "pending_apk" // 等"允许安装未知应用"授权后继续
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 自动检查最快 6 小时一次

    fun currentVersion(a: Activity): String =
        runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }
            .getOrNull() ?: "0"

    /** manual=false 为静默自动检查, 无新版本不打扰, 失败也不提示 */
    fun check(a: Activity, manual: Boolean) {
        val prefs = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(KEY_LAST, 0) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST, now).apply()

        if (manual) Toast.makeText(a, "正在检查更新…", Toast.LENGTH_SHORT).show()
        thread(name = "update-check") {
            val result = runCatching { fetchLatest() }.getOrNull()
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (result == null) {
                    if (manual) Toast.makeText(a, "检查更新失败,请检查网络", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val (latest, url, notes) = result
                val cur = currentVersion(a)
                if (!isNewer(latest, cur)) {
                    if (manual) Toast.makeText(a, "当前 v$cur 已是最新版本", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Dialogs.update(a, latest, cur, notes) { downloadAndInstall(a, url, latest) }
            }
        }
    }

    /** 下载 APK 并在完成后拉起安装; 进度对话框可取消 */
    private fun downloadAndInstall(a: Activity, url: String, version: String) {
        val cancelled = AtomicBoolean(false)
        val dialog = Dialogs.progress(a, "更新到 v$version") { cancelled.set(true) }

        thread(name = "update-download") {
            val target = File(UpdateProvider.updateDir(a), "phonecast-$version.apk")
            var failure: String? = null
            val ok = runCatching {
                download(url, target, cancelled) { done, total ->
                    a.runOnUiThread { dialog.update(done, total) }
                }
            }.getOrElse {
                failure = it.message ?: it.javaClass.simpleName
                false
            }

            a.runOnUiThread {
                dialog.dismiss()
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                when {
                    cancelled.get() -> target.delete()
                    !ok -> {
                        target.delete()
                        // 国内直连 GitHub 经常被重置, 与其只说"失败", 不如给出原因和退路
                        Dialogs.confirm(a, "下载失败",
                            (failure?.let { "原因: $it\n\n" } ?: "") +
                                "国内网络常常无法直接访问 GitHub。可以改用浏览器下载" +
                                "(浏览器能走系统代理),下载完成后按提示安装。",
                            "用浏览器下载",
                            { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                            "稍后再说")
                    }
                    else -> install(a, target)
                }
            }
        }
    }

    private fun download(
        url: String, target: File, cancelled: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // GitHub 会 302 到 objects.githubusercontent.com
        }
        try {
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLength.toLong()
            var done = 0L
            var lastTick = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 150) { // 别把 UI 线程刷爆
                            lastTick = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            return target.length() > 0
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 拉起系统安装器。Android 8+ 需要用户先允许本应用"安装未知应用",
     * 未授权时引导到设置页, 回到应用后自动继续(见 resumePendingInstall)。
     */
    private fun install(a: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !a.packageManager.canRequestPackageInstalls()) {
            a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_PENDING, apk.absolutePath).apply()
            Dialogs.confirm(a, "需要安装权限",
                "系统要求先允许 PhoneCast 安装应用。点「去设置」打开开关,返回后会自动继续安装。",
                "去设置", {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${a.packageName}")))
                }, "取消")
            return
        }
        launchInstaller(a, apk)
    }

    private fun launchInstaller(a: Activity, apk: File) {
        val uri = UpdateProvider.uriFor(a, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { a.startActivity(intent) }.onFailure {
            Toast.makeText(a, "无法打开安装器: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 从"允许安装未知应用"设置页返回后调用: 权限拿到了就继续装 */
    fun resumePendingInstall(a: Activity) {
        val prefs = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val path = prefs.getString(KEY_PENDING, null) ?: return
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            a.packageManager.canRequestPackageInstalls()
        if (!granted) return
        prefs.edit().remove(KEY_PENDING).apply()
        val apk = File(path)
        if (apk.isFile) launchInstaller(a, apk)
    }

    data class Release(val version: String, val apkUrl: String, val notes: String)

    /** 返回 版本号 / APK 下载地址 / 发布说明 */
    private fun fetchLatest(): Release {
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
                    val item = assets.optJSONObject(i) ?: continue
                    if (item.optString("name") == ASSET) url = item.optString("browser_download_url")
                }
            }
            require(tag.isNotEmpty() && url.isNotEmpty())
            // 发布说明去掉 GitHub 自动生成的链接尾巴, 只留人看得懂的部分
            val notes = json.optString("body").lineSequence()
                .filterNot { it.startsWith("**Full Changelog") || it.isBlank() }
                .take(20).joinToString("\n").trim()
            return Release(tag, url, notes)
        } finally {
            conn.disconnect()
        }
    }

    /** 逐段数值比较 "0.6.0" 形式的版本号 */
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
