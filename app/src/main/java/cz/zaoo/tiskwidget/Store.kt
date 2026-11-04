package cz.zaoo.tiskwidget

import android.content.Context
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Uložené nastavení a poslední známý stav tiskárny. */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("tisk", Context.MODE_PRIVATE)

    private fun s(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    var mode: String          // "cloud" nebo "lan"
        get() = s("mode")
        set(v) = put("mode", v)
    var email: String
        get() = s("email")
        set(v) = put("email", v)
    var token: String
        get() = s("token")
        set(v) = put("token", v)
    var username: String
        get() = s("username")
        set(v) = put("username", v)
    var serial: String
        get() = s("serial")
        set(v) = put("serial", v)
    var devName: String
        get() = s("devName")
        set(v) = put("devName", v)
    var accessCode: String
        get() = s("accessCode")
        set(v) = put("accessCode", v)
    var ip: String                 // ručně zadaná IP
        get() = s("ip")
        set(v) = put("ip", v)
    var autoIp: String     // IP zjištěná z tiskárny
        get() = s("autoIp")
        set(v) = put("autoIp", v)
    var stateJson: String
        get() = s("state")
        set(v) = put("state", v)
    var error: String
        get() = s("error")
        set(v) = put("error", v)
    var lastUpdate: Long
        get() = p.getLong("lastUpdate", 0L)
        set(v) = p.edit().putLong("lastUpdate", v).apply()
    var connected: Boolean
        get() = p.getBoolean("connected", false)
        set(v) = p.edit().putBoolean("connected", v).apply()

    val configured: Boolean
        get() = when (mode) {
            "cloud" -> token.isNotEmpty() && serial.isNotEmpty() && username.isNotEmpty()
            "lan" -> ip.isNotEmpty() && serial.isNotEmpty() && accessCode.isNotEmpty()
            else -> false
        }

    fun camIp(): String = ip.ifEmpty { autoIp }
    val hasCamera get() = camIp().isNotEmpty() && accessCode.isNotEmpty()

    fun clearState() {
        p.edit().remove("state").remove("error").remove("lastUpdate").putBoolean("connected", false).apply()
    }
}

/** Tiskárna v LAN má vlastní (self-signed) certifikát. */
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
