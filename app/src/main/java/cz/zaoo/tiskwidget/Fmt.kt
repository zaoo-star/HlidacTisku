package cz.zaoo.tiskwidget

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    /**
     * Stav pro zobrazení: zbývající čas se mezi zprávami odpočítává sám,
     * `base` je čas, ke kterému `remain` platí (kvůli výpočtu „hotovo v“).
     */
    data class View(val s: Status, val remain: Int, val base: Long)

    fun view(s: Status): View {
        val elapsed = if (s.updated > 0 && s.state == "RUNNING")
            ((System.currentTimeMillis() - s.updated) / 60_000L).toInt() else 0
        val base = if (elapsed > 0) s.updated + elapsed * 60_000L else s.updated
        return View(s, (s.remain - elapsed).coerceAtLeast(0), base)
    }

    fun stateCz(st: String) = when (st) {
        "RUNNING" -> "Tiskne"
        "PAUSE" -> "Pozastaveno"
        "PREPARE" -> "Připravuje tisk"
        "FINISH" -> "Tisk hotový"
        "FAILED" -> "Tisk zrušen / chyba"
        "IDLE" -> "Nečinná"
        "" -> "Čekám na data"
        else -> st
    }

    fun remain(min: Int): String {
        if (min <= 0) return "0 min"
        val h = min / 60
        val m = min % 60
        return if (h == 0) "$m min" else "$h h $m min"
    }

    /** Krátce: 3:32 h nebo 45 min. */
    fun remainShort(min: Int): String {
        if (min <= 0) return "0 min"
        return if (min < 60) "$min min" else "%d:%02d h".format(min / 60, min % 60)
    }

    fun hm(min: Int) = "%d:%02d".format(min / 60, min % 60)

    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun eta(v: View): String {
        val base = if (v.base > 0) v.base else System.currentTimeMillis()
        val end = base + v.remain * 60_000L
        val a = Calendar.getInstance()
        val b = Calendar.getInstance().apply { timeInMillis = end }
        val sameDay = a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        return if (sameDay) clock(end) else "zítra " + clock(end)
    }

    /** Kód chyby ve formátu Bambu (např. 0300-400C). */
    fun errCode(code: Long): String {
        val h = "%08X".format(code and 0xFFFFFFFFL)
        return h.substring(0, 4) + "-" + h.substring(4)
    }

    fun errText(s: Status): String = when {
        s.printError != 0L -> "⚠ Chyba tiskárny ${errCode(s.printError)}"
        s.alert.isNotEmpty() -> "⚠ ${s.alert}"
        s.hms > 0 -> "⚠ Tiskárna hlásí upozornění (${s.hms})"
        else -> ""
    }

    fun temps(s: Status) = "${s.nozzle.toInt()}° / ${s.bed.toInt()}°"

    /** Titulek: „45 % · zbývá 1 h 20 min“ nebo stav. */
    fun title(s: Status): String {
        val v = view(s)
        return when {
            s.state.isEmpty() && s.error.isNotEmpty() -> "Nepřipojeno"
            s.state.isEmpty() -> "Připojuji se…"
            s.active -> "${s.pct} % · zbývá ${remain(v.remain)}"
            else -> stateCz(s.state)
        }
    }
}
