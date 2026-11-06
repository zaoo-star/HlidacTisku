package cz.zaoo.tiskwidget

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Pomocníci pro jednotný vzhled appky (vše se skládá v kódu, bez XML). */
object Ui {
    const val BG = 0xFF0F1215.toInt()
    const val SURFACE = 0xFF1A1F24.toInt()
    const val SURFACE2 = 0xFF242B32.toInt()
    const val TEXT = 0xFFE8EDF2.toInt()
    const val MUTED = 0xFF9AA6B2.toInt()
    const val GREEN = 0xFF00D26A.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val AMBER = 0xFFFFB300.toInt()
    const val RED = 0xFFFF4D5E.toInt()
    const val GREY = 0xFF6B7782.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    fun stateColor(s: Status): Int = when {
        Fmt.offline(s) -> GREY
        s.hasError -> RED
        !s.connected && s.state.isEmpty() -> GREY
        s.state == "RUNNING" || s.state == "PREPARE" -> GREEN
        s.state == "PAUSE" -> AMBER
        s.state == "FINISH" -> CYAN
        s.state == "FAILED" -> RED
        else -> GREY
    }

    fun text(ctx: Context, t: CharSequence = "", size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.1f)
        }

    fun section(ctx: Context, t: String) = text(ctx, t.uppercase(), 12f, MUTED, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(ctx, 4), dp(ctx, 20), 0, dp(ctx, 8))
    }

    fun vbox(ctx: Context, padDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
    }

    fun hbox(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(ctx: Context, padDp: Int = 16): Pair<MaterialCardView, LinearLayout> {
        val c = MaterialCardView(ctx).apply {
            radius = dpf(ctx, 22f)
            setCardBackgroundColor(SURFACE)
            strokeWidth = 0
            cardElevation = 0f
            useCompatPadding = false
        }
        val inner = vbox(ctx, padDp)
        c.addView(inner)
        return c to inner
    }

    fun lp(ctx: Context, topDp: Int = 0, bottomDp: Int = 12) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, topDp); bottomMargin = dp(ctx, bottomDp)
        }

    fun weight(ctx: Context, marginDp: Int = 4) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(ctx, marginDp); marginEnd = dp(ctx, marginDp)
    }

    fun button(ctx: Context, t: String, kind: String = "filled", onClick: () -> Unit): MaterialButton {
        val b = if (kind == "outlined") MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                else if (kind == "text") MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle)
                else MaterialButton(ctx)
        b.text = t
        b.isAllCaps = false
        b.textSize = 15f
        b.cornerRadius = dp(ctx, 14)
        b.minHeight = dp(ctx, 52)
        if (kind == "tonal") {
            b.backgroundTintList = ColorStateList.valueOf(SURFACE2)
            b.setTextColor(TEXT)
        }
        b.setOnClickListener { onClick() }
        return b
    }

    fun input(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, helper: String? = null): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        til.setBoxCornerRadii(dpf(ctx, 12f), dpf(ctx, 12f), dpf(ctx, 12f), dpf(ctx, 12f))
        if (helper != null) til.helperText = helper
        val et = TextInputEditText(til.context)
        et.setText(value)
        et.inputType = type
        et.isSingleLine = true
        til.addView(et)
        return til to et
    }

    fun progress(ctx: Context) = LinearProgressIndicator(ctx).apply {
        max = 100
        trackThickness = dp(ctx, 8)
        trackCornerRadius = dp(ctx, 4)
        trackColor = SURFACE2
        setIndicatorColor(GREEN)
    }

    fun pill(ctx: Context, t: String, color: Int) = text(ctx, t, 12f, color, true).apply {
        background = GradientDrawable().apply {
            cornerRadius = dpf(ctx, 20f)
            setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
        }
        setPadding(dp(ctx, 10), dp(ctx, 3), dp(ctx, 10), dp(ctx, 3))
    }

    /** Čtvercový obrázek se zaoblenými rohy (náhled modelu / značka). */
    fun thumb(ctx: Context, sizeDp: Int) = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { cornerRadius = dpf(ctx, 16f); setColor(SURFACE2) }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    /** Stránka: horní lišta + posouvatelný obsah. Vrací kontejner pro obsah. */
    fun page(a: Activity, title: String, back: Boolean, menu: (MaterialToolbar.() -> Unit)? = null): LinearLayout {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val tb = MaterialToolbar(a).apply {
            this.title = title
            setTitleTextColor(TEXT)
            setBackgroundColor(BG)
            if (back) {
                setNavigationIcon(R.drawable.ic_back)
                setNavigationOnClickListener { a.finish() }
            }
        }
        menu?.invoke(tb)
        root.addView(tb)
        val content = vbox(a).apply { setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 100)) }
        val scroll = ScrollView(a).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val frame = FrameLayout(a)
        frame.addView(root)
        // Android 15 kreslí appku až pod horní lištu s hodinami → odsadit podle systémových lišt (i klávesnice)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        a.setContentView(frame)
        content.tag = frame
        return content
    }

    fun frameOf(content: LinearLayout) = content.tag as FrameLayout

    fun gone(v: View, hide: Boolean) { v.visibility = if (hide) View.GONE else View.VISIBLE }
}
