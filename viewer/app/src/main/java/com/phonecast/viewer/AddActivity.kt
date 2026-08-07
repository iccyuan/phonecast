package com.phonecast.viewer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** 添加电脑: 填 地址/设备名/配对码, 或粘贴电脑端复制的连接信息。 */
class AddActivity : Activity() {

    private lateinit var addrInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var codeInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this

        addrInput = Ui.field(c, "192.168.1.10:27184 或中继地址").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(intent.getStringExtra("addr") ?: "")
        }
        roomInput = Ui.field(c, "电脑端显示的设备名").apply {
            setText(intent.getStringExtra("room") ?: "")
        }
        codeInput = Ui.field(c, "6 位数字").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val connectBtn = Ui.primaryButton(c, "配对并投屏")
        val pasteBtn = Ui.flatButton(c, "粘贴本机剪贴板内容")

        val card = Ui.card(c).apply {
            addView(Ui.label(c, "地址"))
            addView(addrInput)
            addView(Ui.label(c, "设备名"))
            addView(roomInput)
            addView(Ui.label(c, "配对码"))
            addView(codeInput)
            addView(connectBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 50)).apply {
                topMargin = Ui.dp(c, 22)
            })
            addView(pasteBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 42)).apply {
                topMargin = Ui.dp(c, 4)
            })
        }

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val pad = Ui.dp(c, 22)
            setPadding(pad, Ui.dp(c, 48), pad, pad)
            addView(Ui.flatButton(c, "‹  返回").apply {
                setPadding(0, 0, 0, 0)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 40)).apply {
                bottomMargin = Ui.dp(c, 12)
            })
            addView(Ui.title(c, "添加电脑"))
            addView(Ui.subtitle(c, "以上三项在电脑托盘菜单里可以看到"))
            addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 24) })
            addView(Ui.hint(c, "更省事: 电脑托盘右键 →「显示配对二维码」,\n用相机扫一下即可自动完成配对"),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 24) })
        }

        setContentView(ScrollView(c).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        })

        connectBtn.setOnClickListener { submit() }
        pasteBtn.setOnClickListener { pasteFromClipboard() }
    }

    private fun submit() {
        val addr = addrInput.text.toString().trim()
        val room = roomInput.text.toString().trim()
        val code = codeInput.text.toString().trim()
        if (addr.substringBeforeLast(':').isEmpty() ||
            addr.substringAfterLast(':').toIntOrNull() == null) {
            Toast.makeText(this, "地址格式应为 IP:端口", Toast.LENGTH_SHORT).show()
            return
        }
        if (room.isEmpty()) {
            Toast.makeText(this, "请填写设备名", Toast.LENGTH_SHORT).show()
            return
        }
        if (code.isEmpty() && Tokens.get(this, addr, room) == null) {
            Toast.makeText(this, "首次配对请填电脑端显示的 6 位配对码", Toast.LENGTH_SHORT).show()
            return
        }
        Saved.touch(this, Entry(addr, room))
        startActivity(Player.intent(this, Entry(addr, room), code))
        finish()
    }

    /**
     * 读本机(手机)剪贴板。注意电脑与手机剪贴板不互通 —— 电脑上复制后需先把文本
     * 发到手机再复制, 或直接扫二维码。
     */
    private fun pasteFromClipboard() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (clip.isNullOrEmpty()) {
            Toast.makeText(this, "手机剪贴板为空。电脑上复制的内容不会同步到手机,建议直接扫二维码",
                Toast.LENGTH_LONG).show()
            return
        }
        Regex("phonecast://\\S+").find(clip)?.value?.let { link ->
            val uri = Uri.parse(link)
            uri.getQueryParameter("a")?.let { addrInput.setText(it) }
            uri.getQueryParameter("r")?.let { roomInput.setText(it) }
            uri.getQueryParameter("c")?.let { codeInput.setText(it) }
            Toast.makeText(this, "已填入", Toast.LENGTH_SHORT).show()
            return
        }
        var found = false
        for (line in clip.lines()) {
            val idx = line.indexOfFirst { it == ':' || it == '：' }
            if (idx < 0) continue
            val name = line.substring(0, idx)
            val v = line.substring(idx + 1).trim()
            if (v.isEmpty()) continue
            when {
                name.contains("配对码") -> { codeInput.setText(v); found = true }
                name.contains("设备名") -> { roomInput.setText(v); found = true }
                name.contains("地址") && !name.contains("局域网") -> { addrInput.setText(v); found = true }
                name.contains("地址") && addrInput.text.isEmpty() -> { addrInput.setText(v); found = true }
            }
        }
        Toast.makeText(this,
            if (found) "已填入" else "剪贴板里没有连接信息,建议直接扫电脑上的配对二维码",
            if (found) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }
}

/** 启动播放页的统一入口 */
object Player {
    fun intent(c: android.content.Context, e: Entry, code: String): Intent =
        Intent(c, PlayerActivity::class.java)
            .putExtra("host", e.host)
            .putExtra("port", e.port)
            .putExtra("addr", e.addr)
            .putExtra("room", e.room)
            .putExtra("code", code)
}
