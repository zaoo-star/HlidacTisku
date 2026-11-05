package cz.zaoo.tiskwidget

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Historie tisků z Bambu cloudu + součty a nacenění (cena filamentu a provozu). */
class HistoryActivity : Activity() {
    private lateinit var store: Store
    private lateinit var summary: TextView
    private lateinit var list: LinearLayout
    private lateinit var kg: EditText
    private lateinit var hour: EditText
    private var tasks: List<Tasks.Task> = emptyList()
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(30))
        }
        root.addView(tv("📊 Historie tisků", 22f, true))

        // Ceny pro nacenění
        root.addView(tv("Nacenění – tvoje ceny:", 15f, true).apply { setPadding(0, dp(14), 0, dp(2)) })
        val prices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kg = EditText(this).apply {
            hint = "Kč za 1 kg filamentu"
            setText(store.priceKg)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        hour = EditText(this).apply {
            hint = "Kč za hodinu tisku"
            setText(store.priceHour)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        prices.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Kč / kg filamentu", 12f, color = 0xFFAAAAAA.toInt())); addView(kg)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        prices.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Kč / hodinu (proud, opotřebení)", 12f, color = 0xFFAAAAAA.toInt())); addView(hour)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(prices)
        root.addView(Button(this).apply {
            text = "Přepočítat"
            isAllCaps = false
            setOnClickListener {
                store.priceKg = kg.text.toString().trim()
                store.priceHour = hour.text.toString().trim()
                show()
            }
        })

        summary = tv("Načítám…", 15f).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF22262A.toInt()) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFF121416.toInt()) })

        if (store.mode != "cloud") {
            summary.text = "Historie je dostupná jen při přihlášení přes Bambu účet (v režimu „Jen doma“ ji tiskárna neposílá)."
            return
        }
        Thread {
            try {
                val t = Tasks.list(store, 100)
                runOnUiThread { tasks = t; show() }
            } catch (e: Exception) {
                runOnUiThread { summary.text = "Historii se nepodařilo načíst: ${e.message}" }
            }
        }.start()
    }

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun cost(t: Tasks.Task): Double =
        t.weight / 1000.0 * num(store.priceKg) + t.costTime / 3600.0 * num(store.priceHour)

    private fun hm(sec: Int): String {
        val m = sec / 60
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    private fun kc(v: Double) = "%.0f Kč".format(v)

    private fun show() {
        if (tasks.isEmpty()) { summary.text = "Zatím žádné tisky."; list.removeAllViews(); return }
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val thisMonth = tasks.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.start }
            c.get(Calendar.MONTH) == month && c.get(Calendar.YEAR) == year
        }
        fun block(name: String, l: List<Tasks.Task>): String {
            val g = l.sumOf { it.weight }
            val sec = l.sumOf { it.costTime }
            return "$name: ${l.size} tisků · ${hm(sec)} · %.0f g · ${kc(l.sumOf { cost(it) })}".format(g)
        }
        summary.text = block("Tento měsíc", thisMonth) + "\n" +
            block("Celkem (posl. ${tasks.size})", tasks) +
            "\n\nCena = filament (g × Kč/kg) + čas (h × Kč/h). Zisk si přidej navrch 😉"

        list.removeAllViews()
        val df = SimpleDateFormat("d. M. HH:mm", Locale.getDefault())
        for (t in tasks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF2A2E33.toInt()) }
                clipToOutline = true
            }
            row.addView(img, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
            val icon = when (t.status) { 2 -> "✅ "; 3 -> "❌ "; else -> "" }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(tv(icon + t.title.ifEmpty { "(bez názvu)" }, 15f, true).apply { maxLines = 2 })
            val date = if (t.start > 0) df.format(Date(t.start)) else ""
            texts.addView(tv("$date · ${hm(t.costTime)} · %.0f g".format(t.weight), 13f, color = 0xFFBBBBBB.toInt()))
            texts.addView(tv("≈ ${kc(cost(t))}", 14f, color = 0xFF00C853.toInt()))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(row)

            if (t.cover.isNotEmpty()) {
                val cached = thumbs[t.cover]
                if (cached != null) img.setImageBitmap(cached)
                else pool.execute {
                    val b = Tasks.download(t.cover, 160) ?: return@execute
                    thumbs[t.cover] = b
                    runOnUiThread { img.setImageBitmap(b) }
                }
            }
        }
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }
}
