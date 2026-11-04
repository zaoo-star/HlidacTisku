package cz.zaoo.tiskwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Nastavení widgetu – otevře se podržením widgetu (Upravit) nebo z appky. */
class WidgetConfigActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val values = mutableMapOf<String, Boolean>()
    private lateinit var preview: FrameLayout
    private var shape = "rect"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Kdyby uživatel odešel zpět, widget při přidávání zůstane s výchozím nastavením
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        WidgetOpts.ALL.forEach { values[it.key] = WidgetOpts.get(this, it.key) }
        shape = WidgetOpts.shape(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }
        root.addView(TextView(this).apply {
            text = "Co má widget ukazovat"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Náhled:"
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(12), 0, dp(6))
        })
        preview = FrameLayout(this).apply { setBackgroundColor(0xFF3A5A3A.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        root.addView(preview, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Tvar"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(2))
        })
        val rg = RadioGroup(this)
        WidgetOpts.SHAPES.forEach { (key, label, _) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                tag = key
            }
            rg.addView(rb)
            if (key == shape) rb.isChecked = true
        }
        rg.setOnCheckedChangeListener { g, id ->
            shape = g.findViewById<RadioButton>(id)?.tag as? String ?: "rect"
            updatePreview()
        }
        root.addView(rg)
        root.addView(TextView(this).apply {
            text = "Co ukazovat"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(2))
        })

        WidgetOpts.ALL.forEach { o ->
            root.addView(Switch(this).apply {
                text = o.label
                textSize = 16f
                setTextColor(Color.WHITE)
                isChecked = values[o.key] == true
                setPadding(0, dp(10), 0, dp(10))
                setOnCheckedChangeListener { _, c -> values[o.key] = c; updatePreview() }
            })
        }
        root.addView(TextView(this).apply {
            text = "Po změně tvaru uprav i velikost na ploše: podrž widget a táhni za okraje (čtverec a kruh 2×2, pruh a obdélník 3×1)."
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 13f
            setPadding(0, dp(12), 0, dp(12))
        })
        root.addView(Button(this).apply {
            text = "Uložit"
            isAllCaps = false
            setOnClickListener { save() }
        })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFF121416.toInt()) })
        updatePreview()
    }

    private fun updatePreview() {
        preview.removeAllViews()
        try {
            val rv = PrintWidget.build(this, { values[it] ?: true }, shape)
            val view = rv.apply(this, preview)
            view.setOnClickListener(null)
            val lp = when (shape) {
                "square", "circle" -> FrameLayout.LayoutParams(dp(150), dp(150))
                "strip" -> FrameLayout.LayoutParams(-1, dp(48))
                else -> FrameLayout.LayoutParams(-1, dp(84))
            }
            lp.gravity = Gravity.CENTER
            preview.addView(view, lp)
        } catch (_: Exception) {}
    }

    private fun save() {
        values.forEach { (k, v) -> WidgetOpts.set(this, k, v) }
        WidgetOpts.setShape(this, shape)
        PrintWidget.render(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
