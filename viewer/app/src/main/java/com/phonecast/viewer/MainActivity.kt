package com.phonecast.viewer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 连接页: 地址(直连 agent 或云端 hub) + 密钥 + 配对码。
 * 三种填法: 手动 / 扫电脑端配对二维码(phonecast:// 深链自动连接) / 从剪贴板粘贴。
 */
class MainActivity : Activity() {

    private lateinit var addrInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var roomInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("phonecast", MODE_PRIVATE)

        addrInput = EditText(this).apply {
            hint = "地址 IP:端口 (hub 或电脑直连)"
            setText(prefs.getString("last_addr", ""))
        }
        keyInput = EditText(this).apply {
            hint = "密钥"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("last_key", ""))
        }
        roomInput = EditText(this).apply {
            hint = "配对码"
            setText(prefs.getString("last_room", ""))
        }
        val connectBtn = Button(this).apply { text = "连接并投屏" }
        val pasteBtn = Button(this).apply { text = "从剪贴板填入" }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(context).apply {
                text = "PhoneCast"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            })
            addView(TextView(context).apply {
                text = "扫电脑托盘菜单里的「配对二维码」可自动连接"
                setPadding(0, 0, 0, pad / 2)
            })
            addView(addrInput)
            addView(keyInput)
            addView(roomInput)
            addView(connectBtn)
            addView(pasteBtn)
        })

        connectBtn.setOnClickListener { connect() }
        pasteBtn.setOnClickListener { pasteFromClipboard() }

        // 仅全新启动时处理深链; Activity 重建(旋转/回退)会重投递原 intent, 不能重复自动连接
        if (savedInstanceState == null) handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** phonecast://c?a=地址&k=密钥&r=配对码 → 填入并自动连接 */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "phonecast") return
        uri.getQueryParameter("a")?.let { addrInput.setText(it) }
        uri.getQueryParameter("k")?.let { keyInput.setText(it) }
        uri.getQueryParameter("r")?.let { roomInput.setText(it) }
        intent.data = null // 防止旋转等场景重复触发
        connect()
    }

    /** 支持两种剪贴板内容: phonecast:// 链接, 或电脑端「复制连接信息」的多行文本 */
    private fun pasteFromClipboard() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (clip.isNullOrEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (clip.startsWith("phonecast://")) {
            handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse(clip)))
            return
        }
        var found = false
        for (line in clip.lines()) {
            val v = line.substringAfter(':', "").trim().substringAfter('：').trim()
            when {
                line.contains("密钥") -> { keyInput.setText(v); found = true }
                line.contains("配对码") -> { roomInput.setText(v); found = true }
                line.contains("地址") && !line.contains("局域网") -> { addrInput.setText(v); found = true }
                line.contains("地址") && addrInput.text.isEmpty() -> { addrInput.setText(v); found = true }
            }
        }
        if (!found) Toast.makeText(this, "没识别出连接信息", Toast.LENGTH_SHORT).show()
    }

    private fun connect() {
        val addr = addrInput.text.toString().trim()
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toIntOrNull()
        val key = keyInput.text.toString().trim()
        val room = roomInput.text.toString().trim()
        if (host.isEmpty() || port == null) {
            Toast.makeText(this, "地址格式应为 IP:端口", Toast.LENGTH_SHORT).show()
            return
        }
        if (key.isEmpty() || room.isEmpty()) {
            Toast.makeText(this, "密钥与配对码不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("phonecast", MODE_PRIVATE).edit()
            .putString("last_addr", addr)
            .putString("last_key", key)
            .putString("last_room", room)
            .apply()
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("host", host)
            .putExtra("port", port)
            .putExtra("key", key)
            .putExtra("room", room))
    }
}
