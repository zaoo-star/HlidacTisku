package cz.zaoo.tiskwidget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Po přidání widgetu z detailu tiskárny mu rovnou přiřadí tuhle tiskárnu. */
class PinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val pid = intent.getStringExtra("printer") ?: return
        if (wid >= 0) {
            WidgetOpts.setPrinter(context, wid, pid)
            PrintWidget.render(context)
        }
    }
}

/** Detail jedné tiskárny: stav, ovládání, kamera, AMS, historie. */
class PrinterActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var p: PrinterCfg
    private lateinit var cover: ImageView
    private lateinit var statePill: android.widget.TextView
    private lateinit var time: android.widget.TextView
    private lateinit var pct: android.widget.TextView
    private lateinit var bar: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var details: android.widget.TextView
    private lateinit var cmdMsg: android.widget.TextView
    private lateinit var camBtn: MaterialButton
    private lateinit var lightBtn: MaterialButton
    private lateinit var pauseBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton
    private lateinit var amsCard: LinearLayout
    private lateinit var amsRow: LinearLayout
    private var coverKey = "-"
    private var amsKey = "-"

    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 2000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Printers.get(this, intent.getStringExtra("id")) ?: run { finish(); return }
        val c = Ui.page(this, p.name, true) {
            menu.add(0, 1, 0, "Přejmenovat").setIcon(R.drawable.ic_edit).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 2, 1, "Odebrat").setIcon(R.drawable.ic_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { if (it.itemId == 1) rename() else remove(); true }
        }

        // --- Hlavní karta ---
        val (hero, hi) = Ui.card(this, 18)
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dpf(this@PrinterActivity, 16f); setColor(Ui.SURFACE2) }
            clipToOutline = true
        }
        hi.addView(cover, LinearLayout.LayoutParams(-1, Ui.dp(this, 190)).apply { bottomMargin = Ui.dp(this@PrinterActivity, 14) })
        statePill = Ui.pill(this, "", Ui.GREY)
        hi.addView(statePill, LinearLayout.LayoutParams(-2, -2))
        val row = Ui.hbox(this).apply { gravity = Gravity.BOTTOM }
        time = Ui.text(this, "—", 36f, Ui.TEXT, true)
        pct = Ui.text(this, "", 20f, Ui.MUTED, true)
        row.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(pct)
        hi.addView(row, Ui.lp(this, 8, 8))
        bar = Ui.progress(this)
        hi.addView(bar, Ui.lp(this, 0, 10))
        details = Ui.text(this, "", 14f, Ui.MUTED)
        hi.addView(details)
        c.addView(hero, Ui.lp(this, 4, 12))

        // --- Ovládání ---
        c.addView(Ui.section(this, "Ovládání"))
        val (ctl, ci) = Ui.card(this, 12)
        camBtn = Ui.button(this, "📷  Kamera", "tonal") { startActivity(Intent(this, CameraActivity::class.java).putExtra("id", p.id)) }
        lightBtn = Ui.button(this, "💡  Světlo", "tonal") {
            MonitorService.command(this, p.id, if (Statuses.get(this, p.id).light == 1) "light_off" else "light_on")
        }
        pauseBtn = Ui.button(this, "⏸  Pauza", "tonal") {
            MonitorService.command(this, p.id, if (Statuses.get(this, p.id).state == "PAUSE") "resume" else "pause")
        }
        stopBtn = Ui.button(this, "⏹  Zastavit", "tonal") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Zastavit tisk?")
                .setMessage("Tisk na tiskárně ${p.name} se ukončí a nepůjde v něm pokračovat.")
                .setPositiveButton("Zastavit") { _, _ -> MonitorService.command(this, p.id, "stop") }
                .setNegativeButton("Zrušit", null)
                .show()
        }
        val r1 = Ui.hbox(this); r1.addView(camBtn, Ui.weight(this)); r1.addView(lightBtn, Ui.weight(this))
        val r2 = Ui.hbox(this); r2.addView(pauseBtn, Ui.weight(this)); r2.addView(stopBtn, Ui.weight(this))
        ci.addView(r1); ci.addView(r2)
        ci.addView(Ui.button(this, "🔄  Obnovit data", "text") { MonitorService.refresh(this) })
        cmdMsg = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(Ui.dp(this@PrinterActivity, 6), 0, Ui.dp(this@PrinterActivity, 6), Ui.dp(this@PrinterActivity, 4)) }
        ci.addView(cmdMsg)
        if (p.isBambu) ci.addView(Ui.text(this, "U Bambu funguje světlo vždy. Pauzu a zastavení Bambu cizím appkám většinou povolí jen v LAN / Developer módu.", 12f, Ui.GREY).apply {
            setPadding(Ui.dp(this@PrinterActivity, 6), 0, Ui.dp(this@PrinterActivity, 6), Ui.dp(this@PrinterActivity, 4))
        })
        c.addView(ctl, Ui.lp(this, 0, 4))

        // --- AMS ---
        amsCard = Ui.vbox(this)
        amsCard.addView(Ui.section(this, "AMS – filamenty"))
        val (ac, ai) = Ui.card(this, 12)
        amsRow = Ui.hbox(this)
        ai.addView(amsRow)
        amsCard.addView(ac, Ui.lp(this, 0, 4))
        c.addView(amsCard)

        // --- Další ---
        c.addView(Ui.section(this, "Další"))
        val (more, mi) = Ui.card(this, 12)
        if (p.hasHistory) mi.addView(Ui.button(this, "📊  Historie tisků a nacenění", "tonal") {
            startActivity(Intent(this, HistoryActivity::class.java).putExtra("id", p.id))
        }, Ui.lp(this, 0, 6))
        mi.addView(Ui.button(this, "➕  Přidat widget této tiskárny na plochu", "tonal") { pinWidget() }, Ui.lp(this, 0, 6))
        if (p.isBambu) mi.addView(Ui.button(this, "📍  IP adresa pro kameru", "tonal") { editIp() }, Ui.lp(this, 0, 0))
        c.addView(more, Ui.lp(this, 0, 4))

        // --- Info ---
        val info = buildString {
            append("${p.brand} · ${p.connLabel}")
            if (p.model.isNotEmpty()) append(" · ${p.model}")
            if (p.host.isNotEmpty()) append("\nAdresa: ${p.host}")
            if (p.serial.isNotEmpty()) append("\nSériové číslo: ${p.serial}")
        }
        c.addView(Ui.text(this, info, 12f, Ui.GREY).apply { setPadding(Ui.dp(this@PrinterActivity, 6), Ui.dp(this@PrinterActivity, 16), 0, 0) })
    }

    override fun onResume() { super.onResume(); MonitorService.start(this); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun refresh() {
        val s = Statuses.get(this, p.id)
        val v = Fmt.view(s)
        val col = Ui.stateColor(s)
        val off = Fmt.offline(s)
        statePill.text = when {
            off -> "Offline" + Fmt.lastSeen(s).let { if (it.isEmpty()) "" else " · $it" }
            s.hasError -> Fmt.errText(s)
            s.state.isEmpty() -> "Připojuji…"
            else -> Fmt.stateCz(s.state)
        }
        statePill.setTextColor(col)
        (statePill.background as? android.graphics.drawable.GradientDrawable)?.setColor(
            android.graphics.Color.argb(40, android.graphics.Color.red(col), android.graphics.Color.green(col), android.graphics.Color.blue(col)))
        if (s.active && !off) {
            time.text = Fmt.remain(v.remain)
            pct.text = "${s.pct} %"
            bar.progress = s.pct
        } else {
            time.text = when {
                off -> "Offline"
                s.state == "FINISH" -> "Hotovo"; s.state == "FAILED" -> "Skončil"; s.state == "IDLE" -> "Netiskne"
                else -> "Připojuji…"
            }
            pct.text = ""
            bar.progress = if (s.state == "FINISH") 100 else 0
        }
        bar.setIndicatorColor(col)
        details.text = buildString {
            if (s.name.isNotEmpty()) append("🧩 ${s.name}\n")
            if (s.active) append("🏁 Hotovo v ${Fmt.eta(v)}\n")
            if (s.total > 0 && s.active) append("📚 Vrstva ${s.layer} / ${s.total}\n")
            if (s.state.isNotEmpty()) append("🌡 Tryska ${s.nozzle.toInt()}° · podložka ${s.bed.toInt()}°\n")
            if (s.light >= 0) append(if (s.light == 1) "💡 Světlo svítí\n" else "💡 Světlo zhasnuté\n")
            if (s.error.isNotEmpty()) append("⚠ ${s.error}\n")
            if (s.updated > 0) append("Aktualizováno ${Fmt.clock(s.updated)}")
        }.trim()

        Ui.gone(camBtn, !p.hasCamera(s))
        Ui.gone(lightBtn, !p.canLight)
        lightBtn.text = if (s.light == 1) "💡  Zhasnout" else "💡  Rozsvítit"
        pauseBtn.text = if (s.state == "PAUSE") "▶  Pokračovat" else "⏸  Pauza"
        pauseBtn.isEnabled = s.active
        stopBtn.isEnabled = s.active
        cmdMsg.text = s.cmdMsg
        Ui.gone(cmdMsg, s.cmdMsg.isEmpty())

        val key = if (Cover.valid(s)) s.coverKey else ""
        if (key != coverKey) {
            coverKey = key
            val b = if (key.isNotEmpty()) Cover.load(this, p.id, 700) else null
            cover.setImageBitmap(b)
            Ui.gone(cover, b == null)
        }
        val trays = if (p.isBambu) Ams.trays(this, p.id) else emptyList()
        val ak = trays.toString()
        if (ak != amsKey) {
            amsKey = ak
            Ams.fillRow(this, amsRow, trays)
            Ui.gone(amsCard, trays.isEmpty())
        }
    }

    private fun rename() {
        val (til, et) = Ui.input(this, "Název tiskárny", p.name)
        val box = Ui.vbox(this, 20).apply { addView(til) }
        MaterialAlertDialogBuilder(this).setTitle("Přejmenovat").setView(box)
            .setPositiveButton("Uložit") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    p = p.copy(name = n)
                    Printers.update(this, p)
                    supportActionBar?.title = n
                    recreate()
                }
            }.setNegativeButton("Zrušit", null).show()
    }

    private fun editIp() {
        val (til, et) = Ui.input(this, "IP adresa tiskárny", p.host.ifEmpty { Statuses.get(this, p.id).autoIp },
            InputType.TYPE_CLASS_TEXT, "Najdeš ji na displeji tiskárny: Nastavení → WLAN. Většinou se zjistí sama.")
        val box = Ui.vbox(this, 20).apply { addView(til) }
        MaterialAlertDialogBuilder(this).setTitle("IP adresa pro kameru").setView(box)
            .setPositiveButton("Uložit") { _, _ ->
                p = p.copy(host = et.text.toString().trim())
                Printers.update(this, p)
                if (p.type == PrinterCfg.BAMBU_LAN) MonitorService.reload(this)
            }.setNegativeButton("Zrušit", null).show()
    }

    private fun remove() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Odebrat ${p.name}?")
            .setMessage("Tiskárna zmizí z appky i z widgetů. Kdykoliv ji můžeš přidat znovu.")
            .setPositiveButton("Odebrat") { _, _ ->
                Printers.remove(this, p.id)
                Cover.clear(this, p.id)
                MonitorService.reload(this)
                PrintWidget.render(this)
                finish()
            }.setNegativeButton("Zrušit", null).show()
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (!mgr.isRequestPinAppWidgetSupported) {
            MaterialAlertDialogBuilder(this).setTitle("Přidat widget")
                .setMessage("Podrž prázdné místo na ploše → Widgety → Hlídač tisku. Pak ho podrž → Upravit a vyber tiskárnu.")
                .setPositiveButton("OK", null).show()
            return
        }
        val cb = android.app.PendingIntent.getBroadcast(this, 9, Intent(this, PinReceiver::class.java).putExtra("printer", p.id),
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        mgr.requestPinAppWidget(ComponentName(this, PrintWidget::class.java), null, cb)
    }
}
