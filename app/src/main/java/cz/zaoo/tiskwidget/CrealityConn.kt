package cz.zaoo.tiskwidget

import android.content.Context
import android.graphics.Bitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Creality s vlastním firmwarem (K1, K1C, K1 Max, K2, Hi, Ender-3 V3…):
 * WebSocket na portu 9999, tiskárna posílá stav po kouskách, každých 10 s
 * chce „ok“ na heart_beat. Bez rootu, bez Moonrakeru.
 */
class CrealityConn(private val ctx: Context, private val cfg: PrinterCfg, private val listener: Listener) : Conn {
    companion object {
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
        }

        fun wsUrl(host: String) = "ws://${Http.hostOnly(host)}:9999"

        /** Převod stavu Creality na náš jednotný stav. */
        fun parse(d: JSONObject, prev: Status): Status {
            val file = d.optString("printFileName", "")
            val progress = if (d.has("printProgress")) d.optDouble("printProgress", -1.0) else d.optDouble("dProgress", -1.0)
            val st = d.optInt("state", -1)
            val left = d.optDouble("printLeftTime", d.optDouble("printTimeLeft", 0.0))
            val err = d.optJSONObject("err")?.optInt("errcode", 0) ?: d.optInt("err", 0)
            val finishing = (st == 1 || st == 5) && left > 0
            val state = when {
                file.isEmpty() -> "IDLE"
                progress >= 100 && !finishing -> "FINISH"
                st == 5 -> "PAUSE"
                st == 4 -> "FAILED"
                st == 1 -> "RUNNING"
                st == 0 || st == 7 || st == 9 -> "PREPARE"
                else -> "IDLE"
            }
            return prev.copy(
                state = state,
                pct = progress.coerceIn(0.0, 100.0).toInt(),
                remain = (left / 60).toInt(),
                layer = d.optInt("layer", 0),
                total = d.optInt("TotalLayer", 0),
                name = file.substringAfterLast('/').removeSuffix(".gcode"),
                nozzle = d.optDouble("nozzleTemp", 0.0),
                bed = d.optDouble("bedTemp0", 0.0),
                light = if (d.has("lightSw")) d.optInt("lightSw", 0) else -1,
                alert = if (err != 0) "Chyba tiskárny (kód $err)" else "",
                updated = System.currentTimeMillis(),
                connected = true, error = "",
            )
        }

        /** Test pro průvodce: připojí se a počká na první data. */
        fun test(host: String): Status {
            val latch = CountDownLatch(1)
            var got: JSONObject? = null
            var fail: Throwable? = null
            val ws = http.newWebSocket(
                Request.Builder().url(wsUrl(host)).header("Sec-WebSocket-Protocol", "wsslicer").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"method":"get","params":{"ReqPrinterPara":1}}""")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val j = try { JSONObject(text) } catch (_: Exception) { return }
                        if (j.optString("ModeCode") == "heart_beat") { webSocket.send("ok"); return }
                        if (j.has("state") || j.has("nozzleTemp") || j.has("printProgress")) { got = j; latch.countDown() }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail = t; latch.countDown() }
                })
            latch.await(8, TimeUnit.SECONDS)
            ws.close(1000, null)
            got?.let { return parse(it, Status()) }
            throw fail as? Exception ?: java.net.SocketTimeoutException("tiskárna neposlala data")
        }
    }

    private val ex = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var lastRx = 0L
    private val data = JSONObject()

    override fun start() { ex.execute { connect() } }

    override fun stop() {
        stopped = true
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ex.shutdownNow()
    }

    override fun refresh() {
        ex.execute { if (ws == null) connect() else send("""{"method":"get","params":{"ReqPrinterPara":1}}""") }
    }

    private fun send(s: String) = try { ws?.send(s) ?: false } catch (_: Exception) { false }

    private fun connect() {
        if (stopped) return
        try { ws?.cancel() } catch (_: Exception) {}
        ws = http.newWebSocket(
            Request.Builder().url(wsUrl(cfg.host)).header("Sec-WebSocket-Protocol", "wsslicer").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastRx = System.currentTimeMillis()
                    webSocket.send("""{"method":"get","params":{"ReqPrinterPara":1}}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastRx = System.currentTimeMillis()
                    if (text == "ok") return
                    val j = try { JSONObject(text) } catch (_: Exception) { return }
                    if (j.optString("ModeCode") == "heart_beat") { webSocket.send("ok"); return }
                    onData(j)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lost("Tiskárna neodpovídá – je zapnutá a jsi na stejné Wi-Fi?") }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost("") }
            })
        // hlídání: každých 10 s si říct o data, po 30 s ticha připojit znovu
        ex.schedule(object : Runnable {
            override fun run() {
                if (stopped) return
                val now = System.currentTimeMillis()
                if (now - lastRx > 30_000) { connect(); return }
                send("""{"method":"get","params":{"ReqPrinterPara":1}}""")
                ex.schedule(this, 10, TimeUnit.SECONDS)
            }
        }, 10, TimeUnit.SECONDS)
    }

    private fun lost(err: String) {
        ws = null
        val prev = Statuses.get(ctx, cfg.id)
        val now = Statuses.edit(ctx, cfg.id) { it.copy(connected = false, error = err) }
        listener.changed(cfg.id, prev, now, prev.connected)
        if (!stopped) try { ex.schedule({ connect() }, 30, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    @Synchronized
    private fun onData(j: JSONObject) {
        j.keys().forEach { k -> data.put(k, j.get(k)) }
        val prev = Statuses.get(ctx, cfg.id)
        val now = parse(data, prev)
        Statuses.put(ctx, cfg.id, now)
        listener.changed(cfg.id, prev, now, prev.state != now.state || prev.alert != now.alert || prev.light != now.light)
    }

    fun cover(): Bitmap? =
        Http.image(cfg, "http://${Http.hostOnly(cfg.host)}/downloads/original/current_print_image.png", 400)

    override fun command(id: String, cmd: String): String {
        val p = when (cmd) {
            "pause" -> "\"pause\":1"
            "resume" -> "\"pause\":0"
            "stop" -> "\"stop\":1"
            "light_on" -> "\"lightSw\":1"
            "light_off" -> "\"lightSw\":0"
            else -> return "Neznámý příkaz."
        }
        return if (send("""{"method":"set","params":{$p}}""")) {
            ex.schedule({ send("""{"method":"get","params":{"ReqPrinterPara":1}}""") }, 3, TimeUnit.SECONDS)
            "Odesláno…"
        } else "Tiskárna není připojená."
    }
}
