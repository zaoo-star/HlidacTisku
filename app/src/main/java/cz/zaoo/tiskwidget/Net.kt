package cz.zaoo.tiskwidget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** HTTP pro tiskárny v domácí síti (PrusaLink, Moonraker) včetně Digest přihlášení. */
object Http {
    class Resp(val code: Int, val body: String, val bytes: ByteArray? = null)

    fun base(host: String): String {
        val h = host.trim().trimEnd('/')
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
    }

    /** Adresa bez portu (u Klipperu běží kamera na portu 80, Moonraker na 7125). */
    fun hostOnly(host: String): String =
        base(host).removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')

    private val digest = HashMap<String, Map<String, String>>()
    private var nc = 1

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun parseDigest(h: String): Map<String, String> {
        val m = HashMap<String, String>()
        Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(h.removePrefix("Digest").trim()).forEach {
            m[it.groupValues[1]] = it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        return m
    }

    private fun digestHeader(p: Map<String, String>, method: String, uri: String, user: String, pass: String): String {
        val realm = p["realm"] ?: ""
        val nonce = p["nonce"] ?: ""
        val qop = p["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val cnonce = md5(System.nanoTime().toString()).take(16)
        val ncs = "%08x".format(nc++)
        val resp = if (qop != null) md5("$ha1:$nonce:$ncs:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\"")
        if (qop != null) sb.append(", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
        p["opaque"]?.let { sb.append(", opaque=\"$it\"") }
        return sb.toString()
    }

    fun request(cfg: PrinterCfg, method: String, path: String, body: String? = null, binary: Boolean = false,
                baseOverride: String? = null, timeout: Int = 8000): Resp {
        val baseUrl = baseOverride ?: base(cfg.host)
        val url = if (path.startsWith("http")) path else baseUrl + path
        val uri = URL(url).let { it.path + (if (it.query != null) "?" + it.query else "") }
        val key = URL(url).host
        for (attempt in 0..1) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = true
            if (cfg.apiKey.isNotEmpty()) c.setRequestProperty("X-Api-Key", cfg.apiKey)
            else if (cfg.user.isNotEmpty()) digest[key]?.let { c.setRequestProperty("Authorization", digestHeader(it, method, uri, cfg.user, cfg.password)) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code == 401 && attempt == 0 && cfg.user.isNotEmpty() && cfg.apiKey.isEmpty()) {
                val h = c.getHeaderField("WWW-Authenticate") ?: ""
                c.disconnect()
                if (h.startsWith("Digest")) { digest[key] = parseDigest(h); continue }
                return Resp(code, "")
            }
            val stream = if (code < 400) c.inputStream else c.errorStream
            return if (binary) {
                val b = stream?.use { it.readBytes() }
                c.disconnect()
                Resp(code, "", b)
            } else {
                val t = stream?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                Resp(code, t)
            }
        }
        return Resp(401, "")
    }

    fun image(cfg: PrinterCfg, path: String, maxPx: Int, baseOverride: String? = null): Bitmap? = try {
        val r = request(cfg, "GET", path, binary = true, baseOverride = baseOverride)
        val b = r.bytes
        if (r.code in 200..299 && b != null) BitmapFactory.decodeByteArray(b, 0, b.size)?.let { Tasks.scale(it, maxPx) } else null
    } catch (_: Exception) { null }

    fun enc(path: String) = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
