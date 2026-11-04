package cz.zaoo.tiskwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: Store
    private val h = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var msg: TextView
    private lateinit var camBtn: Button
    private lateinit var cloudBox: LinearLayout
    private lateinit var lanBox: LinearLayout
    private lateinit var codeBox: LinearLayout
    private lateinit var email: EditText
    private lateinit var pass: EditText
    private lateinit var code: EditText
    private lateinit var camIp: EditText
    private lateinit var lanIp: EditText
    private lateinit var lanSn: EditText
    private lateinit var lanCode: EditText
    private var tfaKey: String? = null

    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 2000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun et(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setSingleLine()
        }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun box() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = box().apply { setPadding(dp(18), dp(18), dp(18), dp(40)) }

        root.addView(tv("🖨 Tisk P1S", 24f, true))
        status = tv("", 16f).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF22262A.toInt()) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(status)
        camBtn = btn("📷 Otevřít kameru") { startActivity(Intent(this, CameraActivity::class.java)) }
        root.addView(camBtn)
        msg = tv("", 14f, color = 0xFFFFC857.toInt())
        root.addView(msg)

        root.addView(tv("Připojení", 18f, true).apply { setPadding(0, dp(18), 0, dp(4)) })
        val rCloud = RadioButton(this).apply { id = View.generateViewId(); text = "Přes Bambu účet – funguje všude, Handy zůstane"; setTextColor(Color.WHITE) }
        val rLan = RadioButton(this).apply { id = View.generateViewId(); text = "Jen doma přes Wi-Fi (IP + přístupový kód)"; setTextColor(Color.WHITE) }
        val rg = RadioGroup(this).apply { addView(rCloud); addView(rLan) }
        root.addView(rg)

        // --- Bambu účet ---
        cloudBox = box()
        email = et("E-mail k Bambu účtu", store.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        pass = et("Heslo", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        cloudBox.addView(email)
        cloudBox.addView(pass)
        cloudBox.addView(btn("Přihlásit") { doLogin() })
        codeBox = box().apply { visibility = View.GONE }
        code = et("Kód z e-mailu", "", InputType.TYPE_CLASS_NUMBER)
        codeBox.addView(code)
        codeBox.addView(btn("Ověřit kód") { doCode() })
        cloudBox.addView(codeBox)
        cloudBox.addView(tv("IP tiskárny doma (jen pro kameru, většinou se vyplní sama):", 13f, color = 0xFFAAAAAA.toInt()))
        camIp = et("např. 192.168.1.50", store.ip)
        cloudBox.addView(camIp)
        cloudBox.addView(btn("Uložit IP") {
            store.ip = camIp.text.toString().trim()
            say("Uloženo.")
            PrintWidget.render(this)
        })
        root.addView(cloudBox)

        // --- LAN ---
        lanBox = box()
        lanBox.addView(tv("Údaje najdeš na displeji tiskárny: Nastavení → WLAN (IP a přístupový kód), Nastavení → Zařízení (sériové číslo).", 13f, color = 0xFFAAAAAA.toInt()))
        lanIp = et("IP adresa (např. 192.168.1.50)", store.ip)
        lanSn = et("Sériové číslo (např. 01P00A...)", if (store.mode == "lan") store.serial else "")
        lanCode = et("Přístupový kód (8 znaků)", if (store.mode == "lan") store.accessCode else "")
        lanBox.addView(lanIp)
        lanBox.addView(lanSn)
        lanBox.addView(lanCode)
        lanBox.addView(btn("Uložit a připojit") { saveLan() })
        root.addView(lanBox)

        rg.setOnCheckedChangeListener { _, id ->
            cloudBox.visibility = if (id == rCloud.id) View.VISIBLE else View.GONE
            lanBox.visibility = if (id == rLan.id) View.VISIBLE else View.GONE
        }
        rg.check(if (store.mode == "lan") rLan.id else rCloud.id)

        // --- Tipy ---
        root.addView(tv("Aby widget běžel spolehlivě", 18f, true).apply { setPadding(0, dp(22), 0, dp(4)) })
        root.addView(btn("🔋 Vypnout úsporu baterie pro tuhle appku") { askBattery() })
        root.addView(btn("➕ Přidat widget na plochu") { pinWidget() })
        root.addView(tv("Na Xiaomi / Redmi navíc: dlouze podrž ikonu appky → Informace o aplikaci → zapni Autostart a u Úspory baterie dej „Bez omezení“.", 13f, color = 0xFFAAAAAA.toInt()))
        root.addView(btn("Odhlásit a zastavit sledování") { logout() })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFF121416.toInt()) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (store.configured) MonitorService.start(this)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun refresh() {
        status.text = if (store.configured) {
            (if (store.devName.isNotEmpty()) "${store.devName}\n" else "") + Fmt.longText(store)
        } else "Nejdřív se připoj k tiskárně ↓"
        camBtn.visibility = if (store.hasCamera) View.VISIBLE else View.GONE
        if (camIp.text.isEmpty() && store.autoIp.isNotEmpty()) camIp.hint = "zjištěno: ${store.autoIp}"
    }

    private fun say(t: String) = runOnUiThread { msg.text = t }

    private fun doLogin() {
        val e = email.text.toString().trim()
        val p = pass.text.toString()
        if (e.isEmpty() || p.isEmpty()) { say("Vyplň e-mail i heslo."); return }
        store.email = e
        say("Přihlašuji…")
        thread { handle(BambuCloud.login(e, p)) }
    }

    private fun doCode() {
        val c = code.text.toString().trim()
        if (c.isEmpty()) { say("Zadej kód."); return }
        say("Ověřuji kód…")
        val key = tfaKey
        thread { handle(if (key != null) BambuCloud.loginTfa(key, c) else BambuCloud.loginWithCode(store.email, c)) }
    }

    private fun handle(r: BambuCloud.Login) {
        when (r) {
            is BambuCloud.Login.Ok -> finishLogin(r.token)
            is BambuCloud.Login.NeedCode -> {
                tfaKey = null
                BambuCloud.sendCode(store.email)
                runOnUiThread { codeBox.visibility = View.VISIBLE; code.hint = "Kód z e-mailu" }
                say("Bambu ti poslal ověřovací kód na e-mail. Opiš ho sem.")
            }
            is BambuCloud.Login.NeedTfa -> {
                tfaKey = r.tfaKey
                runOnUiThread { codeBox.visibility = View.VISIBLE; code.hint = "Kód z ověřovací aplikace" }
                say("Máš zapnuté dvoufázové ověření – zadej kód z aplikace.")
            }
            is BambuCloud.Login.Err -> say(r.msg)
        }
    }

    private fun finishLogin(token: String) {
        try {
            val user = BambuCloud.username(token)
            val devs = BambuCloud.devices(token)
            if (devs.isEmpty()) { say("Na účtu není žádná tiskárna."); return }
            val d = devs.firstOrNull { it.model.contains("P1S", true) } ?: devs.first()
            store.mode = "cloud"
            store.token = token
            store.username = user
            store.serial = d.serial
            store.devName = d.name
            store.accessCode = d.accessCode
            store.clearState()
            MonitorService.start(this, reconnect = true)
            runOnUiThread {
                codeBox.visibility = View.GONE
                pass.setText("")
                code.setText("")
            }
            say("✅ Přihlášeno – tiskárna ${d.name}. Teď si přidej widget na plochu.")
        } catch (e: Exception) {
            say("Přihlášení proběhlo, ale nepovedlo se načíst tiskárnu: ${e.message}")
        }
    }

    private fun saveLan() {
        val ip = lanIp.text.toString().trim()
        val sn = lanSn.text.toString().trim().uppercase()
        val c = lanCode.text.toString().trim()
        if (ip.isEmpty() || sn.isEmpty() || c.isEmpty()) { say("Vyplň všechna tři pole."); return }
        store.mode = "lan"
        store.ip = ip
        store.serial = sn
        store.accessCode = c
        store.devName = "P1S"
        store.clearState()
        MonitorService.start(this, reconnect = true)
        say("Uloženo, připojuji se…")
    }

    private fun logout() {
        MonitorService.stop(this)
        store.mode = ""
        store.token = ""
        store.clearState()
        PrintWidget.render(this)
        say("Odhlášeno.")
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { say("Úspora baterie už je vypnutá 👍"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, PrintWidget::class.java), null, null)
        } else {
            say("Dlouze podrž prázdné místo na ploše → Widgety → Tisk P1S.")
        }
    }
}
