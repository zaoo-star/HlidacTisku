package cz.zaoo.tiskwidget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Neoficiální přihlášení k Bambu účtu (stejné jako používá Home Assistant / OrcaSlicer). */
object BambuCloud {
    private const val API = "https://api.bambulab.com"

    sealed class Login {
        data class Ok(val token: String) : Login()
        object NeedCode : Login()
        data class NeedTfa(val tfaKey: String) : Login()
        data class Err(val msg: String) : Login()
    }

    data class Device(val serial: String, val name: String, val model: String, val accessCode: String, val online: Boolean)

    private class Resp(val code: Int, val body: String, val cookies: List<String>)

    private fun req(method: String, url: String, body: JSONObject? = null, token: String? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "bambu_network_agent/01.09.05.01")
        c.setRequestProperty("X-BBL-Client-Name", "OrcaSlicer")
        c.setRequestProperty("X-BBL-Client-Type", "slicer")
        c.setRequestProperty("X-BBL-Client-Version", "01.09.05.51")
        c.setRequestProperty("X-BBL-Language", "en-US")
        c.setRequestProperty("X-BBL-OS-Type", "linux")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val cookies = c.headerFields["Set-Cookie"] ?: c.headerFields["set-cookie"] ?: emptyList()
        c.disconnect()
        return Resp(code, text, cookies)
    }

    private fun parseLogin(r: Resp): Login {
        if (r.body.contains("cloudflare", true) || r.body.trimStart().startsWith("<")) {
            return Login.Err("Bambu server odmítl přihlášení z appky (HTTP ${r.code}). Zkus to za chvíli, nebo použij režim „Jen doma“.")
        }
        val j = try { JSONObject(r.body) } catch (e: Exception) { return Login.Err("Neznámá odpověď serveru (HTTP ${r.code})") }
        val token = j.optString("accessToken", "")
        if (token.isNotEmpty()) return Login.Ok(token)
        return when (j.optString("loginType", "")) {
            "verifyCode" -> Login.NeedCode
            "tfa" -> Login.NeedTfa(j.optString("tfaKey", ""))
            else -> Login.Err(j.optString("error", "").ifEmpty { j.optString("message", "").ifEmpty { "Přihlášení selhalo (HTTP ${r.code})" } })
        }
    }

    fun login(email: String, password: String): Login = try {
        parseLogin(req("POST", "$API/v1/user-service/user/login",
            JSONObject().put("account", email).put("password", password).put("apiError", "")))
    } catch (e: Exception) { Login.Err("Chyba sítě: ${e.message}") }

    fun sendCode(email: String) {
        try {
            req("POST", "$API/v1/user-service/user/sendemail/code",
                JSONObject().put("email", email).put("type", "codeLogin"))
        } catch (_: Exception) {}
    }

    fun loginWithCode(email: String, code: String): Login = try {
        parseLogin(req("POST", "$API/v1/user-service/user/login",
            JSONObject().put("account", email).put("code", code)))
    } catch (e: Exception) { Login.Err("Chyba sítě: ${e.message}") }

    fun loginTfa(tfaKey: String, code: String): Login = try {
        val r = req("POST", "https://bambulab.com/api/sign-in/tfa",
            JSONObject().put("tfaKey", tfaKey).put("tfaCode", code))
        val tok = r.cookies.flatMap { it.split(";") }.map { it.trim() }
            .firstOrNull { it.startsWith("token=") }?.removePrefix("token=")
        if (!tok.isNullOrEmpty()) Login.Ok(tok) else parseLogin(r)
    } catch (e: Exception) { Login.Err("Chyba sítě: ${e.message}") }

    /** MQTT uživatel je "u_<uid>". */
    /**
     * MQTT uživatel je "u_<uid>". Nejdřív ho přečte přímo z přihlašovacího tokenu (JWT),
     * teprve pak se ptá serveru – ten totiž občas místo dat vrátí webovou stránku (ochrana proti botům).
     */
    fun username(token: String): String {
        fromJwt(token)?.let { return it }
        var lastCode = 0
        for (path in listOf("/v1/user-service/my/profile", "/v1/design-user-service/my/preference")) {
            try {
                val r = req("GET", "$API$path", token = token)
                lastCode = r.code
                val j = parseJson(r.body) ?: continue
                val uid = j.optString("uidStr", "").ifEmpty { j.optLong("uid", 0L).takeIf { it != 0L }?.toString() ?: "" }
                if (uid.isNotEmpty()) return "u_$uid"
            } catch (_: Exception) {}
        }
        throw Exception("Bambu server teď nepustil načtení účtu (HTTP $lastCode). Zkus to za pár minut znovu, " +
            "nebo přidej tiskárnu přes „Jen v domácí síti“.")
    }

    /** Token bývá JWT – v jeho druhé části je přímo "username": "u_123…". */
    private fun fromJwt(token: String): String? = try {
        val part = token.split('.').getOrNull(1) ?: throw Exception()
        val json = String(android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
        val j = JSONObject(json)
        val u = j.optString("username", "")
        when {
            u.startsWith("u_") -> u
            j.optLong("uid", 0L) != 0L -> "u_" + j.optLong("uid")
            else -> null
        }
    } catch (_: Exception) { null }

    /** Bezpečné čtení JSON – když server vrátí HTML (Cloudflare), vrátí null. */
    private fun parseJson(body: String): JSONObject? =
        if (body.trimStart().startsWith("{")) try { JSONObject(body) } catch (_: Exception) { null } else null

    fun devices(token: String): List<Device> {
        var r = req("GET", "$API/v1/iot-service/api/user/bind", token = token)
        if (parseJson(r.body) == null) { Thread.sleep(1500); r = req("GET", "$API/v1/iot-service/api/user/bind", token = token) }
        val j = parseJson(r.body) ?: throw Exception("Bambu server teď nevrátil seznam tiskáren (HTTP ${r.code}). Zkus to za pár minut znovu.")
        val arr = j.optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).map {
            val d = arr.getJSONObject(it)
            Device(
                serial = d.optString("dev_id"),
                name = d.optString("name"),
                model = d.optString("dev_product_name", d.optString("dev_model_name")),
                accessCode = d.optString("dev_access_code"),
                online = d.optBoolean("online"),
            )
        }
    }
}
