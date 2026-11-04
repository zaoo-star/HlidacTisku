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
        val updated: Long,
        val connected: Boolean,
        val error: String,
    )

    fun info(s: Store): Info {
        val j = try { JSONObject(s.stateJson) } catch (e: Exception) { JSONObject() }
        return Info(
            state = j.optString("gcode_state", ""),
            pct = j.optInt("mc_percent", 0),
            remain = j.optInt("mc_remaining_time", 0),
            layer = j.optInt("layer_num", 0),
            total = j.optInt("total_layer_num", 0),
            name = j.optString("subtask_name", "").removeSuffix(".3mf").removeSuffix(".gcode"),
            nozzle = j.optDouble("nozzle_temper", 0.0),
            bed = j.optDouble("bed_temper", 0.0),
            updated = s.lastUpdate,
            connected = s.connected,
            error = s.error,
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
        if (i.state.isNotEmpty()) sb.append(temps(i)).append('\n')
        sb.append(if (i.connected) "🟢 Připojeno" else "🔴 Nepřipojeno")
        if (i.updated > 0) sb.append("  ·  data z ${clock(i.updated)}")
        if (i.error.isNotEmpty()) sb.append("\n⚠ ").append(i.error)
        return sb.toString()
    }
}
