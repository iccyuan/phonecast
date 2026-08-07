package com.phonecast.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 主页: 已配对电脑列表。点条目直接连接(已配对的用设备令牌免输配对码),
 * 长按删除, 右下角「+」添加新电脑。扫二维码可跳过整个流程直接连。
 */
class MainActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var emptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this

        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(c).apply { background = LogoDrawable() },
                LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.title(c, "PhoneCast"))
                addView(Ui.subtitle(c, "把另一台手机的画面投到这里"))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dp(c, 14) })
        }

        listBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        emptyHint = Ui.hint(c, "还没有配对的手机\n\n点右下角「+」添加,\n或用相机扫电脑托盘菜单里的配对二维码").apply {
            setPadding(0, Ui.dp(c, 48), 0, 0)
        }

        val content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val pad = Ui.dp(c, 22)
            setPadding(pad, Ui.dp(c, 56), pad, Ui.dp(c, 96))
            addView(header)
            addView(Ui.label(c, "已配对的手机"))
            addView(listBox)
            addView(emptyHint)
            addView(Ui.flatButton(c, "检查更新  ·  v${Updater.currentVersion(c)}", Ui.MUTED).apply {
                setOnClickListener { Updater.check(c, manual = true) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 44)).apply {
                topMargin = Ui.dp(c, 20)
            })
        }

        val fab = View(c).apply {
            background = FabDrawable()
            setOnClickListener { startActivity(Intent(c, AddActivity::class.java)) }
        }

        setContentView(FrameLayout(c).apply {
            setBackgroundColor(Ui.BG)
            addView(ScrollView(c).apply {
                isFillViewport = true
                addView(content)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(fab, FrameLayout.LayoutParams(Ui.dp(c, 60), Ui.dp(c, 60), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = Ui.dp(c, 24)
                bottomMargin = Ui.dp(c, 28)
            })
        })

        // 仅全新启动时处理深链; Activity 重建(旋转/回退)会重投递原 intent, 不能重复自动连接
        if (savedInstanceState == null) {
            handleDeepLink(intent)
            Updater.check(this, manual = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        val c = this
        listBox.removeAllViews()
        val items = Saved.list(c)
        emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (e in items) {
            listBox.addView(row(e), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 10) })
        }
    }

    private fun row(e: Entry): View {
        val c = this
        val paired = Tokens.get(c, e.addr, e.room) != null
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rowBackground(c)
            val p = Ui.dp(c, 16)
            setPadding(p, p, p, p)
            addView(TextView(c).apply {
                text = e.title // 优先显示被投屏手机的机型
                setTextColor(Ui.TEXT)
                textSize = 17f
            })
            addView(TextView(c).apply {
                val via = "经 ${e.addr} · ${e.room}"
                text = if (paired) via else "$via  ·  未配对,点击后需输配对码"
                setTextColor(if (paired) Ui.MUTED else 0xFFD9A441.toInt())
                textSize = 12f
                setPadding(0, Ui.dp(c, 4), 0, 0)
            })
            setOnClickListener { open(e) }
            setOnLongClickListener {
                confirmDelete(e)
                true
            }
        }
    }

    private fun open(e: Entry) {
        Saved.touch(this, e)
        if (Tokens.get(this, e.addr, e.room) != null) {
            startActivity(Player.intent(this, e, "")) // 已配对: 令牌免输
        } else {
            startActivity(Intent(this, AddActivity::class.java)
                .putExtra("addr", e.addr)
                .putExtra("room", e.room))
        }
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("删除「${e.room}」?")
            .setMessage("会同时清除本机保存的配对令牌,下次连接需要重新输入配对码。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                Saved.remove(this, e)
                renderList()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** phonecast://c?a=地址&r=设备名&c=配对码 → 保存并直接连接 */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "phonecast") return
        val addr = uri.getQueryParameter("a").orEmpty()
        val room = uri.getQueryParameter("r").orEmpty()
        val code = uri.getQueryParameter("c").orEmpty()
        intent.data = null // 防止旋转等场景重复触发
        if (addr.isEmpty() || room.isEmpty()) {
            Toast.makeText(this, "二维码内容不完整", Toast.LENGTH_SHORT).show()
            return
        }
        val e = Entry(addr, room)
        Saved.touch(this, e)
        startActivity(Player.intent(this, e, code))
    }

    /** 页内 Logo: 与启动器/托盘图标同款圆角蓝底白三角 */
    private class LogoDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = b.width() * 0.24f
            paint.color = Ui.ACCENT
            canvas.drawRoundRect(RectF(b), r, r, paint)
            paint.color = Color.WHITE
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val h = b.height() * 0.26f
            path.reset()
            path.moveTo(cx - h * 0.7f, cy - h)
            path.lineTo(cx + h * 0.95f, cy)
            path.lineTo(cx - h * 0.7f, cy + h)
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("deprecated in API 29", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 右下角圆形「+」按钮 */
    private class FabDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = minOf(b.width(), b.height()) / 2f
            paint.color = Ui.ACCENT
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = r * 0.12f
            val arm = r * 0.42f
            canvas.drawLine(b.exactCenterX() - arm, b.exactCenterY(),
                b.exactCenterX() + arm, b.exactCenterY(), paint)
            canvas.drawLine(b.exactCenterX(), b.exactCenterY() - arm,
                b.exactCenterX(), b.exactCenterY() + arm, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("deprecated in API 29", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
