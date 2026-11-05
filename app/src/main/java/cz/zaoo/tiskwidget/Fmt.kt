package cz.zaoo.tiskwidget

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    data class Info(
        val state: String,
        val pct: Int,
        val remain: Int,
        val layer: Int,
        val total: Int,
        val name: String,
        val nozzle: Double,
        val bed: Double,
        val updated: Long,      // čas, ke kterému platí "remain" (kvůli výpočtu ETA)
        val realUpdated: Long,  // kdy opravdu přišla data
        val connected: Boolean,
        val error: String,
        val printError: Long,   // kód chyby z tiskárny (0 = bez chyby)
        val hmsCount: Int,      // počet aktivních HMS hlášení
        val lightOn: Boolean?,
    )

    fun info(s: Store): Info {
        val j = try { JSONObject(s.stateJson) } catch (e: Exception) { JSONObject() }
        // Zbývající čas odpočítává i mezi zprávami z tiskárny
        val raw = j.optInt("mc_remaining_time", 0)
        val st = j.optString("gcode_state", "")
        val elapsedMin = if (s.lastUpdate > 0 && st == "RUNNING")
            ((System.currentTimeMillis() - s.lastUpdate) / 60_000L).toInt() else 0
        val base = if (elapsedMin > 0) s.lastUpdate + elapsedMin * 60_000L else s.lastUpdate
        return Info(
            state = j.optString("gcode_state", ""),
            pct = j.optInt("mc_percent", 0),
            remain = (raw - elapsedMin).coerceAtLeast(0),
            layer = j.optInt("layer_num", 0),
            total = j.optInt("total_layer_num", 0),
            name = j.optString("subtask_name", "").removeSuffix(".3mf").removeSuffix(".gcode"),
            nozzle = j.optDouble("nozzle_temper", 0.0),
            bed = j.optDouble("bed_temper", 0.0),
            updated = base,
            realUpdated = s.lastUpdate,
            connected = s.connected,
            error = s.error,
            printError = j.optLong("print_error", 0L),
            hmsCount = j.optJSONArray("hms")?.length() ?: 0,
            lightOn = j.optJSONArray("lights_report")?.let { arr ->
                (0 until arr.length()).map { arr.optJSONObject(it) }
                    .firstOrNull { it?.optString("node") == "chamber_light" }
                    ?.optString("mode")?.let { it == "on" }
            },
        )
    }

    fun active(st: String) = st == "RUNNING" || st == "PAUSE" || st == "PREPARE" || st == "SLICING"

    fun stateCz(st: String) = when (st) {
        "RUNNING" -> "Tiskne"
        "PAUSE" -> "Pozastaveno"
        "PREPARE" -> "Připravuje tisk"
        "SLICING" -> "Slicuje"
        "FINISH" -> "Tisk hotový"
        "FAILED" -> "Tisk skončil chybou"
        "IDLE" -> "Tiskárna nečinná"
        "" -> "Čekám na data"
        else -> st
    }

    fun remain(min: Int): String {
        if (min <= 0) return "0 min"
        val h = min / 60
        val m = min % 60
        return if (h == 0) "$m min" else "$h h $m min"
    }

    /** Krátký formát pro kulatý/čtvercový widget: 3:32 h nebo 45 min. */
    fun remainShort(min: Int): String {
        if (min <= 0) return "0 min"
        return if (min < 60) "$min min" else "%d:%02d h".format(min / 60, min % 60)
    }

    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun eta(i: Info): String {
        val base = if (i.updated > 0) i.updated else System.currentTimeMillis()
        val end = base + i.remain * 60_000L
        val a = Calendar.getInstance()
        val b = Calendar.getInstance().apply { timeInMillis = end }
        val sameDay = a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        return if (sameDay) clock(end) else "zítra " + clock(end)
    }

    fun title(i: Info): String = when {
        i.state.isEmpty() && i.error.isNotEmpty() -> "Nepřipojeno"
        i.state.isEmpty() -> "Připojuji se k tiskárně…"
        active(i.state) -> "${i.pct} % · zbývá ${remain(i.remain)}"
        else -> stateCz(i.state)
    }

    /** Kód chyby ve formátu, jaký ukazuje Bambu (např. 0300-400C). */
    fun errCode(code: Long): String {
        val h = "%08X".format(code and 0xFFFFFFFFL)
        return h.substring(0, 4) + "-" + h.substring(4)
    }

    fun hasError(i: Info) = i.printError != 0L || i.hmsCount > 0

    fun errText(i: Info): String = when {
        i.printError != 0L -> "⚠ Chyba tiskárny ${errCode(i.printError)}"
        i.hmsCount > 0 -> "⚠ Tiskárna hlásí upozornění (${i.hmsCount})"
        else -> ""
    }

    fun temps(i: Info) = "tryska ${i.nozzle.toInt()}° · podložka ${i.bed.toInt()}°"

    fun longText(s: Store): String {
        val i = info(s)
        val sb = StringBuilder()
        sb.append(stateCz(i.state)).append('\n')
        if (active(i.state)) {
            sb.append("${i.pct} %  ·  zbývá ${remain(i.remain)}  ·  hotovo v ${eta(i)}\n")
            if (i.name.isNotEmpty()) sb.append("Model: ${i.name}\n")
            if (i.total > 0) sb.append("Vrstva ${i.layer} / ${i.total}\n")
        }
        if (hasError(i)) sb.append(errText(i)).append(" – podrobnosti v Bambu Handy\n")
        if (i.state.isNotEmpty()) sb.append(temps(i)).append('\n')
        if (i.lightOn != null) sb.append(if (i.lightOn) "💡 Světlo zapnuté\n" else "Světlo vypnuté\n")
        sb.append(if (i.connected) "🟢 Připojeno" else "🔴 Nepřipojeno")
        if (i.realUpdated > 0) sb.append("  ·  data z ${clock(i.realUpdated)}")
        if (i.error.isNotEmpty()) sb.append("\n⚠ ").append(i.error)
        return sb.toString()
    }
}
