package cz.zaoo.tiskwidget

import android.content.Context
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Globální nastavení appky (Bambu účet, budík, ceny…). Tiskárny jsou v [Printers]. */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("tisk", Context.MODE_PRIVATE)

    private fun s(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    // Bambu účet (společný pro všechny Bambu tiskárny přes cloud)
    var email: String
        get() = s("email")
        set(v) = put("email", v)
    var token: String
        get() = s("token")
        set(v) = put("token", v)
    var username: String
        get() = s("username")
        set(v) = put("username", v)
    var cloudError: String
        get() = s("cloudError")
        set(v) = put("cloudError", v)

    var priceKg: String
        get() = p.getString("priceKg", "600") ?: "600"
        set(v) = put("priceKg", v)
    var priceHour: String
        get() = p.getString("priceHour", "5") ?: "5"
        set(v) = put("priceHour", v)
    var alarmOn: Boolean
        get() = p.getBoolean("alarmOn", false)
        set(v) = p.edit().putBoolean("alarmOn", v).apply()
    var refreshAt: Long
        get() = p.getLong("refreshAt", 0L)
        set(v) = p.edit().putLong("refreshAt", v).apply()
    var migrated: Boolean
        get() = p.getBoolean("migrated2", false)
        set(v) = p.edit().putBoolean("migrated2", v).apply()

    val hasCloud get() = token.isNotEmpty() && username.isNotEmpty()

    /** Převod nastavení z verze pro jednu tiskárnu. */
    fun migrate(ctx: Context) {
        if (migrated) return
        migrated = true
        val mode = s("mode")
        val serial = s("serial")
        if (mode.isEmpty() || serial.isEmpty()) return
        val cfg = PrinterCfg(
            id = "bambu_$serial",
            name = s("devName").ifEmpty { "Bambu P1S" },
            type = if (mode == "cloud") PrinterCfg.BAMBU_CLOUD else PrinterCfg.BAMBU_LAN,
            host = s("ip").ifEmpty { s("autoIp") },
            serial = serial,
            accessCode = s("accessCode"),
        )
        Printers.add(ctx, cfg)
    }

    fun raw(k: String) = s(k)
}

/** Tiskárny v síti mají vlastní (self-signed) certifikát. */
object Tls {
    fun trustAll(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return ctx
    }
}
