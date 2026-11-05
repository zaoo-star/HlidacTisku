package cz.zaoo.tiskwidget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Přehled všech tiskáren jako karty. */
class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var tips: LinearLayout
    private lateinit var upd: LinearLayout
    private var updShown = ""
    private var shownIds = ""
    private val holders = HashMap<String, Holder>()

    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 2000) }
    }

    /** Karta jedné tiskárny – při obnově se jen přepíšou texty. */
    private inner class Holder(val p: PrinterCfg) {
        val card: MaterialCardView
        val thumb: ImageView = Ui.thumb(this@MainActivity, 64)
        val initial = Ui.text(this@MainActivity, p.brand.take(1), 26f, Ui.MUTED, true).apply { gravity = Gravity.CENTER }
        val name = Ui.text(this@MainActivity, p.name, 18f, Ui.TEXT, true)
        val sub = Ui.text(this@MainActivity, "", 13f, Ui.MUTED)
        val state = Ui.pill(this@MainActivity, "", Ui.GREY)
        val time = Ui.text(this@MainActivity, "", 30f, Ui.TEXT, true)
        val pct = Ui.text(this@MainActivity, "", 18f, Ui.MUTED, true)
        val bar: LinearProgressIndicator = Ui.progress(this@MainActivity)
        val info = Ui.text(this@MainActivity, "", 13f, Ui.MUTED)
        var coverKey = "-"

        init {
            val ctx = this@MainActivity
            val (c, inner) = Ui.card(ctx, 16)
            card = c
            val top = Ui.hbox(ctx)
            val tf = FrameLayout(ctx)
            tf.addView(thumb)
            tf.addView(initial, FrameLayout.LayoutParams(Ui.dp(ctx, 64), Ui.dp(ctx, 64)))
            top.addView(tf)
            val col = Ui.vbox(ctx).apply { setPadding(Ui.dp(ctx, 14), 0, 0, 0) }
            col.addView(name)
            col.addView(sub)
            col.addView(state, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(ctx, 6) })
            top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            inner.addView(top)
            val row = Ui.hbox(ctx).apply { gravity = Gravity.BOTTOM }
            row.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(pct)
            inner.addView(row, Ui.lp(ctx, 12, 6))
            inner.addView(bar, Ui.lp(ctx, 0, 8))
            inner.addView(info)
            card.setOnClickListener { startActivity(Intent(ctx, PrinterActivity::class.java).putExtra("id", p.id)) }
        }

        fun update() {
            val ctx = this@MainActivity
            val s = Statuses.get(ctx, p.id)
            val v = Fmt.view(s)
            val col = Ui.stateColor(s)
            sub.text = "${p.brand} · ${p.connLabel}"
            state.text = when {
                s.hasError -> Fmt.errText(s).removePrefix("⚠ ")
                s.state.isEmpty() && s.error.isNotEmpty() -> "Nepřipojeno"
                s.state.isEmpty() -> "Připojuji…"
                !s.connected -> Fmt.stateCz(s.state) + " · offline"
                else -> Fmt.stateCz(s.state)
            }
            state.setTextColor(col)
            (state.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                android.graphics.Color.argb(40, android.graphics.Color.red(col), android.graphics.Color.green(col), android.graphics.Color.blue(col)))
            if (s.active) {
                time.text = Fmt.remain(v.remain)
                pct.text = "${s.pct} %"
                bar.setIndicatorColor(col)
                bar.progress = s.pct
                Ui.gone(bar, false)
                val parts = mutableListOf("hotovo v ${Fmt.eta(v)}")
                if (s.name.isNotEmpty()) parts.add(0, s.name)
                parts += Fmt.temps(s)
                info.text = parts.joinToString("  ·  ")
            } else {
                time.text = when (s.state) { "FINISH" -> "Hotovo ✅"; "FAILED" -> "Skončil"; "IDLE" -> "Volná"; else -> "—" }
                pct.text = ""
                Ui.gone(bar, s.state != "FINISH")
                bar.progress = 100
                bar.setIndicatorColor(col)
                info.text = when {
                    s.error.isNotEmpty() -> s.error
                    s.state.isNotEmpty() -> Fmt.temps(s) + (if (s.name.isNotEmpty() && s.state == "FINISH") "  ·  ${s.name}" else "")
                    else -> ""
                }
            }
            val key = if (Cover.valid(s) && (s.active || s.state == "FINISH")) s.coverKey else ""
            if (key != coverKey) {
                coverKey = key
                val b = if (key.isNotEmpty()) Cover.load(ctx, p.id, 256) else null
                thumb.setImageBitmap(b)
                Ui.gone(initial, b != null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store(this).migrate(this)
        content = Ui.page(this, "Hlídač tisku", false) {
            menu.add(0, 1, 0, "Nastavení").setIcon(R.drawable.ic_settings)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)); true }
        }
        upd = Ui.vbox(this)
        content.addView(upd)
        tips = Ui.vbox(this)
        content.addView(tips)
        list = Ui.vbox(this)
        content.addView(list)

        val fab = ExtendedFloatingActionButton(this).apply {
            text = "Přidat tiskárnu"
            setIconResource(R.drawable.ic_add)
            setOnClickListener { startActivity(Intent(this@MainActivity, AddPrinterActivity::class.java)) }
        }
        Ui.frameOf(content).addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 28))
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Alarm.ringing) Alarm.stop()
        if (Printers.all(this).isNotEmpty()) MonitorService.start(this)
        shownIds = "-"
        h.post(tick)
        if (Updater.dueForCheck(this, 30 * 60_000L) || Updater.available != null) Thread {
            val r = if (Updater.dueForCheck(this, 30 * 60_000L)) Updater.check(this) else Updater.available
            runOnUiThread { showUpdate(r) }
        }.start()
    }

    /** Karta „Je dostupná nová verze“ s tlačítkem Aktualizovat. */
    private fun showUpdate(r: Updater.Rel?) {
        if (r == null) { upd.removeAllViews(); updShown = ""; return }
        if (updShown == r.version) return
        updShown = r.version
        upd.removeAllViews()
        val (c, inner) = Ui.card(this, 16)
        c.setCardBackgroundColor(0xFF0B3D22.toInt())
        inner.addView(Ui.text(this, "🎉 Je dostupná nová verze ${r.version}", 17f, Ui.TEXT, true))
        if (r.notes.isNotEmpty()) inner.addView(Ui.text(this, r.notes, 14f, 0xFFB9E8CB.toInt()).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4), 0, 0)
        })
        val status = Ui.text(this, "", 13f, 0xFFB9E8CB.toInt())
        val btn = Ui.button(this, "Aktualizovat") {}
        btn.setOnClickListener {
            if (!Updater.canInstall(this)) {
                status.text = "Povol appce „Instalovat neznámé aplikace“ a pak klepni znovu na Aktualizovat."
                return@setOnClickListener
            }
            btn.isEnabled = false
            status.text = "Stahuji…"
            Thread {
                try {
                    val f = Updater.download(this, r) { pc -> runOnUiThread { status.text = "Stahuji… $pc %" } }
                    runOnUiThread { status.text = "Staženo – potvrď instalaci."; btn.isEnabled = true; Updater.install(this, f) }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Stažení se nepovedlo: ${e.message}"; btn.isEnabled = true }
                }
            }.start()
        }
        inner.addView(btn, Ui.lp(this, 10, 4))
        inner.addView(status)
        upd.addView(c, Ui.lp(this, 4, 12))
    }

    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun refresh() {
        val all = Printers.all(this)
        val ids = all.joinToString(",") { it.id + it.name }
        if (ids != shownIds) {
            shownIds = ids
            list.removeAllViews()
            holders.clear()
            if (all.isEmpty()) list.addView(empty())
            for (p in all) {
                val hd = Holder(p)
                holders[p.id] = hd
                list.addView(hd.card, Ui.lp(this, 0, 12))
            }
            buildTips(all.isNotEmpty())
        }
        holders.values.forEach { it.update() }
    }

    private fun empty(): LinearLayout {
        val (c, inner) = Ui.card(this, 24)
        inner.gravity = Gravity.CENTER_HORIZONTAL
        inner.addView(Ui.text(this, "🖨", 56f).apply { gravity = Gravity.CENTER })
        inner.addView(Ui.text(this, "Zatím tu není žádná tiskárna", 20f, Ui.TEXT, true).apply { gravity = Gravity.CENTER })
        inner.addView(Ui.text(this, "Přidej Bambu Lab, Prusu nebo tiskárnu s Klipperem (Creality K1, Elegoo, Sovol…). " +
            "Uvidíš průběh tisku, zbývající čas a dostaneš upozornění, až bude hotovo.", 14f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 16))
        })
        inner.addView(Ui.button(this, "Přidat první tiskárnu") {
            startActivity(Intent(this, AddPrinterActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2))
        return Ui.vbox(this).apply { addView(c, Ui.lp(this@MainActivity, 8, 0)) }
    }

    /** Doporučení, aby hlídání běželo spolehlivě (zmizí, až je splněno). */
    private fun buildTips(hasPrinters: Boolean) {
        tips.removeAllViews()
        if (!hasPrinters) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val (c, inner) = Ui.card(this, 16)
        c.setCardBackgroundColor(0xFF2A2410.toInt())
        inner.addView(Ui.text(this, "🔋 Aby hlídání běželo i na pozadí", 16f, Ui.AMBER, true))
        inner.addView(Ui.text(this, "Telefon jinak appku uspí a widget se přestane aktualizovat.", 14f, Ui.TEXT).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 10))
        })
        inner.addView(Ui.button(this, "Povolit běh na pozadí") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }, LinearLayout.LayoutParams(-1, -2))
        tips.addView(c, Ui.lp(this, 4, 12))
    }
}
