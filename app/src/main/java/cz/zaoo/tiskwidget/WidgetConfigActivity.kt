package cz.zaoo.tiskwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch

/** Nastavení widgetu – otevře se podržením widgetu (Upravit) nebo z Nastavení. */
class WidgetConfigActivity : AppCompatActivity() {
    private var wid = AppWidgetManager.INVALID_APPWIDGET_ID
    private val values = mutableMapOf<String, Boolean>()
    private var shape = "neon_auto"
    private var theme = "neon"
    private var printerId = ""
    private lateinit var preview: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wid = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))

        WidgetOpts.ALL.forEach { values[it.key] = WidgetOpts.get(this, wid, it.key) }
        shape = WidgetOpts.shape(this, wid)
        theme = WidgetOpts.theme(this, wid)
        val printers = Printers.all(this)
        printerId = WidgetOpts.printer(this, wid).takeIf { id -> printers.any { it.id == id } } ?: printers.firstOrNull()?.id ?: ""

        val c = Ui.page(this, if (wid >= 0) "Upravit widget" else "Výchozí vzhled widgetu", true)

        // Náhled na „ploše“
        preview = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2E4A34.toInt(), 0xFF1D2F3A.toInt())).apply {
                cornerRadius = Ui.dpf(this@WidgetConfigActivity, 22f)
            }
            val pd = Ui.dp(this@WidgetConfigActivity, 14)
            setPadding(pd, pd, pd, pd)
            minimumHeight = Ui.dp(this@WidgetConfigActivity, 150)
        }
        c.addView(preview, Ui.lp(this, 4, 4))
        c.addView(Ui.text(this, "Widget se sám přizpůsobí velikosti na ploše – písmo i počet řádků. Velikost změníš podržením widgetu a tažením za okraje.", 12f, Ui.GREY).apply {
            setPadding(Ui.dp(this@WidgetConfigActivity, 4), Ui.dp(this@WidgetConfigActivity, 4), 0, 0)
        })

        if (printers.size > 1) {
            c.addView(Ui.section(this, "Tiskárna"))
            c.addView(chips(printers.map { it.id to it.name }, printerId, null) { printerId = it; updatePreview() })
        }
        c.addView(Ui.section(this, "Tvar"))
        c.addView(chips(WidgetOpts.SHAPES.map { it.first to it.second }, shape, null) { shape = it; updatePreview() })
        c.addView(Ui.section(this, "Barvy"))
        c.addView(chips(Themes.ALL.map { it.key to it.label }, theme, Themes.ALL.associate { it.key to it.a }) { theme = it; updatePreview() })
        c.addView(Ui.text(this, "Barvy se projeví hlavně u futuristického vzhledu; u klasického barví průběh.", 12f, Ui.GREY).apply {
            setPadding(Ui.dp(this@WidgetConfigActivity, 4), Ui.dp(this@WidgetConfigActivity, 6), 0, 0)
        })

        c.addView(Ui.section(this, "Co ukazovat"))
        val (oc, oi) = Ui.card(this, 8)
        WidgetOpts.ALL.forEach { o ->
            oi.addView(MaterialSwitch(this).apply {
                text = o.label
                textSize = 16f
                setTextColor(Ui.TEXT)
                isChecked = values[o.key] == true
                setPadding(Ui.dp(this@WidgetConfigActivity, 10), Ui.dp(this@WidgetConfigActivity, 4), Ui.dp(this@WidgetConfigActivity, 6), Ui.dp(this@WidgetConfigActivity, 4))
                setOnCheckedChangeListener { _, ch -> values[o.key] = ch; updatePreview() }
            })
        }
        c.addView(oc, Ui.lp(this, 0, 16))
        c.addView(Ui.button(this, "Uložit") { save() }, Ui.lp(this, 0, 8))
        if (printerId.isNotEmpty()) c.addView(Ui.button(this, "Otevřít tiskárnu (stav, ovládání, AMS, historie)", "text") {
            startActivity(Intent(this, PrinterActivity::class.java).putExtra("id", printerId))
        })
        updatePreview()
    }

    private fun chips(items: List<Pair<String, String>>, selected: String, dots: Map<String, Int>?, onPick: (String) -> Unit): ChipGroup {
        val g = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        for ((key, label) in items) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isCheckedIconVisible = false
                tag = key
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF0B3D22.toInt(), Ui.SURFACE2))
                setTextColor(Ui.TEXT)
                chipStrokeWidth = 0f
                dots?.get(key)?.let { col ->
                    chipIcon = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
                    isChipIconVisible = true
                }
            }
            g.addView(chip)
            if (key == selected) chip.isChecked = true
        }
        g.setOnCheckedStateChangeListener { grp, ids ->
            val ch = ids.firstOrNull()?.let { grp.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            onPick(ch.tag as String)
        }
        return g
    }

    private fun updatePreview() {
        preview.removeAllViews()
        try {
            val sh = PrintWidget.resolve(shape, 0, 0)
            val (pw, ph) = when (sh) {
                "neon_panel" -> 320 to 110; "neon_ring" -> 170 to 170; "rect" -> 320 to 84
                "strip" -> 320 to 48; else -> 150 to 150
            }
            val p = Printers.get(this, printerId)
            val rv = PrintWidget.build(this, p, { values[it] ?: true }, sh, theme, pw, ph)
            val view = rv.apply(this, preview)
            view.setOnClickListener(null)
            val lp = FrameLayout.LayoutParams(Ui.dp(this, pw), Ui.dp(this, ph))
            lp.gravity = Gravity.CENTER
            preview.addView(view, lp)
        } catch (_: Exception) {}
    }

    private fun save() {
        values.forEach { (k, v) -> WidgetOpts.set(this, wid, k, v) }
        WidgetOpts.setShape(this, wid, shape)
        WidgetOpts.setTheme(this, wid, theme)
        if (wid >= 0 && printerId.isNotEmpty()) WidgetOpts.setPrinter(this, wid, printerId)
        PrintWidget.render(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        finish()
    }
}
