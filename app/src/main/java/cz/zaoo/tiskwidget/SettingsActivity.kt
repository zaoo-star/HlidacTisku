package cz.zaoo.tiskwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val c = Ui.page(this, "Nastavení", true)
        fun pad(v: android.view.View) = v.apply { setPadding(Ui.dp(this@SettingsActivity, 4), 0, Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 8)) }

        // Upozornění
        c.addView(Ui.section(this, "Upozornění"))
        val (a, ai) = Ui.card(this, 14)
        ai.addView(MaterialSwitch(this).apply {
            text = "⏰ Hlasitý budík, když tisk skončí"
            textSize = 16f
            setTextColor(Ui.TEXT)
            isChecked = store.alarmOn
            setOnCheckedChangeListener { _, ch -> store.alarmOn = ch }
        })
        ai.addView(pad(Ui.text(this, "Zvoní jako budík i v tichém režimu (max. minutu). Ztlumíš ho v notifikaci nebo otevřením appky.", 13f, Ui.MUTED)))
        ai.addView(Ui.button(this, "Vyzkoušet budík (5 s)", "tonal") {
            Alarm.start(this)
            Handler(Looper.getMainLooper()).postDelayed({ Alarm.stop() }, 5000)
        }, LinearLayout.LayoutParams(-1, -2))
        c.addView(a, Ui.lp(this, 0, 4))

        // Běh na pozadí
        c.addView(Ui.section(this, "Běh na pozadí"))
        val (b, bi) = Ui.card(this, 14)
        bi.addView(Ui.button(this, "🔋 Vypnout úsporu baterie pro appku", "tonal") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, Ui.lp(this, 0, 8))
        bi.addView(Ui.button(this, "⚙ Informace o aplikaci (Autostart)", "tonal") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, Ui.lp(this, 0, 8))
        bi.addView(pad(Ui.text(this, "Xiaomi / Redmi / POCO: v Informacích o aplikaci zapni Autostart a u Úspory baterie zvol „Bez omezení“. Jinak systém appku uspí.", 13f, Ui.MUTED)))
        c.addView(b, Ui.lp(this, 0, 4))

        // Widgety
        c.addView(Ui.section(this, "Widgety"))
        val (w, wi) = Ui.card(this, 14)
        wi.addView(Ui.button(this, "➕ Přidat widget na plochu", "tonal") {
            val mgr = getSystemService(AppWidgetManager::class.java)
            if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(this, PrintWidget::class.java), null, null)
        }, Ui.lp(this, 0, 8))
        wi.addView(Ui.button(this, "🎨 Výchozí vzhled widgetu", "tonal") {
            startActivity(Intent(this, WidgetConfigActivity::class.java))
        }, Ui.lp(this, 0, 8))
        wi.addView(pad(Ui.text(this, "Každý widget na ploše si nastavíš zvlášť: podrž ho → Upravit. Tam vybereš tiskárnu, tvar, barvy a co ukazovat.", 13f, Ui.MUTED)))
        c.addView(w, Ui.lp(this, 0, 4))

        // Nacenění
        c.addView(Ui.section(this, "Nacenění tisků"))
        val (n, ni) = Ui.card(this, 14)
        val (tKg, kg) = Ui.input(this, "Kč za 1 kg filamentu", store.priceKg, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val (tH, hr) = Ui.input(this, "Kč za hodinu tisku (proud, opotřebení)", store.priceHour, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        ni.addView(tKg, Ui.lp(this, 0, 10)); ni.addView(tH, Ui.lp(this, 0, 10))
        ni.addView(Ui.button(this, "Uložit ceny", "tonal") {
            store.priceKg = kg.text.toString().trim(); store.priceHour = hr.text.toString().trim()
            android.widget.Toast.makeText(this, "Uloženo", android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, -2))
        c.addView(n, Ui.lp(this, 0, 4))

        // Bambu účet
        if (store.hasCloud) {
            c.addView(Ui.section(this, "Bambu účet"))
            val (acc, ac) = Ui.card(this, 14)
            ac.addView(pad(Ui.text(this, "Přihlášeno: ${store.email}" + if (store.cloudError.isNotEmpty()) "\n⚠ ${store.cloudError}" else "", 15f)))
            ac.addView(Ui.button(this, "Odhlásit Bambu účet", "outlined") {
                MaterialAlertDialogBuilder(this).setTitle("Odhlásit Bambu účet?")
                    .setMessage("Bambu tiskárny připojené přes účet se přestanou aktualizovat, dokud se znovu nepřihlásíš (Přidat tiskárnu → Bambu).")
                    .setPositiveButton("Odhlásit") { _, _ -> store.token = ""; MonitorService.reload(this); recreate() }
                    .setNegativeButton("Zrušit", null).show()
            }, LinearLayout.LayoutParams(-1, -2))
            c.addView(acc, Ui.lp(this, 0, 4))
        }

        // Aktualizace
        c.addView(Ui.section(this, "Aktualizace"))
        val (u, ui) = Ui.card(this, 14)
        val us = Ui.text(this, "Nainstalovaná verze ${Updater.current(this)}", 15f)
        ui.addView(us)
        ui.addView(MaterialSwitch(this).apply {
            text = "Aktualizovat automaticky"
            textSize = 16f
            setTextColor(Ui.TEXT)
            isChecked = Updater.autoOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, ch -> Updater.setAuto(this@SettingsActivity, ch) }
        }, Ui.lp(this, 8, 0))
        ui.addView(Ui.text(this, "Nová verze se stáhne a nainstaluje sama na pozadí. Poprvé ji Android nechá potvrdit, potom už (Android 12+) proběhne bez klikání.", 13f, Ui.MUTED))
        ui.addView(Ui.button(this, "Zkontrolovat aktualizace", "tonal") {
            us.text = "Kontroluji…"
            Thread {
                val r = Updater.check(this)
                runOnUiThread {
                    us.text = if (r != null) "🎉 Je dostupná verze ${r.version} – aktualizuješ ji na hlavní obrazovce."
                              else "✅ Máš nejnovější verzi ${Updater.current(this)}."
                }
            }.start()
        }, Ui.lp(this, 10, 0))
        c.addView(u, Ui.lp(this, 0, 4))

        // O aplikaci
        c.addView(Ui.section(this, "O aplikaci"))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        c.addView(pad(Ui.text(this, "Hlídač tisku $ver\nNeoficiální aplikace, není spojená s Bambu Lab, Prusa Research ani Creality.\n" +
            "Písma Orbitron a Rajdhani – SIL Open Font License.", 13f, Ui.GREY)))
    }
}
