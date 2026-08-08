package com.phonecast.viewer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设计系统: 颜色 / 字号 / 间距 / 组件 / 手绘矢量图标。
 * 纯代码实现, 不引第三方 UI 库, 保证 APK 体积与启动速度。
 */
object Ui {
    // ---- 颜色 ----
    const val BG = 0xFF0E1117.toInt()      // 页面底色
    const val SURFACE = 0xFF161A22.toInt() // 卡片
    const val SURFACE2 = 0xFF1E2430.toInt() // 输入框/图标底
    const val BORDER = 0xFF29303D.toInt()
    const val ACCENT = 0xFF2D7DFF.toInt()
    const val ACCENT_DARK = 0xFF1E5FD0.toInt()
    const val TEXT = 0xFFE8EBF2.toInt()
    const val MUTED = 0xFF8B94A7.toInt()
    const val DIM = 0xFF5A6376.toInt()
    const val SUCCESS = 0xFF34D399.toInt()
    const val WARN = 0xFFF5B759.toInt()
    private const val RIPPLE = 0x332D7DFF

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

    // ---- 基础形状 ----

    fun rounded(c: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(c, radiusDp.toFloat())
            if (strokeColor != 0) setStroke(dp(c, strokeDp).coerceAtLeast(1), strokeColor)
        }

    private fun ripple(c: Context, content: Drawable, radiusDp: Int) = RippleDrawable(
        ColorStateList.valueOf(RIPPLE), content, rounded(c, Color.WHITE, radiusDp))

    // ---- 文字 ----

    fun h1(c: Context, text: String) = TextView(c).apply {
        this.text = text
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun h2(c: Context, text: String) = TextView(c).apply {
        this.text = text
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun subtitle(c: Context, text: String) = TextView(c).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    /** 分组标题: 小字、字间距略开 */
    fun sectionLabel(c: Context, text: String) = TextView(c).apply {
        this.text = text
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(c, 4), dp(c, 20), 0, dp(c, 10))
    }

    /** 表单项标签 */
    fun label(c: Context, text: String) = TextView(c).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(c, 2), dp(c, 16), 0, dp(c, 6))
    }

    fun hint(c: Context, text: String) = TextView(c).apply {
        this.text = text
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.45f)
    }

    // ---- 容器 ----

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, SURFACE, 18, BORDER)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dpf(c, 2f)
        val p = dp(c, 20)
        setPadding(p, dp(c, 6), p, p)
    }

    // ---- 表单 ----

    fun field(c: Context, hintText: String): EditText = EditText(c).apply {
        hint = hintText
        setTextColor(TEXT)
        setHintTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(c, SURFACE2, 12, ACCENT, 2))
            addState(intArrayOf(), rounded(c, SURFACE2, 12, BORDER))
        }
        backgroundTintList = null
        val ph = dp(c, 14)
        setPadding(ph, dp(c, 13), ph, dp(c, 13))
    }

    fun primaryButton(c: Context, text: String): Button = Button(c).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        stateListAnimator = null
        background = ripple(c, rounded(c, ACCENT, 14), 14)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dpf(c, 3f)
    }

    /** 次级按钮: 无填充, 只有文字与涟漪 */
    fun flatButton(c: Context, text: String, color: Int = ACCENT): Button = Button(c).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isAllCaps = false
        stateListAnimator = null
        background = RippleDrawable(
            ColorStateList.valueOf(RIPPLE), null, rounded(c, Color.WHITE, 12))
    }

    // ---- 顶栏 ----

    /**
     * 页面顶栏: 圆形图标返回键 + 标题。
     * 返回键做成 40dp 圆形触控区并带涟漪, 与随手加的文字按钮不是一个量级。
     */
    fun toolbar(c: Context, title: String, onBack: () -> Unit): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(c, Icons.Back(c), "返回", onBack),
                LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)).apply {
                    leftMargin = -dp(c, 8) // 图标视觉左对齐正文
                })
            addView(h2(c, title), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(c, 8) })
        }

    /** 圆形图标按钮 */
    fun iconButton(c: Context, icon: Drawable, desc: String, onClick: () -> Unit): View =
        View(c).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(RIPPLE), icon, rounded(c, Color.WHITE, 20))
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    // ---- 列表项 ----

    fun rowBackground(c: Context): Drawable = ripple(c, rounded(c, SURFACE, 16, BORDER), 16)

    /** 圆角方形图标底 (列表左侧) */
    fun iconTile(c: Context, icon: Drawable, tint: Int): FrameLayout = FrameLayout(c).apply {
        background = rounded(c, tint and 0x22FFFFFF, 12)
        addView(View(c).apply { background = icon }, FrameLayout.LayoutParams(
            dp(c, 22), dp(c, 22), Gravity.CENTER))
    }

    /**
     * 圆形按钮背景: 实心圆 + 居中图标。不带水波纹 ——
     * 悬浮按钮靠按压缩放(见 addPressFeedback)反馈, 涟漪在圆形上会显得生硬。
     */
    fun circleButton(c: Context, icon: Drawable, fillColor: Int, insetDp: Int): Drawable {
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }
        val content = android.graphics.drawable.LayerDrawable(arrayOf(oval, icon))
        val inset = dp(c, insetDp)
        content.setLayerInset(1, inset, inset, inset, inset)
        return content
    }

    fun fabBackground(c: Context): Drawable = circleButton(c, Icons.Plus(c), ACCENT, 15)

    /** 速拨菜单里的文字标签: 深色药丸 */
    fun pill(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        background = rounded(c, SURFACE, 10, BORDER)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dpf(c, 4f)
        val ph = dp(c, 12)
        setPadding(ph, dp(c, 8), ph, dp(c, 8))
    }

    /** 状态圆点 */
    fun dot(c: Context, color: Int): View = View(c).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }
}

/**
 * 手绘矢量图标: 全部按 bounds 归一化绘制, 任意尺寸都清晰, 且不占 APK 体积。
 */
object Icons {
    // 全套图标共用: 2dp 圆头描边, 保证风格一致
    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        strokeWidth = w
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    abstract class Base(protected val color: Int) : Drawable() {
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("deprecated in API 29", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** 返回: 左向尖括号 */
    class Back(c: Context, color: Int = Ui.TEXT) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2f))
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val s = minOf(b.width(), b.height()) * 0.20f
            path.reset()
            path.moveTo(cx + s * 0.5f, cy - s)
            path.lineTo(cx - s * 0.5f, cy)
            path.lineTo(cx + s * 0.5f, cy + s)
            canvas.drawPath(path, p)
        }
    }

    /** 右向尖括号 (列表项末尾的可进入提示) */
    class ChevronRight(c: Context, color: Int = Ui.DIM) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 1.8f))
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val s = minOf(b.width(), b.height()) * 0.22f
            path.reset()
            path.moveTo(cx - s * 0.5f, cy - s)
            path.lineTo(cx + s * 0.5f, cy)
            path.lineTo(cx - s * 0.5f, cy + s)
            canvas.drawPath(path, p)
        }
    }

    /** 系统导航「返回」: Android 经典左向三角 */
    class NavBack(color: Int = Ui.TEXT) : Base(color) {
        private val p = fill(color)
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val s = minOf(b.width(), b.height()) * 0.30f
            path.reset()
            path.moveTo(cx - s, cy)
            path.lineTo(cx + s * 0.8f, cy - s)
            path.lineTo(cx + s * 0.8f, cy + s)
            path.close()
            canvas.drawPath(path, p)
        }
    }

    /** 系统导航「主页」: 圆 */
    class NavHome(c: Context, color: Int = Ui.TEXT) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2f))
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(),
                minOf(b.width(), b.height()) * 0.28f, p)
        }
    }

    /** 系统导航「多任务」: 圆角方 */
    class NavRecents(c: Context, color: Int = Ui.TEXT) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2f))
        private val r = Ui.dpf(c, 2f)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()) * 0.26f
            canvas.drawRoundRect(RectF(b.exactCenterX() - s, b.exactCenterY() - s,
                b.exactCenterX() + s, b.exactCenterY() + s), r, r, p)
        }
    }

    /** 断开: ✕ */
    class Close(c: Context, color: Int = Ui.MUTED) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2f))
        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val s = minOf(b.width(), b.height()) * 0.24f
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, p)
            canvas.drawLine(cx + s, cy - s, cx - s, cy + s, p)
        }
    }

    /** 加号 */
    class Plus(c: Context, color: Int = Color.WHITE) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2.4f))
        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val s = minOf(b.width(), b.height()) * 0.22f
            canvas.drawLine(cx - s, cy, cx + s, cy, p)
            canvas.drawLine(cx, cy - s, cx, cy + s, p)
        }
    }

    /** 扫码: 取景框四角 + 中间扫描线 */
    class Scan(c: Context, color: Int = Color.WHITE) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2f))
        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()) * 0.30f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val arm = s * 0.5f
            for (sx in listOf(cx - s, cx + s)) {
                for (sy in listOf(cy - s, cy + s)) {
                    val dx = if (sx < cx) arm else -arm
                    val dy = if (sy < cy) arm else -arm
                    canvas.drawLine(sx, sy, sx + dx, sy, p)
                    canvas.drawLine(sx, sy, sx, sy + dy, p)
                }
            }
            canvas.drawLine(cx - s, cy, cx + s, cy, p)
        }
    }

    /** 声音开: 喇叭 + 两道声波 */
    class SoundOn(c: Context, color: Int = Color.WHITE) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2f))
        private val fillP = fill(color)
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()) * 0.30f
            val cx = b.exactCenterX() - s * 0.25f
            val cy = b.exactCenterY()
            path.reset()
            path.moveTo(cx - s * 0.7f, cy - s * 0.35f)
            path.lineTo(cx - s * 0.25f, cy - s * 0.35f)
            path.lineTo(cx + s * 0.3f, cy - s)
            path.lineTo(cx + s * 0.3f, cy + s)
            path.lineTo(cx - s * 0.25f, cy + s * 0.35f)
            path.lineTo(cx - s * 0.7f, cy + s * 0.35f)
            path.close()
            canvas.drawPath(path, fillP)
            for (r in listOf(s * 0.75f, s * 1.15f)) {
                canvas.drawArc(RectF(cx + s * 0.3f - r, cy - r, cx + s * 0.3f + r, cy + r),
                    -55f, 110f, false, p)
            }
        }
    }

    /** 声音关: 喇叭 + 叉 */
    class SoundOff(c: Context, color: Int = Color.WHITE) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2f))
        private val fillP = fill(color)
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()) * 0.30f
            val cx = b.exactCenterX() - s * 0.3f
            val cy = b.exactCenterY()
            path.reset()
            path.moveTo(cx - s * 0.7f, cy - s * 0.35f)
            path.lineTo(cx - s * 0.25f, cy - s * 0.35f)
            path.lineTo(cx + s * 0.3f, cy - s)
            path.lineTo(cx + s * 0.3f, cy + s)
            path.lineTo(cx - s * 0.25f, cy + s * 0.35f)
            path.lineTo(cx - s * 0.7f, cy + s * 0.35f)
            path.close()
            canvas.drawPath(path, fillP)
            val x0 = cx + s * 0.7f
            canvas.drawLine(x0, cy - s * 0.45f, x0 + s * 0.9f, cy + s * 0.45f, p)
            canvas.drawLine(x0 + s * 0.9f, cy - s * 0.45f, x0, cy + s * 0.45f, p)
        }
    }

    /** 检查更新: 带箭头的环形 */
    class Refresh(c: Context, color: Int = Color.WHITE) : Base(color) {
        private val ring = stroke(color, Ui.dpf(c, 2f))
        private val head = fill(color)
        private val arrow = Ui.dpf(c, 3.2f)
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = minOf(b.width(), b.height()) * 0.30f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -50f, 280f, false, ring)
            val a = arrow // 箭头
            path.reset()
            path.moveTo(cx + r, cy - a)
            path.lineTo(cx + r + a, cy + a * 0.8f)
            path.lineTo(cx + r - a, cy + a * 0.8f)
            path.close()
            canvas.drawPath(path, head)
        }
    }

    /** 手动填写: 键盘 (与扫码/更新同为几何描边风格) */
    class Edit(c: Context, color: Int = Color.WHITE) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, 2f))
        private val r = Ui.dpf(c, 2.5f)
        private val dotCap = Ui.dpf(c, 2f)
        override fun draw(canvas: Canvas) {
            val b = bounds
            p.strokeWidth = dotCap
            val w = minOf(b.width(), b.height()) * 0.36f
            val h = w * 0.66f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            canvas.drawRoundRect(RectF(cx - w, cy - h, cx + w, cy + h), r, r, p)
            // 两排键位 + 底部空格
            val keyY1 = cy - h * 0.28f
            val keyY2 = cy + h * 0.06f
            val step = w * 0.5f
            for (x in listOf(cx - step, cx, cx + step)) {
                canvas.drawPoint(x, keyY1, p)
                canvas.drawPoint(x, keyY2, p)
            }
            canvas.drawLine(cx - step * 0.7f, cy + h * 0.45f, cx + step * 0.7f, cy + h * 0.45f, p)
        }
    }

    /** 手机轮廓 (列表左侧图标 / 空状态插画) */
    class Phone(c: Context, color: Int = Ui.ACCENT, private val strokeDp: Float = 1.8f) : Base(color) {
        private val p = stroke(color, Ui.dpf(c, strokeDp))
        private val r = Ui.dpf(c, 3f)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = minOf(b.width(), b.height()) * 0.30f
            val h = minOf(b.width(), b.height()) * 0.42f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            canvas.drawRoundRect(RectF(cx - w, cy - h, cx + w, cy + h), r, r, p)
            // 底部指示条
            canvas.drawLine(cx - w * 0.35f, cy + h * 0.72f, cx + w * 0.35f, cy + h * 0.72f, p)
        }
    }

    /** 品牌 Logo: 圆角蓝底 + 白色播放三角 (与启动器/托盘图标同款) */
    class Logo : Base(Ui.ACCENT) {
        private val bg = fill(Ui.ACCENT)
        private val fg = fill(Color.WHITE)
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = b.width() * 0.24f
            canvas.drawRoundRect(RectF(b), r, r, bg)
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val h = b.height() * 0.26f
            path.reset()
            path.moveTo(cx - h * 0.7f, cy - h)
            path.lineTo(cx + h * 0.95f, cy)
            path.lineTo(cx - h * 0.7f, cy + h)
            path.close()
            canvas.drawPath(path, fg)
        }
    }
}
