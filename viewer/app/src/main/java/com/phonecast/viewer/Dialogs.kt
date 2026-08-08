package com.phonecast.viewer

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 与 App 同一套设计的对话框。
 * 系统默认对话框(Theme_Material_Dialog_Alert)是亮底方角, 和这里的暗色卡片风格完全脱节,
 * 所以自己搭一个: 圆角卡片 + 主/次按钮 + 自绘进度条。
 */
object Dialogs {

    private fun shell(a: Activity): Pair<Dialog, LinearLayout> {
        val card = Ui.card(a).apply {
            val p = Ui.dp(a, 22)
            setPadding(p, p, p, Ui.dp(a, 16))
        }
        val dialog = Dialog(a).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(FrameWrap(a, card))
            window?.setBackgroundDrawable(ColorDrawable(0xB0000000.toInt()))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return dialog to card
    }

    /** 给卡片套一层居中容器, 并留出左右边距 */
    private class FrameWrap(a: Activity, child: View) : LinearLayout(a) {
        init {
            gravity = Gravity.CENTER
            val m = Ui.dp(a, 28)
            setPadding(m, m, m, m)
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** 信息/确认对话框 */
    fun confirm(
        a: Activity, title: String, message: String,
        positive: String, onPositive: () -> Unit,
        negative: String? = "以后再说",
    ): Dialog {
        val (dialog, card) = shell(a)
        card.addView(Ui.h2(a, title))
        card.addView(TextView(a).apply {
            text = message
            setTextColor(Ui.MUTED)
            textSize = 13.5f
            setLineSpacing(0f, 1.4f)
            setPadding(0, Ui.dp(a, 12), 0, 0)
        })
        card.addView(Ui.primaryButton(a, positive).apply {
            setOnClickListener { dialog.dismiss(); onPositive() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 48)).apply {
            topMargin = Ui.dp(a, 22)
        })
        if (negative != null) {
            card.addView(Ui.flatButton(a, negative, Ui.MUTED).apply {
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 42)))
        }
        dialog.show()
        return dialog
    }

    /** 带发布说明的更新对话框: 说明可能较长, 单独滚动 */
    fun update(
        a: Activity, version: String, current: String, notes: String,
        onConfirm: () -> Unit,
    ): Dialog {
        val (dialog, card) = shell(a)
        card.addView(Ui.h2(a, "发现新版本 v$version"))
        card.addView(Ui.subtitle(a, "当前 v$current  ·  将在应用内下载并自动安装").apply {
            setPadding(0, Ui.dp(a, 6), 0, 0)
        })
        if (notes.isNotBlank()) {
            card.addView(ScrollView(a).apply {
                background = Ui.rounded(a, Ui.SURFACE2, 12, Ui.BORDER)
                val p = Ui.dp(a, 12)
                setPadding(p, p, p, p)
                addView(TextView(a).apply {
                    text = notes
                    setTextColor(Ui.MUTED)
                    textSize = 12.5f
                    setLineSpacing(0f, 1.35f)
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 150)).apply {
                topMargin = Ui.dp(a, 16)
            })
        }
        card.addView(Ui.primaryButton(a, "立即更新").apply {
            setOnClickListener { dialog.dismiss(); onConfirm() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 48)).apply {
            topMargin = Ui.dp(a, 20)
        })
        card.addView(Ui.flatButton(a, "以后再说", Ui.MUTED).apply {
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 42)))
        dialog.show()
        return dialog
    }

    /** 下载进度对话框; 返回句柄供更新进度与关闭 */
    class Progress(private val dialog: Dialog, private val label: TextView, private val bar: BarView) {
        fun update(done: Long, total: Long) {
            if (total > 0) {
                bar.progress = done.toFloat() / total
                label.text = "正在下载  %.1f MB / %.1f MB".format(done / 1048576f, total / 1048576f)
            } else {
                label.text = "正在下载  %.1f MB".format(done / 1048576f)
            }
        }

        fun dismiss() = runCatching { dialog.dismiss() }
    }

    fun progress(a: Activity, title: String, onCancel: () -> Unit): Progress {
        val (dialog, card) = shell(a)
        card.addView(Ui.h2(a, title))
        val label = TextView(a).apply {
            text = "正在准备…"
            setTextColor(Ui.MUTED)
            textSize = 13f
            setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12))
        }
        val bar = BarView(a)
        card.addView(label)
        card.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 8)))
        card.addView(Ui.flatButton(a, "取消", Ui.MUTED).apply {
            setOnClickListener { dialog.dismiss(); onCancel() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 42)).apply {
            topMargin = Ui.dp(a, 14)
        })
        dialog.setCancelable(false)
        dialog.show()
        return Progress(dialog, label, bar)
    }

    /** 自绘进度条: 系统 ProgressBar 的观感和这套设计不搭 */
    class BarView(a: Activity) : View(a) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE2 }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
        var progress: Float = 0f
            set(v) {
                field = v.coerceIn(0f, 1f)
                postInvalidateOnAnimation()
            }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, track)
            if (progress > 0f) {
                canvas.drawRoundRect(
                    RectF(0f, 0f, width * progress, height.toFloat()), r, r, fill)
            }
        }
    }
}
