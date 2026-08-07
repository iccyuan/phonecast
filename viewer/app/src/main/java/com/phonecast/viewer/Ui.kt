package com.phonecast.viewer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 深色主题的纯代码样式 (不引第三方 UI 库)。 */
object Ui {
    const val BG = 0xFF10131A.toInt()
    const val CARD = 0xFF1A1E27.toInt()
    const val FIELD = 0xFF232936.toInt()
    const val BORDER = 0xFF2A3140.toInt()
    const val ACCENT = 0xFF2D7DFF.toInt()
    const val ACCENT_DARK = 0xFF1E5FD0.toInt()
    const val TEXT = 0xFFE6E9F0.toInt()
    const val MUTED = 0xFF8A93A6.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * 3 // 近似 dp→px, 由调用处的 density 决定精度要求不高
            if (strokeColor != 0) setStroke(strokeDp, strokeColor)
        }

    private fun roundedPx(c: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(c, strokeDp).coerceAtLeast(1), strokeColor)
        }

    fun title(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun subtitle(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    fun label(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(c, 2), dp(c, 14), 0, dp(c, 6))
    }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedPx(c, CARD, 16)
        val p = dp(c, 20)
        setPadding(p, dp(c, 8), p, p)
    }

    fun field(c: Context, hintText: String): EditText = EditText(c).apply {
        hint = hintText
        setTextColor(TEXT)
        setHintTextColor(0xFF525B6E.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), roundedPx(c, FIELD, 10, ACCENT, 2))
            addState(intArrayOf(), roundedPx(c, FIELD, 10, BORDER, 1))
        }
        val ph = dp(c, 14)
        setPadding(ph, dp(c, 12), ph, dp(c, 12))
        backgroundTintList = null
    }

    fun primaryButton(c: Context, text: String): Button = Button(c).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        stateListAnimator = null
        background = RippleDrawable(
            ColorStateList.valueOf(ACCENT_DARK),
            roundedPx(c, ACCENT, 12), roundedPx(c, Color.WHITE, 12))
    }

    fun flatButton(c: Context, text: String, color: Int = ACCENT): Button = Button(c).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isAllCaps = false
        stateListAnimator = null
        background = RippleDrawable(
            ColorStateList.valueOf(0x332D7DFF),
            null, roundedPx(c, Color.WHITE, 12))
    }

    /** 列表条目背景: 卡片色 + 按压涟漪 */
    fun rowBackground(c: Context): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(0x332D7DFF),
        roundedPx(c, CARD, 14), roundedPx(c, android.graphics.Color.WHITE, 14))

    fun hint(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.3f)
    }
}
