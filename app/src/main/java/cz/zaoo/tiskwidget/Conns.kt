package cz.zaoo.tiskwidget

import android.content.Context
import android.graphics.Bitmap
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/** Služba dostává změny stavu přes tohle rozhraní. */
interface Listener {
    fun changed(id: String, prev: Status, now: Status, important: Boolean)
}

/** Spojení s jednou (nebo u Bambu cloudu více) tiskárnami. */
interface Conn {
    fun start()
    fun stop()
    fun refresh()
    /** pause, resume, stop, light_on, light_off – vrací text pro uživatele */
    fun command(id: String, cmd: String): String
}

// ---------------------------------------------------------------- Bambu (MQTT)

class BambuConn(
    private val ctx: Context,
    private val printers: List<PrinterCfg>,   // cloud: všechny cloudové; LAN: jedna
    private val cloud: Boolean,
    private val listener: Listener,
) : Conn {
    companion object {
        private val KEYS = setOf(
            "gcode_state", "mc_percent", "mc_remaining_time", "layer_num", "total_layer_num",
            "subtask_name", "nozzle_temper", "bed_temper", "print_error", "hms", "lights_report", "ams", "vt_tray",
        )
    }

    private val store = Store(ctx)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var client: MqttClient? = null
    @Volatile private var stopped = false
    private val raws = HashMap<String, JSONObject>()
    private val bySerial = printers.associateBy { it.serial }

    override fun start() { worker.execute { connect() } }

    override fun stop() {
        stopped = true
        val c = client
        client = null
        worker.execute {
            try { c?.disconnectForcibly(500, 1000) } catch (_: Exception) {}
            try { c?.close() } catch (_: Exception) {}
        }
        worker.shutdown()
    }

    override fun refresh() {
        worker.execute { val c = client; if (c != null && c.isConnected) printers.forEach { pushAll(it) } else connect() }
    }

    private fun setConn(ok: Boolean, err: String) {
        for (p in printers) {
            val prev = Statuses.get(ctx, p.id)
            val now = Statuses.edit(ctx, p.id) { it.copy(connected = ok, error = err) }
            listener.changed(p.id, prev, now, true)
        }
    }

    private fun connect() {
        if (stopped) return
        client?.let { try { it.disconnectForcibly(500, 1000); it.close() } catch (_: Exception) {} }
        client = null
        val p0 = printers.firstOrNull() ?: return
        val uri = if (cloud) "ssl://us.mqtt.bambulab.com:8883" else "ssl://${p0.host}:8883"
        val user = if (cloud) store.username else "bblp"
        val pass = if (cloud) store.token else p0.accessCode
        var c: MqttClient? = null
        try {
            c = MqttClient(uri, "hl_" + UUID.randomUUID().toString().take(10), MemoryPersistence())
            val o = MqttConnectOptions().apply {
                userName = user
                password = pass.toCharArray()
                isAutomaticReconnect = true
                isCleanSession = true
                keepAliveInterval = 30
                connectionTimeout = 20
                maxReconnectDelay = 60_000
                socketFactory = if (cloud) SSLSocketFactory.getDefault() else Tls.trustAll().socketFactory
                if (!cloud) isHttpsHostnameVerificationEnabled = false
            }
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (cloud) store.cloudError = ""
                    setConn(true, "")
                    worker.execute {
                        printers.forEach {
                            try { client?.subscribe("device/${it.serial}/report", 0) } catch (_: Exception) {}
                            pushAll(it)
                        }
                    }
                }
                override fun connectionLost(cause: Throwable?) { setConn(false, "") }
                override fun messageArrived(topic: String?, message: MqttMessage) {
                    val serial = topic?.split('/')?.getOrNull(1) ?: return
                    bySerial[serial]?.let { onMessage(it, String(message.payload)) }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            client = c
            c.connect(o)
        } catch (e: Exception) {
            client = null
            try { c?.close() } catch (_: Exception) {}
            val code = (e as? MqttException)?.reasonCode ?: -1
            val auth = code == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                code == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
            val msg = when {
                auth && cloud -> "Přihlášení k Bambu účtu vypršelo – přihlas se znovu."
                auth -> "Špatný přístupový kód tiskárny."
                else -> "Nejde se připojit (${e.message ?: code})."
            }
            if (cloud && auth) store.cloudError = msg
            setConn(false, msg)
            if (!stopped) worker.schedule({ connect() }, if (auth) 15L else 1L, TimeUnit.MINUTES)
        }
    }

    private fun pushAll(p: PrinterCfg) {
        try {
            val msg = """{"pushing":{"sequence_id":"0","command":"pushall","version":1,"push_target":1}}"""
            client?.publish("device/${p.serial}/request", MqttMessage(msg.toByteArray()))
        } catch (_: Exception) {}
    }

    private fun onMessage(p: PrinterCfg, s: String) {
        val pr = try { JSONObject(s).optJSONObject("print") } catch (_: Exception) { null } ?: return
        val raw = raws.getOrPut(p.id) { Statuses.raw(ctx, p.id) }
        var changed = false
        pr.keys().forEach { k ->
            if (k in KEYS) {
                val nv = pr.get(k)
                val ov = raw.opt(k)
                if (k == "ams" && nv is JSONObject && ov is JSONObject) Json.deepMerge(ov, nv) else raw.put(k, nv)
                changed = true
            }
        }
        var ip = ""
        try {
            val n = pr.optJSONObject("net")?.optJSONArray("info")?.optJSONObject(0)?.optLong("ip", 0L) ?: 0L
            if (n != 0L) ip = "${n and 0xFF}.${(n shr 8) and 0xFF}.${(n shr 16) and 0xFF}.${(n shr 24) and 0xFF}"
        } catch (_: Exception) {}
        if (!changed && ip.isEmpty()) return
        Statuses.putRaw(ctx, p.id, raw)

        val st = when (val g = raw.optString("gcode_state", "")) {
            "RUNNING" -> "RUNNING"; "PAUSE" -> "PAUSE"; "PREPARE", "SLICING" -> "PREPARE"
            "FINISH" -> "FINISH"; "FAILED" -> "FAILED"; "IDLE" -> "IDLE"; else -> g
        }
        val light = raw.optJSONArray("lights_report")?.let { arr ->
            (0 until arr.length()).map { arr.optJSONObject(it) }
                .firstOrNull { it?.optString("node") == "chamber_light" }
                ?.optString("mode")?.let { if (it == "on") 1 else 0 }
        } ?: -1
        val prev = Statuses.get(ctx, p.id)
        val now = prev.copy(
            state = st,
            pct = raw.optInt("mc_percent", 0),
            remain = raw.optInt("mc_remaining_time", 0),
            layer = raw.optInt("layer_num", 0),
            total = raw.optInt("total_layer_num", 0),
            name = raw.optString("subtask_name", "").removeSuffix(".3mf").removeSuffix(".gcode"),
            nozzle = raw.optDouble("nozzle_temper", 0.0),
            bed = raw.optDouble("bed_temper", 0.0),
            printError = raw.optLong("print_error", 0L),
            hms = raw.optJSONArray("hms")?.length() ?: 0,
            light = light,
            autoIp = ip.ifEmpty { prev.autoIp },
            updated = System.currentTimeMillis(),
            connected = true, error = "",
        )
        Statuses.put(ctx, p.id, now)
        listener.changed(p.id, prev, now, prev.state != now.state || prev.printError != now.printError)
    }

    override fun command(id: String, cmd: String): String {
        val p = printers.firstOrNull { it.id == id } ?: return "Neznámá tiskárna."
        val c = client
        if (c == null || !c.isConnected) return "Tiskárna není připojená."
        val json = when (cmd) {
            "light_on", "light_off" -> """{"system":{"sequence_id":"0","command":"ledctrl","led_node":"chamber_light","led_mode":"${if (cmd == "light_on") "on" else "off"}","led_on_time":500,"led_off_time":500,"loop_times":0,"interval_time":0}}"""
            "pause", "resume", "stop" -> """{"print":{"sequence_id":"0","command":"$cmd","param":""}}"""
            else -> return "Neznámý příkaz."
        }
        return try {
            c.publish("device/${p.serial}/request", MqttMessage(json.toByteArray()))
            worker.schedule({ pushAll(p) }, 6, TimeUnit.SECONDS)
            "Odesláno…"
        } catch (e: Exception) { "Nepodařilo se odeslat: ${e.message}" }
    }

    /** Náhled modelu z Bambu cloudu. */
    fun cover(p: PrinterCfg): Bitmap? {
        if (!cloud || !store.hasCloud) return null
        return try {
            val t = Tasks.list(store.token, p.serial, 1).firstOrNull() ?: return null
            Tasks.download(t.cover, 400)
        } catch (_: Exception) { null }
    }
}

// ---------------------------------------------------------------- Prusa + Klipper (HTTP)

/** Společný základ: dotazuje se tiskárny každých pár vteřin. */
abstract class PollConn(protected val ctx: Context, protected val cfg: PrinterCfg, private val listener: Listener) : Conn {
    private val ex: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var stopped = false
    private var fails = 0

    override fun start() { ex.execute { tick() } }
    override fun stop() { stopped = true; ex.shutdownNow() }
    override fun refresh() { ex.execute { poll(true) } }

    private fun tick() {
        if (stopped) return
        val active = poll(false)
        val delay = when { fails > 0 -> 30L; active -> 5L; else -> 20L }
        if (!stopped) try { ex.schedule({ tick() }, delay, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    /** Vrací true, když se tiskne (častější dotazy). */
    private fun poll(force: Boolean): Boolean {
        val prev = Statuses.get(ctx, cfg.id)
        return try {
            val fresh = fetch(prev)
            fails = 0
            val now = fresh.copy(updated = System.currentTimeMillis(), connected = true, error = "",
                coverKey = prev.coverKey, cmdMsg = prev.cmdMsg, autoIp = prev.autoIp)
            Statuses.put(ctx, cfg.id, now)
            listener.changed(cfg.id, prev, now, force || prev.state != now.state || prev.alert != now.alert)
            now.active
        } catch (e: Exception) {
            fails++
            val now = Statuses.edit(ctx, cfg.id) { it.copy(connected = false, error = errText(e)) }
            listener.changed(cfg.id, prev, now, prev.connected)
            false
        }
    }

    protected open fun errText(e: Exception): String = when {
        e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
            "Tiskárna neodpovídá – je zapnutá a jsi na stejné Wi-Fi?"
        e.message?.contains("401") == true -> "Špatný API klíč nebo heslo."
        else -> "Chyba: ${e.message}"
    }

    protected fun json(method: String, path: String, body: String? = null, base: String? = null): JSONObject {
        val r = Http.request(cfg, method, path, body, baseOverride = base)
        if (r.code == 401 || r.code == 403) throw Exception("401")
        if (r.code !in 200..299) throw Exception("HTTP ${r.code}")
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }

    abstract fun fetch(prev: Status): Status
    abstract fun cover(): Bitmap?

    override fun command(id: String, cmd: String): String = try {
        doCommand(cmd)
        ex.schedule({ poll(true) }, 3, TimeUnit.SECONDS)
        "Odesláno…"
    } catch (e: Exception) { "Nepodařilo se: ${errText(e)}" }

    abstract fun doCommand(cmd: String)
}

/** Prusa (MK4, MK3.9, Mini, XL, Core One) přes PrusaLink. */
class PrusaConn(ctx: Context, cfg: PrinterCfg, l: Listener) : PollConn(ctx, cfg, l) {
    private var jobId = -1
    private var jobName = ""
    private var thumb = ""
    private var legacy = false

    override fun fetch(prev: Status): Status {
        if (!legacy) {
            val r = Http.request(cfg, "GET", "/api/v1/status")
            if (r.code == 404) legacy = true
            else {
                if (r.code == 401 || r.code == 403) throw Exception("401")
                if (r.code !in 200..299) throw Exception("HTTP ${r.code}")
                return parseV1(JSONObject(r.body))
            }
        }
        return parseLegacy()
    }

    private fun parseV1(j: JSONObject): Status {
        val pr = j.optJSONObject("printer") ?: JSONObject()
        val job = j.optJSONObject("job")
        val ps = pr.optString("state", "IDLE").uppercase()
        if (job != null) {
            val id = job.optInt("id", -1)
            if (id != jobId) {
                jobId = id
                try {
                    val f = json("GET", "/api/v1/job").optJSONObject("file")
                    jobName = f?.optString("display_name", f.optString("name", "")) ?: ""
                    thumb = f?.optJSONObject("refs")?.optString("thumbnail", "") ?: ""
                } catch (_: Exception) {}
            }
        }
        val state = when (ps) {
            "PRINTING" -> "RUNNING"; "PAUSED", "ATTENTION" -> "PAUSE"; "FINISHED" -> "FINISH"
            "STOPPED", "ERROR" -> "FAILED"; "BUSY" -> "PREPARE"; else -> "IDLE"
        }
        val alert = when (ps) {
            "ATTENTION" -> "Tiskárna vyžaduje pozornost"
            "ERROR" -> "Chyba tiskárny – podívej se na displej"
            else -> ""
        }
        return Status(
            state = state,
            pct = job?.optDouble("progress", 0.0)?.toInt() ?: 0,
            remain = ((job?.optInt("time_remaining", 0) ?: 0) / 60),
            name = jobName.substringBeforeLast(".bgcode").substringBeforeLast(".gcode"),
            nozzle = pr.optDouble("temp_nozzle", 0.0),
            bed = pr.optDouble("temp_bed", 0.0),
            alert = alert,
        )
    }

    /** Starší PrusaLink (rozhraní podobné OctoPrintu). */
    private fun parseLegacy(): Status {
        val job = json("GET", "/api/job")
        val printer = try { json("GET", "/api/printer") } catch (_: Exception) { JSONObject() }
        val stateTxt = job.optString("state", "").lowercase()
        val prog = job.optJSONObject("progress")
        val temps = printer.optJSONObject("temperature")
        val state = when {
            stateTxt.contains("print") -> "RUNNING"
            stateTxt.contains("paus") -> "PAUSE"
            stateTxt.contains("finish") -> "FINISH"
            stateTxt.contains("error") -> "FAILED"
            else -> "IDLE"
        }
        return Status(
            state = state,
            pct = ((prog?.optDouble("completion", 0.0) ?: 0.0) * (if ((prog?.optDouble("completion", 0.0) ?: 0.0) <= 1.0) 100 else 1)).toInt(),
            remain = (prog?.optInt("printTimeLeft", 0) ?: 0) / 60,
            name = job.optJSONObject("job")?.optJSONObject("file")?.optString("display_name", "") ?: "",
            nozzle = temps?.optJSONObject("tool0")?.optDouble("actual", 0.0) ?: 0.0,
            bed = temps?.optJSONObject("bed")?.optDouble("actual", 0.0) ?: 0.0,
        )
    }

    override fun cover(): Bitmap? = if (thumb.isNotEmpty()) Http.image(cfg, thumb, 400) else null

    override fun doCommand(cmd: String) {
        if (jobId < 0) throw Exception("Tiskárna zrovna netiskne")
        when (cmd) {
            "pause" -> json("PUT", "/api/v1/job/$jobId/pause")
            "resume" -> json("PUT", "/api/v1/job/$jobId/resume")
            "stop" -> json("DELETE", "/api/v1/job/$jobId")
            else -> throw Exception("Tohle Prusa neumí")
        }
    }
}

/** Klipper (Creality K1/K2, Elegoo Neptune 4, Sovol, Voron…) přes Moonraker. */
class KlipperConn(ctx: Context, cfg: PrinterCfg, l: Listener) : PollConn(ctx, cfg, l) {
    private var metaFile = ""
    private var estTime = 0.0
    private var layerCount = 0
    private var thumbPath = ""

    override fun fetch(prev: Status): Status {
        val res = json("GET", "/printer/objects/query?print_stats&virtual_sdcard&display_status&extruder&heater_bed")
            .optJSONObject("result")?.optJSONObject("status") ?: JSONObject()
        val ps = res.optJSONObject("print_stats") ?: JSONObject()
        val file = ps.optString("filename", "")
        if (file.isNotEmpty() && file != metaFile) {
            metaFile = file
            try {
                val m = json("GET", "/server/files/metadata?filename=" + Http.enc(file)).optJSONObject("result") ?: JSONObject()
                estTime = m.optDouble("estimated_time", 0.0)
                layerCount = m.optInt("layer_count", 0)
                val th = m.optJSONArray("thumbnails")
                var best: JSONObject? = null
                if (th != null) for (k in 0 until th.length()) {
                    val t = th.optJSONObject(k) ?: continue
                    if (best == null || t.optInt("width") > best.optInt("width")) best = t
                }
                val dir = file.substringBeforeLast('/', "")
                thumbPath = best?.optString("relative_path", "")?.let { if (it.isEmpty()) "" else (if (dir.isEmpty()) it else "$dir/$it") } ?: ""
            } catch (_: Exception) { estTime = 0.0; thumbPath = "" }
        }
        val progress = res.optJSONObject("display_status")?.optDouble("progress", 0.0)
            ?.takeIf { it > 0 } ?: res.optJSONObject("virtual_sdcard")?.optDouble("progress", 0.0) ?: 0.0
        val dur = ps.optDouble("print_duration", 0.0)
        val remainS = when {
            progress > 0.1 -> dur / progress - dur
            estTime > 0 -> (estTime - dur).coerceAtLeast(0.0)
            progress > 0.01 -> dur / progress - dur
            else -> 0.0
        }
        val stTxt = ps.optString("state", "standby")
        val state = when (stTxt) {
            "printing" -> "RUNNING"; "paused" -> "PAUSE"; "complete" -> "FINISH"
            "cancelled", "error" -> "FAILED"; else -> "IDLE"
        }
        val info = ps.optJSONObject("info")
        return Status(
            state = state,
            pct = (progress * 100).toInt(),
            remain = (remainS / 60).toInt(),
            layer = info?.optInt("current_layer", 0) ?: 0,
            total = info?.optInt("total_layer", 0)?.takeIf { it > 0 } ?: layerCount,
            name = file.substringAfterLast('/').removeSuffix(".gcode"),
            nozzle = res.optJSONObject("extruder")?.optDouble("temperature", 0.0) ?: 0.0,
            bed = res.optJSONObject("heater_bed")?.optDouble("temperature", 0.0) ?: 0.0,
            alert = if (stTxt == "error") ps.optString("message", "Chyba tiskárny") else "",
        )
    }

    override fun cover(): Bitmap? = if (thumbPath.isNotEmpty()) Http.image(cfg, "/server/files/gcodes/" + Http.enc(thumbPath), 400) else null

    override fun doCommand(cmd: String) {
        when (cmd) {
            "pause" -> json("POST", "/printer/print/pause")
            "resume" -> json("POST", "/printer/print/resume")
            "stop" -> json("POST", "/printer/print/cancel")
            else -> throw Exception("Tohle Klipper neumí")
        }
    }
}

/** Jednorázový test připojení (průvodce přidáním tiskárny). */
object ConnTest {
    fun run(ctx: Context, cfg: PrinterCfg): Pair<Boolean, String> {
        val dummy = object : Listener { override fun changed(id: String, prev: Status, now: Status, important: Boolean) {} }
        return try {
            val s = when (cfg.type) {
                PrinterCfg.PRUSA -> PrusaConn(ctx, cfg, dummy).fetch(Status())
                PrinterCfg.KLIPPER -> KlipperConn(ctx, cfg, dummy).fetch(Status())
                else -> return true to "OK"
            }
            true to "✅ Připojeno – ${Fmt.stateCz(s.state).lowercase()}" + (if (s.nozzle > 0) ", tryska ${s.nozzle.toInt()}°" else "")
        } catch (e: Exception) {
            false to when {
                e.message?.contains("401") == true -> if (cfg.type == PrinterCfg.KLIPPER)
                    "❌ Tiskárna chce přihlášení – klepni na „Tiskárna chce přihlášení“ a zadej jméno a heslo z Fluiddu."
                    else "❌ Tiskárna odmítla přístup – zkontroluj API klíč / heslo."
                e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "❌ Tiskárna neodpovídá – zkontroluj IP adresu a že je telefon na stejné Wi-Fi."
                e is java.net.UnknownHostException -> "❌ Adresa nenalezena – zadej jen IP, např. 192.168.1.50."
                else -> "❌ Nepodařilo se připojit: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}
