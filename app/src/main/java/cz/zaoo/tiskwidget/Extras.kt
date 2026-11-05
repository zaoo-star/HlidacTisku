package cz.zaoo.tiskwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Sloučení částečných zpráv z tiskárny (AMS chodí po kouskách). */
object Json {
    fun deepMerge(dst: JSONObject, src: JSONObject) {
        val keys = src.keys().asSequence().toList()
        for (k in keys) {
            val sv = src.get(k)
            val dv = dst.opt(k)
            when {
                sv is JSONObject && dv is JSONObject -> deepMerge(dv, sv)
                sv is JSONArray && dv is JSONArray -> mergeArr(dv, sv)
                else -> dst.put(k, sv)
            }
        }
    }

    private fun mergeArr(dst: JSONArray, src: JSONArray) {
        for (i in 0 until src.length()) {
            val so = src.get(i)
            if (so is JSONObject) {
                val id = so.optString("id", "")
                var target: JSONObject? = null
                if (id.isNotEmpty()) {
                    for (j in 0 until dst.length()) {
                        val d = dst.optJSONObject(j)
                        if (d != null && d.optString("id", "") == id) { target = d; break }
                    }
                } else target = dst.optJSONObject(i)
                if (target != null) deepMerge(target, so) else dst.put(so)
            } else {
                dst.put(i, so)
            }
        }
    }
}

/** AMS – co je založené v jednotlivých slotech. */
object Ams {
    data class Tray(
        val label: String,     // A1…A4, B1…, Ext
        val type: String,      // PLA, PETG… (prázdné = nic založeno)
        val color: Int,
        val remain: Int,       // % nebo -1 když neznámé (cizí filament)
        val active: Boolean,
    )

    private fun parseColor(hex: String): Int = try {
        val h = hex.padEnd(8, 'F')
        Color.argb(255, h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    } catch (_: Exception) { Color.GRAY }

    /** Aktivní slot (ze kterého se tiskne), nebo null. */
    fun current(s: Store): Tray? = trays(s).firstOrNull { it.active && it.type.isNotEmpty() }

    /** Vyplní řádek barevnými sloty (sdílí appka i nastavení widgetu). */
    fun fillRow(ctx: Context, box: android.widget.LinearLayout, trays: List<Tray>) {
        val dens = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()
        box.removeAllViews()
        for (t in trays) {
            val cell = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(6), dp(2), dp(6))
                if (t.active) background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setStroke(dp(2), 0xFF00AE42.toInt())
                }
            }
            val dot = android.view.View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (t.type.isEmpty()) 0xFF333333.toInt() else t.color)
                    setStroke(dp(1), 0x66FFFFFF)
                }
            }
            cell.addView(dot, android.widget.LinearLayout.LayoutParams(dp(30), dp(30)))
            val rem = if (t.type.isEmpty()) "prázdné" else if (t.remain >= 0) "${t.remain} %" else "?"
            cell.addView(android.widget.TextView(ctx).apply {
                text = "${t.label}\n${t.type.ifEmpty { "–" }}\n$rem"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
            box.addView(cell, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    fun trays(s: Store): List<Tray> {
        val j = try { JSONObject(s.stateJson) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<Tray>()
        val ams = j.optJSONObject("ams")
        val trayNow = ams?.optString("tray_now", "255") ?: "255"
        val units = ams?.optJSONArray("ams")
        if (units != null) {
            for (u in 0 until units.length()) {
                val unit = units.optJSONObject(u) ?: continue
                val unitId = unit.optString("id", u.toString()).toIntOrNull() ?: u
                val trays = unit.optJSONArray("tray") ?: continue
                for (t in 0 until trays.length()) {
                    val tr = trays.optJSONObject(t) ?: continue
                    val trayId = tr.optString("id", t.toString()).toIntOrNull() ?: t
                    out += Tray(
                        label = "${'A' + unitId}${trayId + 1}",
                        type = tr.optString("tray_type", ""),
                        color = parseColor(tr.optString("tray_color", "808080FF")),
                        remain = tr.optInt("remain", -1),
                        active = trayNow == (unitId * 4 + trayId).toString(),
                    )
                }
            }
        }
        j.optJSONObject("vt_tray")?.let { vt ->
            if (vt.optString("tray_type", "").isNotEmpty()) {
                out += Tray("Ext", vt.optString("tray_type"), parseColor(vt.optString("tray_color", "808080FF")),
                    vt.optInt("remain", -1), trayNow == "254")
            }
        }
        return out
    }
}

/** Tisky z Bambu cloudu (historie, náhled modelu). */
object Tasks {
    data class Task(
        val title: String,
        val cover: String,
        val status: Int,
        val start: Long,
        val end: Long,
        val weight: Double,   // gramy
        val costTime: Int,    // sekundy
    )

    private fun time(s: String): Long = try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { 0L }

    fun list(s: Store, limit: Int): List<Task> {
        val c = URL("https://api.bambulab.com/v1/user-service/my/tasks?deviceId=${s.serial}&limit=$limit")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer ${s.token}")
        c.setRequestProperty("User-Agent", "bambu_network_agent/01.09.05.01")
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code == 401) throw Exception("Přihlášení vypršelo – přihlas se v appce znovu.")
        val hits = JSONObject(body).optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }.map { t ->
            Task(
                title = t.optString("title", t.optString("designTitle", "")),
                cover = t.optString("cover", ""),
                status = t.optInt("status", 0),
                start = time(t.optString("startTime", "")),
                end = time(t.optString("endTime", "")),
                weight = t.optDouble("weight", 0.0),
                costTime = t.optInt("costTime", 0),
            )
        }
    }

    fun download(url: String, maxPx: Int): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        val bytes = c.inputStream.use { it.readBytes() }
        c.disconnect()
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (b == null) null else scale(b, maxPx)
    } catch (_: Exception) { null }

    fun scale(b: Bitmap, maxPx: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxPx) return b
        val k = maxPx.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }
}

/** Náhled právě tištěného modelu (uložený v souboru). */
object Cover {
    private fun file(ctx: Context) = File(ctx.filesDir, "cover.png")

    fun load(ctx: Context, maxPx: Int): Bitmap? = try {
        val f = file(ctx)
        if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)?.let { Tasks.scale(it, maxPx) }
    } catch (_: Exception) { null }

    /** Stáhne náhled aktuálního tisku z cloudu (jen při změně modelu). */
    fun fetch(ctx: Context, s: Store, name: String): Boolean {
        if (s.mode != "cloud" || s.token.isEmpty()) return false
        return try {
            val t = Tasks.list(s, 1).firstOrNull() ?: return false
            val bmp = Tasks.download(t.cover, 400) ?: return false
            file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            s.coverName = name
            true
        } catch (_: Exception) { false }
    }

    fun clear(ctx: Context, s: Store) {
        try { file(ctx).delete() } catch (_: Exception) {}
        s.coverName = ""
    }
}

/** Hlasitý budík na konci tisku – zvoní jako budík i v tichém režimu. */
object Alarm {
    private var ringtone: Ringtone? = null
    private val h = Handler(Looper.getMainLooper())

    fun start(ctx: Context) {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
            h.postDelayed({ stop() }, 60_000)  // max. minutu
        } catch (_: Exception) {}
    }

    fun stop() {
        h.removeCallbacksAndMessages(null)
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }

    val ringing get() = ringtone?.isPlaying == true
}
