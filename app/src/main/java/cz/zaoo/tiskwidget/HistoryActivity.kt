package cz.zaoo.tiskwidget

import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Historie tisků (Bambu cloud nebo Klipper) se součty a naceněním. */
class HistoryActivity : AppCompatActivity() {
    data class Item(val title: String, val ok: Boolean?, val start: Long, val sec: Int, val grams: Double, val image: (() -> Bitmap?)?)

    private lateinit var store: Store
    private lateinit var p: PrinterCfg
    private lateinit var summary: android.widget.TextView
    private lateinit var list: LinearLayout
    private var items: List<Item> = emptyList()
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = ConcurrentHashMap<Int, Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        p = Printers.get(this, intent.getStringExtra("id")) ?: run { finish(); return }
        val c = Ui.page(this, "Historie – ${p.name}", true)
        val (sc, si) = Ui.card(this, 16)
        summary = Ui.text(this, "Načítám…", 15f)
        si.addView(summary)
        c.addView(sc, Ui.lp(this, 4, 8))
        c.addView(Ui.text(this, "Ceny pro nacenění změníš v Nastavení → Nacenění tisků.", 12f, Ui.GREY).apply {
            setPadding(Ui.dp(this@HistoryActivity, 6), 0, 0, Ui.dp(this@HistoryActivity, 8))
        })
        list = Ui.vbox(this)
        c.addView(list)

        Thread {
            try {
                val it = load()
                runOnUiThread { items = it; show() }
            } catch (e: Exception) {
                runOnUiThread { summary.text = "Historii se nepodařilo načíst: ${e.message}" }
            }
        }.start()
    }

    private fun load(): List<Item> = when (p.type) {
        PrinterCfg.BAMBU_CLOUD -> Tasks.list(store.token, p.serial, 100).map { t ->
            Item(t.title, when (t.status) { 2 -> true; 3 -> false; else -> null }, t.start, t.costTime, t.weight,
                if (t.cover.isNotEmpty()) ({ Tasks.download(t.cover, 160) }) else null)
        }
        PrinterCfg.KLIPPER -> {
            val r = Http.request(p, "GET", "/server/history/list?limit=100&order=desc")
            if (r.code !in 200..299) throw Exception("HTTP ${r.code}")
            val jobs = JSONObject(r.body).optJSONObject("result")?.optJSONArray("jobs")
            (0 until (jobs?.length() ?: 0)).mapNotNull { jobs?.optJSONObject(it) }.map { j ->
                val file = j.optString("filename")
                val meta = j.optJSONObject("metadata")
                val th = meta?.optJSONArray("thumbnails")
                var best: JSONObject? = null
                if (th != null) for (k in 0 until th.length()) {
                    val t = th.optJSONObject(k) ?: continue
                    if (best == null || t.optInt("width") > best.optInt("width")) best = t
                }
                val dir = file.substringBeforeLast('/', "")
                val rel = best?.optString("relative_path", "") ?: ""
                val path = if (rel.isEmpty()) "" else if (dir.isEmpty()) rel else "$dir/$rel"
                val mm = j.optDouble("filament_used", 0.0)
                Item(file.substringAfterLast('/').removeSuffix(".gcode"),
                    when (j.optString("status")) { "completed" -> true; "in_progress" -> null; else -> false },
                    (j.optDouble("start_time", 0.0) * 1000).toLong(),
                    j.optDouble("print_duration", 0.0).toInt(),
                    mm * 0.002405 * 1.24,  // 1,75 mm PLA: mm → gramy
                    if (path.isNotEmpty()) ({ Http.image(p, "/server/files/gcodes/" + Http.enc(path), 160) }) else null)
            }
        }
        else -> emptyList()
    }

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    private fun cost(t: Item) = t.grams / 1000.0 * num(store.priceKg) + t.sec / 3600.0 * num(store.priceHour)
    private fun hm(sec: Int): String { val m = sec / 60; return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }
    private fun kc(v: Double) = "%.0f Kč".format(v)

    private fun show() {
        if (items.isEmpty()) { summary.text = "Zatím žádné tisky."; return }
        val cal = Calendar.getInstance()
        val month = items.filter {
            val c2 = Calendar.getInstance().apply { timeInMillis = it.start }
            c2.get(Calendar.MONTH) == cal.get(Calendar.MONTH) && c2.get(Calendar.YEAR) == cal.get(Calendar.YEAR)
        }
        fun block(n: String, l: List<Item>) =
            "$n\n${l.size} tisků · ${hm(l.sumOf { it.sec })} · ${"%.0f".format(l.sumOf { it.grams })} g · ≈ ${kc(l.sumOf { cost(it) })}"
        summary.text = block("📅 Tento měsíc", month) + "\n\n" + block("📦 Celkem (posledních ${items.size})", items)

        list.removeAllViews()
        val df = SimpleDateFormat("d. M. HH:mm", Locale.getDefault())
        items.forEachIndexed { idx, t ->
            val (card, inner) = Ui.card(this, 12)
            val row = Ui.hbox(this)
            val img = Ui.thumb(this, 60)
            row.addView(img)
            val col = Ui.vbox(this).apply { setPadding(Ui.dp(this@HistoryActivity, 12), 0, 0, 0) }
            val icon = when (t.ok) { true -> "✅ "; false -> "❌ "; null -> "⏳ " }
            col.addView(Ui.text(this, icon + t.title.ifEmpty { "(bez názvu)" }, 15f, Ui.TEXT, true).apply { maxLines = 2 })
            col.addView(Ui.text(this, (if (t.start > 0) df.format(Date(t.start)) + " · " else "") + hm(t.sec) + " · ${"%.0f".format(t.grams)} g", 13f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.text(this, kc(cost(t)), 16f, Ui.GREEN, true).apply { gravity = Gravity.END })
            inner.addView(row)
            list.addView(card, Ui.lp(this, 0, 8))
            val loader = t.image
            if (loader != null) {
                val cached = thumbs[idx]
                if (cached != null) img.setImageBitmap(cached)
                else pool.execute {
                    val b = loader() ?: return@execute
                    thumbs[idx] = b
                    runOnUiThread { img.setImageBitmap(b) }
                }
            }
        }
    }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }
}
