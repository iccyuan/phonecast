package com.phonecast.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 主页: 已配对手机列表。点条目直接连(已配对的用设备令牌免输配对码),
 * 长按删除, 右下角「+」添加新电脑。扫二维码可跳过整个流程直接连。
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_SCAN = 1
    }

    private lateinit var listBox: LinearLayout
    private lateinit var emptyBox: LinearLayout
    private lateinit var fab: View
    private lateinit var scrim: View
    private lateinit var actionsBox: LinearLayout
    private var expanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this

        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(c).apply {
                background = Icons.Logo()
                outlineProvider = ViewOutlineProvider.BACKGROUND
                elevation = Ui.dpf(c, 4f)
            }, LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.h1(c, "PhoneCast"))
                addView(Ui.subtitle(c, "把另一台手机的画面投到这里"))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dp(c, 14) })
        }

        listBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        emptyBox = emptyState()

        val content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(c, 20)
            setPadding(pad, Ui.dp(c, 52), pad, Ui.dp(c, 100))
            addView(header)
            addView(Ui.sectionLabel(c, "已配对的手机"))
            addView(listBox)
            addView(emptyBox)
        }

        // 悬浮「+」: 点击原地展开两个操作, 再点(或点空白处)收起
        fab = View(c).apply {
            background = Ui.fabBackground(c)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = Ui.dpf(c, 8f)
            contentDescription = "添加电脑"
            setOnClickListener { setExpanded(!expanded) }
            addPressFeedback()
        }
        scrim = View(c).apply {
            setBackgroundColor(0xB3000000.toInt())
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { setExpanded(false) }
        }
        actionsBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
            val gap = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 12) }
            addView(speedDialAction("扫码添加", Icons.Scan(c), Ui.ACCENT) { startScan() })
            addView(speedDialAction("手动填写", Icons.Edit(c), Ui.SURFACE2) {
                startActivity(Intent(c, AddActivity::class.java))
            }, gap)
            addView(speedDialAction(
                "检查更新 · v${Updater.currentVersion(c)}", Icons.Refresh(c), Ui.SURFACE2) {
                Updater.check(c, manual = true)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 12) })
        }

        setContentView(FrameLayout(c).apply {
            setBackgroundColor(Ui.BG)
            addView(ScrollView(c).apply {
                isFillViewport = true
                clipToPadding = false
                addView(content)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(scrim, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(actionsBox, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = Ui.dp(c, 28)
                bottomMargin = Ui.dp(c, 98) // 让开 FAB
            })
            addView(fab, FrameLayout.LayoutParams(
                Ui.dp(c, 58), Ui.dp(c, 58), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = Ui.dp(c, 22)
                bottomMargin = Ui.dp(c, 26)
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

    private fun emptyState(): LinearLayout {
        val c = this
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Ui.dp(c, 40), 0, 0)
            addView(View(c).apply {
                background = Ui.rounded(c, Ui.SURFACE, 28, Ui.BORDER)
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }, LinearLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 96)).apply {
                bottomMargin = Ui.dp(c, 20)
            })
            addView(View(c).apply { background = Icons.Phone(c, Ui.DIM, 2f) },
                LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)).apply {
                    topMargin = -Ui.dp(c, 96) // 叠在上面的圆角方块中央
                    bottomMargin = Ui.dp(c, 60)
                })
            addView(TextView(c).apply {
                text = "还没有配对的手机"
                setTextColor(Ui.MUTED)
                textSize = 15f
            })
            addView(Ui.hint(c, "在电脑托盘图标右键 →「显示配对二维码」,\n用下面的扫码功能扫一下即可").apply {
                setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 22))
            })
            addView(Ui.primaryButton(c, "扫码添加").apply {
                setOnClickListener { startScan() }
            }, LinearLayout.LayoutParams(Ui.dp(c, 200), Ui.dp(c, 48)))
            addView(Ui.flatButton(c, "手动填写").apply {
                setOnClickListener { startActivity(Intent(c, AddActivity::class.java)) }
            }, LinearLayout.LayoutParams(Ui.dp(c, 200), Ui.dp(c, 42)).apply {
                topMargin = Ui.dp(c, 4)
            })
        }
    }

    /** 速拨菜单的一项: 左侧文字药丸 + 右侧圆形图标按钮, 整行可点 */
    private fun speedDialAction(
        label: String, icon: android.graphics.drawable.Drawable, fill: Int, onClick: () -> Unit,
    ): LinearLayout {
        val c = this
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.pill(c, label))
            addView(View(c).apply {
                background = Ui.circleButton(c, icon, fill, 13)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                elevation = Ui.dpf(c, 6f)
            }, LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)).apply {
                leftMargin = Ui.dp(c, 12)
            })
            setOnClickListener {
                setExpanded(false)
                onClick()
            }
            addPressFeedback()
        }
    }

    /**
     * 展开/收起速拨菜单。各项从 FAB 方向弹出(位移+缩放+淡入, 逐个错开),
     * 「+」带回弹地转成「×」—— 动作幅度足够大才看得出来。
     */
    private fun setExpanded(on: Boolean) {
        if (expanded == on) return
        expanded = on
        val c = this
        val items = actionsBox.children()

        // 「+」转 45° 成「×」, 轻微回弹即可, 过头会显得突兀
        fab.animate().rotation(if (on) 45f else 0f)
            .setInterpolator(if (on) OvershootInterpolator(0.8f) else DecelerateInterpolator())
            .setDuration(240).start()

        if (on) {
            scrim.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setInterpolator(DecelerateInterpolator())
                .setDuration(220).start()
            actionsBox.visibility = View.VISIBLE
            actionsBox.alpha = 1f
            for ((i, v) in items.withIndex()) {
                v.alpha = 0f
                v.translationY = Ui.dpf(c, 28f)
                v.scaleX = 0.85f
                v.scaleY = 0.85f
                v.pivotX = v.width.toFloat() // 从靠近 FAB 的一侧展开
                v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(45L * (items.size - 1 - i)) // 离 FAB 近的先出来
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .setDuration(240).start()
            }
        } else {
            scrim.animate().alpha(0f).setDuration(180)
                .withEndAction { scrim.visibility = View.GONE }.start()
            for ((i, v) in items.withIndex()) {
                v.animate().alpha(0f).translationY(Ui.dpf(c, 20f)).scaleX(0.9f).scaleY(0.9f)
                    .setStartDelay(35L * i)
                    .setInterpolator(AccelerateInterpolator())
                    .setDuration(150)
                    .withEndAction { if (i == items.size - 1) actionsBox.visibility = View.GONE }
                    .start()
            }
        }
    }

    /** 按下时轻微缩小, 松手回弹 —— 让点击有"按下去"的手感而不是硬切 */
    private fun View.addPressFeedback() {
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.92f).scaleY(0.92f)
                        .setInterpolator(DecelerateInterpolator()).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(1.2f)).setDuration(180).start()
            }
            false // 不拦截, 交给 OnClickListener
        }
    }

    private fun LinearLayout.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    override fun onBackPressed() {
        if (expanded) setExpanded(false) else super.onBackPressed()
    }

    private fun startScan() {
        startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data?.data != null) {
            handleDeepLink(data) // 扫到的 phonecast:// 链接走同一条路径
        }
    }

    private fun renderList() {
        val c = this
        listBox.removeAllViews()
        val items = Saved.list(c)
        emptyBox.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (e in items) {
            listBox.addView(row(e), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 10) })
        }
    }

    /** 列表项: 图标 + (机型 / 连接坐标) + 状态点 + 进入箭头 */
    private fun row(e: Entry): View {
        val c = this
        val paired = Tokens.get(c, e.addr, e.room) != null
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rowBackground(c)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            val p = Ui.dp(c, 14)
            setPadding(p, p, p, p)

            addView(Ui.iconTile(c, Icons.Phone(c, if (paired) Ui.ACCENT else Ui.WARN), Ui.ACCENT),
                LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42)))

            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(c).apply {
                    text = e.title // 优先显示被投屏手机的机型
                    setTextColor(Ui.TEXT)
                    textSize = 16.5f
                    isSingleLine = true
                })
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Ui.dp(c, 5), 0, 0)
                    addView(Ui.dot(c, if (paired) Ui.SUCCESS else Ui.WARN),
                        LinearLayout.LayoutParams(Ui.dp(c, 6), Ui.dp(c, 6)))
                    addView(TextView(c).apply {
                        text = if (paired) "已配对  ·  ${e.addr}" else "待配对  ·  点击后输配对码"
                        setTextColor(if (paired) Ui.MUTED else Ui.WARN)
                        textSize = 12f
                        isSingleLine = true
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dp(c, 6) })
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Ui.dp(c, 14)
            })

            addView(View(c).apply { background = Icons.ChevronRight(c) },
                LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)))

            setOnClickListener { open(e) }
            setOnLongClickListener { confirmDelete(e); true }
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
            .setTitle("删除「${e.title}」?")
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
}
