package cz.zaoo.tiskwidget

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox
import kotlin.concurrent.thread

/** Průvodce přidáním tiskárny – srozumitelný i pro někoho, kdo to dělá poprvé. */
class AddPrinterActivity : AppCompatActivity() {
    private lateinit var c: LinearLayout
    private lateinit var store: Store
    private var tfaKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        c = Ui.page(this, "Přidat tiskárnu", true)
        stepBrand()
    }

    private fun clear(title: String, sub: String) {
        c.removeAllViews()
        c.addView(Ui.text(this, title, 24f, Ui.TEXT, true).apply { setPadding(Ui.dp(this@AddPrinterActivity, 4), Ui.dp(this@AddPrinterActivity, 8), 0, 0) })
        c.addView(Ui.text(this, sub, 15f, Ui.MUTED).apply { setPadding(Ui.dp(this@AddPrinterActivity, 4), Ui.dp(this@AddPrinterActivity, 6), 0, Ui.dp(this@AddPrinterActivity, 14)) })
    }

    /** Velká klikací karta s volbou. */
    private fun choice(icon: String, title: String, sub: String, onClick: () -> Unit) {
        val (card, inner) = Ui.card(this, 18)
        val row = Ui.hbox(this)
        row.addView(Ui.text(this, icon, 34f).apply { gravity = Gravity.CENTER; minWidth = Ui.dp(this@AddPrinterActivity, 56) })
        val col = Ui.vbox(this).apply { setPadding(Ui.dp(this@AddPrinterActivity, 10), 0, 0, 0) }
        col.addView(Ui.text(this, title, 17f, Ui.TEXT, true))
        col.addView(Ui.text(this, sub, 13f, Ui.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.text(this, "›", 28f, Ui.MUTED))
        inner.addView(row)
        card.setOnClickListener { onClick() }
        c.addView(card, Ui.lp(this, 0, 12))
    }

    private fun msgView() = Ui.text(this, "", 14f, Ui.AMBER).apply { setPadding(Ui.dp(this@AddPrinterActivity, 4), Ui.dp(this@AddPrinterActivity, 10), 0, Ui.dp(this@AddPrinterActivity, 10)) }

    private fun help(t: String) = Ui.text(this, t, 13f, Ui.MUTED).apply {
        setPadding(Ui.dp(this@AddPrinterActivity, 4), Ui.dp(this@AddPrinterActivity, 2), 0, Ui.dp(this@AddPrinterActivity, 12))
    }

    // ------------------------------------------------------------ 1. značka

    private fun stepBrand() {
        clear("Jakou máš tiskárnu?", "Vyber značku – další kroky tě provedou.")
        choice("🟢", "Bambu Lab", "P1S, P1P, A1, A1 mini, X1C, H2D…") { stepBambu() }
        choice("🟠", "Prusa", "MK4, MK4S, MK3.9, Mini, XL, Core One (PrusaLink)") { stepPrusa() }
        choice("🔵", "Klipper", "Creality K1/K2, Qidi (Q1 Pro, X-Plus, X-Max…), Elegoo Neptune 4, Sovol, Voron… (Mainsail / Fluidd)") { stepKlipper() }
    }

    // ------------------------------------------------------------ Bambu

    private fun stepBambu() {
        clear("Bambu Lab", "Jak se má appka k tiskárně připojit?")
        choice("☁️", "Přes Bambu účet (doporučeno)", "Funguje všude i mimo domov. Bambu Handy dál normálně funguje.") {
            if (store.hasCloud) stepBambuDevices() else stepBambuLogin()
        }
        choice("🏠", "Jen v domácí síti", "Bez účtu – stačí IP adresa a přístupový kód z displeje tiskárny. Funguje jen doma.") { stepBambuLan() }
    }

    private fun stepBambuLogin() {
        clear("Přihlášení k Bambu", "Stejný účet, jaký máš v Bambu Handy.")
        val (tE, email) = Ui.input(this, "E-mail", store.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val (tP, pass) = Ui.input(this, "Heslo", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            "Přihlašuješ se do Bambu přes Google? Heslo nech prázdné a použij kód z e-mailu.")
        val (tC, code) = Ui.input(this, "Kód z e-mailu", "", InputType.TYPE_CLASS_NUMBER)
        val msg = msgView()
        val verify = Ui.button(this, "Ověřit kód") {}
        c.addView(tE, Ui.lp(this, 0, 10))
        c.addView(tP, Ui.lp(this, 0, 10))
        c.addView(Ui.button(this, "Přihlásit") {
            val e = email.text.toString().trim(); val p = pass.text.toString()
            if (e.isEmpty() || p.isEmpty()) { msg.text = "Vyplň e-mail i heslo (nebo použij kód z e-mailu)."; return@button }
            store.email = e; msg.text = "Přihlašuji…"
            thread { handle(BambuCloud.login(e, p), msg, tC, verify) }
        }, Ui.lp(this, 4, 8))
        c.addView(Ui.button(this, "Přihlásit kódem z e-mailu (Google účet)", "outlined") {
            val e = email.text.toString().trim()
            if (e.isEmpty()) { msg.text = "Vyplň e-mail, kterým se do Bambu přihlašuješ."; return@button }
            store.email = e; tfaKey = null; msg.text = "Posílám kód…"
            thread {
                BambuCloud.sendCode(e)
                runOnUiThread { Ui.gone(tC, false); Ui.gone(verify, false); msg.text = "Bambu ti poslal kód na $e (mrkni i do spamu)." }
            }
        }, Ui.lp(this, 0, 8))
        c.addView(msg)
        Ui.gone(tC, true); Ui.gone(verify, true)
        c.addView(tC, Ui.lp(this, 0, 10))
        verify.setOnClickListener {
            val k = code.text.toString().trim()
            if (k.isEmpty()) { msg.text = "Opiš kód z e-mailu."; return@setOnClickListener }
            msg.text = "Ověřuji…"
            val key = tfaKey
            thread { handle(if (key != null) BambuCloud.loginTfa(key, k) else BambuCloud.loginWithCode(store.email, k), msg, tC, verify) }
        }
        c.addView(verify, Ui.lp(this, 0, 8))
    }

    private fun handle(r: BambuCloud.Login, msg: TextView, codeBox: View, verify: View) {
        when (r) {
            is BambuCloud.Login.Ok -> {
                try {
                    store.username = BambuCloud.username(r.token)
                    store.token = r.token
                    store.cloudError = ""
                    runOnUiThread { stepBambuDevices() }
                } catch (e: Exception) { runOnUiThread { msg.text = "Přihlášeno, ale nepodařilo se načíst účet: ${e.message}" } }
            }
            is BambuCloud.Login.NeedCode -> {
                tfaKey = null
                BambuCloud.sendCode(store.email)
                runOnUiThread { Ui.gone(codeBox, false); Ui.gone(verify, false); msg.text = "Bambu ti poslal ověřovací kód na e-mail. Opiš ho sem." }
            }
            is BambuCloud.Login.NeedTfa -> {
                tfaKey = r.tfaKey
                runOnUiThread { Ui.gone(codeBox, false); Ui.gone(verify, false); msg.text = "Máš zapnuté dvoufázové ověření – zadej kód z ověřovací aplikace." }
            }
            is BambuCloud.Login.Err -> runOnUiThread { msg.text = r.msg }
        }
    }

    private fun stepBambuDevices() {
        clear("Vyber tiskárny", "Tiskárny na účtu ${store.email}. Zaškrtni, které chceš hlídat.")
        val msg = msgView().apply { text = "Načítám…" }
        c.addView(msg)
        thread {
            try {
                val devs = BambuCloud.devices(store.token)
                runOnUiThread { showDevices(devs, msg) }
            } catch (e: Exception) {
                runOnUiThread {
                    msg.text = "Nepodařilo se načíst tiskárny (${e.message}). Přihlas se prosím znovu."
                    c.addView(Ui.button(this, "Přihlásit znovu") { store.token = ""; stepBambuLogin() }, Ui.lp(this, 0, 8))
                }
            }
        }
    }

    private fun showDevices(devs: List<BambuCloud.Device>, msg: TextView) {
        if (devs.isEmpty()) { msg.text = "Na účtu není žádná tiskárna."; return }
        msg.text = ""
        val have = Printers.all(this).map { it.serial }.toSet()
        val (card, inner) = Ui.card(this, 8)
        val boxes = devs.map { d ->
            MaterialCheckBox(this).apply {
                text = "${d.name}  ·  ${d.model}" + if (d.serial in have) "  (už přidaná)" else ""
                textSize = 16f
                setTextColor(Ui.TEXT)
                isChecked = d.serial !in have
                isEnabled = d.serial !in have
                setPadding(0, Ui.dp(this@AddPrinterActivity, 10), 0, Ui.dp(this@AddPrinterActivity, 10))
            }.also { inner.addView(it) } to d
        }
        c.addView(card, Ui.lp(this, 0, 12))
        c.addView(Ui.button(this, "Přidat vybrané") {
            var n = 0
            for ((cb, d) in boxes) if (cb.isChecked && cb.isEnabled) {
                Printers.add(this, PrinterCfg(id = "bambu_${d.serial}", name = d.name, type = PrinterCfg.BAMBU_CLOUD,
                    serial = d.serial, accessCode = d.accessCode, model = d.model))
                n++
            }
            if (n == 0) { msg.text = "Nic není vybráno."; return@button }
            done()
        }, Ui.lp(this, 0, 8))
    }

    private fun stepBambuLan() {
        clear("Bambu – domácí síť", "Údaje najdeš na displeji tiskárny.")
        val (tN, name) = Ui.input(this, "Název (např. P1S dílna)", "Bambu")
        val (tI, ip) = Ui.input(this, "IP adresa", "", InputType.TYPE_CLASS_TEXT, "Nastavení → WLAN → IP (např. 192.168.1.50)")
        val (tS, sn) = Ui.input(this, "Sériové číslo", "", InputType.TYPE_CLASS_TEXT, "Nastavení → Zařízení / Device (01P00A…)")
        val (tC, code) = Ui.input(this, "Přístupový kód", "", InputType.TYPE_CLASS_TEXT, "Nastavení → WLAN → Access Code (8 znaků)")
        listOf(tN, tI, tS, tC).forEach { c.addView(it, Ui.lp(this, 0, 10)) }
        val msg = msgView()
        c.addView(Ui.button(this, "Přidat tiskárnu") {
            val i = ip.text.toString().trim(); val s = sn.text.toString().trim().uppercase(); val k = code.text.toString().trim()
            if (i.isEmpty() || s.isEmpty() || k.isEmpty()) { msg.text = "Vyplň IP adresu, sériové číslo i přístupový kód."; return@button }
            Printers.add(this, PrinterCfg(id = "bambu_$s", name = name.text.toString().trim().ifEmpty { "Bambu" },
                type = PrinterCfg.BAMBU_LAN, host = i, serial = s, accessCode = k))
            done()
        }, Ui.lp(this, 4, 8))
        c.addView(msg)
    }

    // ------------------------------------------------------------ Prusa

    private fun stepPrusa() {
        clear("Prusa", "Tiskárna musí být připojená k Wi-Fi nebo kabelem a mít zapnutý PrusaLink.")
        val (tN, name) = Ui.input(this, "Název (např. MK4)", "Prusa")
        val (tI, ip) = Ui.input(this, "IP adresa", "", InputType.TYPE_CLASS_TEXT, "Na tiskárně: Nastavení → Síť (např. 192.168.1.60)")
        val (tK, key) = Ui.input(this, "API klíč", "", InputType.TYPE_CLASS_TEXT,
            "Nastavení → Síť → PrusaLink. Nebo otevři IP tiskárny v prohlížeči → Nastavení.")
        listOf(tN, tI, tK).forEach { c.addView(it, Ui.lp(this, 0, 10)) }
        val adv = Ui.vbox(this)
        val (tU, user) = Ui.input(this, "Uživatel", "maker")
        val (tP, pass) = Ui.input(this, "Heslo", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            "Stejné heslo jako do webu PrusaLink.")
        adv.addView(tU, Ui.lp(this, 0, 10)); adv.addView(tP, Ui.lp(this, 0, 10))
        Ui.gone(adv, true)
        c.addView(Ui.button(this, "Nemám API klíč – přihlásit jménem a heslem", "text") { Ui.gone(adv, false) })
        c.addView(adv)
        val msg = msgView()
        fun cfg() = PrinterCfg(id = Printers.newId("prusa"), name = name.text.toString().trim().ifEmpty { "Prusa" },
            type = PrinterCfg.PRUSA, host = ip.text.toString().trim(), apiKey = key.text.toString().trim(),
            user = if (key.text.isNullOrBlank()) user.text.toString().trim() else "", password = pass.text.toString())
        testAndSave(::cfg, msg)
    }

    // ------------------------------------------------------------ Klipper

    private fun stepKlipper() {
        clear("Klipper", "Stejná adresa, na které otevíráš Mainsail, Fluidd nebo web Creality.")
        val (tN, name) = Ui.input(this, "Název (např. K1 Max)", "Klipper")
        val (tI, ip) = Ui.input(this, "IP adresa", "", InputType.TYPE_CLASS_TEXT,
            "Najdeš ji na displeji tiskárny v nastavení sítě. Port (7125) doplním sám.")
        listOf(tN, tI).forEach { c.addView(it, Ui.lp(this, 0, 10)) }
        val adv = Ui.vbox(this)
        val (tU, user) = Ui.input(this, "Uživatel", "", InputType.TYPE_CLASS_TEXT,
            "Stejné jméno a heslo, jakým se přihlašuješ do Fluiddu / Mainsailu.")
        val (tP, pass) = Ui.input(this, "Heslo", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val (tK, key) = Ui.input(this, "API klíč (místo jména a hesla)", "")
        adv.addView(tU, Ui.lp(this, 0, 10)); adv.addView(tP, Ui.lp(this, 0, 10)); adv.addView(tK, Ui.lp(this, 0, 10))
        Ui.gone(adv, true)
        c.addView(Ui.button(this, "Tiskárna chce přihlášení (např. Qidi s účtem ve Fluiddu)", "text") { Ui.gone(adv, false) })
        c.addView(adv)
        val msg = msgView()
        fun cfg() = PrinterCfg(id = Printers.newId("klipper"), name = name.text.toString().trim().ifEmpty { "Klipper" },
            type = PrinterCfg.KLIPPER, host = ip.text.toString().trim(), apiKey = key.text.toString().trim(),
            user = user.text.toString().trim(), password = pass.text.toString())
        testAndSave(::cfg, msg)
    }

    /** Tlačítka Vyzkoušet + Přidat. U Klipperu zkusí i port 7125. */
    private fun testAndSave(cfg: () -> PrinterCfg, msg: TextView) {
        var tested: PrinterCfg? = null
        val save = Ui.button(this, "Přidat tiskárnu") {
            val p = tested ?: cfg()
            if (p.host.isEmpty()) { msg.text = "Vyplň IP adresu."; return@button }
            Printers.add(this, p)
            done()
        }
        c.addView(Ui.button(this, "Vyzkoušet připojení", "outlined") {
            val base = cfg()
            if (base.host.isEmpty()) { msg.text = "Vyplň IP adresu."; return@button }
            msg.text = "Zkouším…"
            thread {
                // Klipper bez portu: nejdřív Moonraker na 7125, pak adresa jak je (nginx na portu 80)
                val tries = if (base.type == PrinterCfg.KLIPPER && !base.host.substringAfter("://").contains(':'))
                    mutableListOf(base.copy(host = base.host + ":7125"), base) else mutableListOf(base)
                var res: Pair<Boolean, String> = false to "❌"
                var first: Pair<Boolean, String>? = null
                for (t in tries) {
                    res = ConnTest.run(this, t)
                    if (first == null) first = res
                    if (res.first) { tested = t; break }
                }
                // u neúspěchu ukázat chybu z Moonrakeru (port 7125) – ta je nejvýstižnější
                if (!res.first && first != null) res = first
                runOnUiThread { msg.text = res.second }
            }
        }, Ui.lp(this, 4, 8))
        c.addView(save, Ui.lp(this, 0, 8))
        c.addView(msg)
    }

    private fun done() {
        MonitorService.reload(this)
        finish()
    }
}
