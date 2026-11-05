package cz.zaoo.tiskwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Jedna tiskárna v appce. */
data class PrinterCfg(
    val id: String,
    val name: String,
    val type: String,
    val host: String = "",       // IP (u Klipperu i s portem), u Bambu cloudu jen pro kameru
    val serial: String = "",     // Bambu
    val accessCode: String = "", // Bambu (LAN kód, i pro kameru)
    val apiKey: String = "",     // Prusa / Klipper
    val user: String = "",       // Prusa (digest)
    val password: String = "",   // Prusa (digest)
    val model: String = "",
) {
    companion object {
        const val BAMBU_CLOUD = "bambu_cloud"
        const val BAMBU_LAN = "bambu_lan"
        const val PRUSA = "prusa"
        const val KLIPPER = "klipper"

        fun fromJson(j: JSONObject) = PrinterCfg(
            id = j.optString("id"), name = j.optString("name"), type = j.optString("type"),
            host = j.optString("host"), serial = j.optString("serial"), accessCode = j.optString("accessCode"),
            apiKey = j.optString("apiKey"), user = j.optString("user"), password = j.optString("password"),
            model = j.optString("model"),
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type).put("host", host).put("serial", serial)
        .put("accessCode", accessCode).put("apiKey", apiKey).put("user", user).put("password", password)
        .put("model", model)

    val isBambu get() = type == BAMBU_CLOUD || type == BAMBU_LAN
    val brand get() = when (type) { BAMBU_CLOUD, BAMBU_LAN -> "Bambu Lab"; PRUSA -> "Prusa"; KLIPPER -> "Klipper"; else -> type }
    val connLabel get() = when (type) {
        BAMBU_CLOUD -> "Bambu účet"; BAMBU_LAN -> "domácí síť"; PRUSA -> "PrusaLink"; KLIPPER -> "Moonraker"; else -> ""
    }

    /** Má kameru, kterou umíme zobrazit? */
    fun hasCamera(st: Status): Boolean = when (type) {
        BAMBU_CLOUD, BAMBU_LAN -> camHost(st).isNotEmpty() && accessCode.isNotEmpty() && !model.uppercase().let { it.startsWith("X1") || it.startsWith("H2") }
        KLIPPER -> host.isNotEmpty()
        else -> false
    }

    fun camHost(st: Status) = host.ifEmpty { st.autoIp }

    val canLight get() = isBambu
    val canControl get() = true
    val hasHistory get() = type == BAMBU_CLOUD || type == KLIPPER
}

/** Seznam tiskáren (uložený v SharedPreferences jako JSON). */
object Printers {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("printers", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<PrinterCfg> = try {
        val arr = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until arr.length()).map { PrinterCfg.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun get(ctx: Context, id: String?): PrinterCfg? = all(ctx).firstOrNull { it.id == id }

    private fun save(ctx: Context, list: List<PrinterCfg>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun add(ctx: Context, cfg: PrinterCfg) {
        val list = all(ctx).filter { it.id != cfg.id } + cfg
        save(ctx, list)
    }

    fun update(ctx: Context, cfg: PrinterCfg) {
        save(ctx, all(ctx).map { if (it.id == cfg.id) cfg else it })
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
        Statuses.clear(ctx, id)
    }

    fun newId(prefix: String) = prefix + "_" + System.currentTimeMillis().toString(36)
}

/** Jednotný stav tiskárny – stejný pro všechny značky. */
data class Status(
    val state: String = "",      // RUNNING, PAUSE, PREPARE, FINISH, FAILED, IDLE, "" = neznámo
    val pct: Int = 0,
    val remain: Int = 0,         // minuty
    val layer: Int = 0,
    val total: Int = 0,
    val name: String = "",
    val nozzle: Double = 0.0,
    val bed: Double = 0.0,
    val updated: Long = 0L,      // kdy přišla data
    val connected: Boolean = false,
    val error: String = "",      // chyba připojení
    val printError: Long = 0L,   // Bambu kód chyby
    val alert: String = "",      // textové upozornění tiskárny (Prusa, Klipper)
    val hms: Int = 0,
    val light: Int = -1,         // -1 neznámo, 0 vyp, 1 zap
    val autoIp: String = "",
    val coverKey: String = "",   // pro který tisk je uložený náhled
    val cmdMsg: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state).put("pct", pct).put("remain", remain).put("layer", layer).put("total", total)
        .put("name", name).put("nozzle", nozzle).put("bed", bed).put("updated", updated)
        .put("connected", connected).put("error", error).put("printError", printError).put("alert", alert)
        .put("hms", hms).put("light", light).put("autoIp", autoIp).put("coverKey", coverKey).put("cmdMsg", cmdMsg)

    companion object {
        fun fromJson(j: JSONObject) = Status(
            state = j.optString("state"), pct = j.optInt("pct"), remain = j.optInt("remain"),
            layer = j.optInt("layer"), total = j.optInt("total"), name = j.optString("name"),
            nozzle = j.optDouble("nozzle", 0.0), bed = j.optDouble("bed", 0.0), updated = j.optLong("updated"),
            connected = j.optBoolean("connected"), error = j.optString("error"), printError = j.optLong("printError"),
            alert = j.optString("alert"), hms = j.optInt("hms"), light = j.optInt("light", -1),
            autoIp = j.optString("autoIp"), coverKey = j.optString("coverKey"), cmdMsg = j.optString("cmdMsg"),
        )
    }

    val active get() = state == "RUNNING" || state == "PAUSE" || state == "PREPARE"
    val hasError get() = printError != 0L || hms > 0 || alert.isNotEmpty()
}

/** Poslední stav každé tiskárny + surová data (Bambu kvůli AMS). */
object Statuses {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("status", Context.MODE_PRIVATE)
    private val cache = HashMap<String, Status>()

    @Synchronized
    fun get(ctx: Context, id: String): Status {
        cache[id]?.let { return it }
        val s = try { Status.fromJson(JSONObject(prefs(ctx).getString("s_$id", "{}") ?: "{}")) } catch (_: Exception) { Status() }
        cache[id] = s
        return s
    }

    @Synchronized
    fun put(ctx: Context, id: String, s: Status) {
        cache[id] = s
        prefs(ctx).edit().putString("s_$id", s.toJson().toString()).apply()
    }

    @Synchronized
    fun edit(ctx: Context, id: String, f: (Status) -> Status): Status {
        val n = f(get(ctx, id))
        put(ctx, id, n)
        return n
    }

    fun raw(ctx: Context, id: String): JSONObject =
        try { JSONObject(prefs(ctx).getString("r_$id", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun putRaw(ctx: Context, id: String, j: JSONObject) {
        prefs(ctx).edit().putString("r_$id", j.toString()).apply()
    }

    @Synchronized
    fun clear(ctx: Context, id: String) {
        cache.remove(id)
        prefs(ctx).edit().remove("s_$id").remove("r_$id").apply()
    }
}
