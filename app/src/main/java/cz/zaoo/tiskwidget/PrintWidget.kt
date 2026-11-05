package cz.zaoo.tiskwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** Co všechno může widget ukazovat (klíč, popis, výchozí stav). */
object WidgetOpts {
    data class Opt(val key: String, val label: String, val def: Boolean)

    val ALL = listOf(
        Opt("time", "Zbývající čas", true),
        Opt("pct", "Procenta", true),
        Opt("progress", "Ukazatel průběhu", true),
        Opt("name", "Název modelu", true),
        Opt("eta", "Kdy bude hotovo (hodina)", true),
        Opt("thumb", "Náhled modelu (u obdélníku)", true),
        Opt("fil", "Aktuální filament (barva a typ)", false),
        Opt("layers", "Vrstvy (38/236)", false),
        Opt("temps", "Teploty trysky a podložky", false),
        Opt("updated", "Čas poslední aktualizace", false),
        Opt("cam", "Tlačítko kamery", true),
        Opt("light", "Průhlednější pozadí", false),
    )

    /** Tvary widgetu: klíč, popis, layout. */
    val SHAPES = listOf(
        Triple("rect", "Obdélník (3×1)", R.layout.widget),
        Triple("strip", "Tenký pruh (3×1 nebo 4×1)", R.layout.widget_strip),
        Triple("square", "Zaoblený čtverec s kruhem (2×2 a větší)", R.layout.widget_square),
        Triple("circle", "Kruh (2×2 a větší)", R.layout.widget_circle),
        Triple("neon_panel", "✨ Futuristický panel (3×1, 4×1, 4×2)", R.layout.widget_neon_panel),
        Triple("neon_ring", "✨ Futuristický kruh (2×2 a větší)", R.layout.widget_neon_ring),
    )

    fun shape(ctx: Context): String = prefs(ctx).getString("shape", "rect") ?: "rect"
    fun setShape(ctx: Context, v: String) = prefs(ctx).edit().putString("shape", v).apply()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("widget", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): Boolean {
        val def = ALL.firstOrNull { it.key == key }?.def ?: true
        return prefs(ctx).getBoolean(key, def)
    }

    fun set(ctx: Context, key: String, v: Boolean) = prefs(ctx).edit().putBoolean(key, v).apply()
}

class PrintWidget : AppWidgetProvider() {

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        render(context)  // změna velikosti → přepočítat velikost písma
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context)
        if (Store(context).configured) MonitorService.start(context)
    }

    companion object {
        fun render(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PrintWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                val o = mgr.getAppWidgetOptions(id)
                // na výšku: šířka = MIN_WIDTH, výška = MAX_HEIGHT (v dp)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val hgt = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                val d = if (w > 0 && hgt > 0) minOf(w, hgt) else 0
                try { mgr.updateAppWidget(id, build(ctx, sizeDp = d, wDp = w, hDp = hgt)) } catch (_: Exception) {}
            }
        }

        /** Sestaví vzhled widgetu (používá se i pro náhled v nastavení). */
        fun build(
            ctx: Context,
            opt: (String) -> Boolean = { WidgetOpts.get(ctx, it) },
            shape: String = WidgetOpts.shape(ctx),
            sizeDp: Int = 0,
            wDp: Int = 0,
            hDp: Int = 0,
        ): RemoteViews {
            if (shape.startsWith("neon")) return buildNeon(ctx, opt, shape, wDp, hDp)
            val store = Store(ctx)
            val i = Fmt.info(store)
            val layout = WidgetOpts.SHAPES.firstOrNull { it.first == shape }?.third ?: R.layout.widget
            val v = RemoteViews(ctx.packageName, layout)
            val round = shape == "square" || shape == "circle"
            val active = Fmt.active(i.state)
            fun vis(id: Int, show: Boolean) = v.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)

            val light = opt("light")
            when (shape) {
                "circle" -> {}  // pozadí kruhu je součástí obrázku
                "strip" -> v.setInt(R.id.w_root, "setBackgroundResource",
                    if (light) R.drawable.pill_bg_light else R.drawable.pill_bg)
                "square" -> v.setInt(R.id.w_root, "setBackgroundResource",
                    if (light) R.drawable.square_bg_light else R.drawable.square_bg)
                else -> v.setInt(R.id.w_root, "setBackgroundResource",
                    if (light) R.drawable.widget_bg_light else R.drawable.widget_bg)
            }
            // Kruh/čtverec: písmo roste s velikostí widgetu (2×2, 3×3, 4×4…)
            if (round && sizeDp > 0) {
                val d = sizeDp.toFloat()
                fun ts(id: Int, k: Float, min: Float) = v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, maxOf(min, d * k))
                ts(R.id.w_time, 0.15f, 13f)
                ts(R.id.w_pct, 0.095f, 10f)
                ts(R.id.w_info, 0.072f, 9f)
                ts(R.id.w_cam, 0.085f, 11f)
            }

            if (!store.configured) {
                v.setTextViewText(R.id.w_time, if (round) "P1S" else "Tisk P1S")
                v.setTextViewText(R.id.w_info, if (round) "Klepni" else "Klepni a přihlas se")
                vis(R.id.w_time, true); vis(R.id.w_info, true)
                v.setTextViewText(R.id.w_pct, ""); vis(R.id.w_cam, false)
                if (round) v.setImageViewBitmap(R.id.w_ring, ring(0, false, shape == "circle", light))
                else vis(R.id.w_progress, false)
            } else {
                // Hlavní řádek
                val main = when {
                    active -> if (round) Fmt.remainShort(i.remain) else Fmt.remain(i.remain)
                    i.state == "FINISH" -> if (round) "Hotovo" else "Hotovo ✅"
                    i.state == "FAILED" -> "Chyba ❌"
                    i.state == "IDLE" -> "Nečinná"
                    i.state.isEmpty() -> "—"
                    else -> Fmt.stateCz(i.state)
                }
                val showTime = opt("time") || !active
                v.setTextViewText(R.id.w_time, if (showTime) main else "${i.pct} %")
                vis(R.id.w_time, true)

                val pctText = when {
                    i.state == "PAUSE" -> "⏸ ${i.pct} %"
                    active -> "${i.pct} %"
                    else -> ""
                }
                // w_pct drží kameru vpravo, proto zůstává viditelný (jen bez textu)
                v.setTextViewText(R.id.w_pct, if (opt("pct") && showTime) pctText else "")

                val barPct = when { i.state == "FINISH" -> 100; active -> i.pct; else -> 0 }
                if (round) {
                    v.setImageViewBitmap(R.id.w_ring, ring(barPct, opt("progress"), shape == "circle", light))
                } else {
                    val showBar = opt("progress") && (active || i.state == "FINISH")
                    vis(R.id.w_progress, showBar)
                    if (showBar) v.setProgressBar(R.id.w_progress, 100, barPct, false)
                }

                // Spodní řádek – poskládaný z vybraných údajů
                val parts = mutableListOf<CharSequence>()
                if (opt("name") && !round && i.name.isNotEmpty() && (active || i.state == "FINISH")) parts += i.name
                if (opt("eta") && active && i.state != "PAUSE") parts += if (round) Fmt.eta(i) else "hotovo v ${Fmt.eta(i)}"
                if (opt("fil") && active) Ams.current(store)?.let { t ->
                    val sp = android.text.SpannableString("● ${t.type}")
                    sp.setSpan(android.text.style.ForegroundColorSpan(t.color), 0, 1, 0)
                    parts += sp
                }
                if (opt("layers") && active && i.total > 0) parts += "vrstva ${i.layer}/${i.total}"
                if (opt("temps") && i.state.isNotEmpty()) parts += "${i.nozzle.toInt()}° / ${i.bed.toInt()}°"
                if (opt("updated") && i.realUpdated > 0) parts += Fmt.clock(i.realUpdated)
                val refreshing = store.refreshAt > i.realUpdated && System.currentTimeMillis() - store.refreshAt < 15_000
                if (Fmt.hasError(i)) parts.add(0, if (round || i.printError == 0L) "⚠ chyba" else "⚠ ${Fmt.errCode(i.printError)}")
                if (refreshing) parts.add(0, "⟳ obnovuji…")
                else if (!i.connected) parts += "offline"
                if (i.error.isNotEmpty() && !active && !round) parts += i.error
                // do kolečka se vejdou max. 2 údaje
                val shown = if (round) parts.take(2) else parts
                val joined = android.text.SpannableStringBuilder()
                shown.forEachIndexed { idx, part ->
                    if (idx > 0) joined.append(if (round) "\n" else " · ")
                    joined.append(part)
                }
                v.setTextViewText(R.id.w_info, joined)
                vis(R.id.w_info, parts.isNotEmpty() || shape == "strip")

                vis(R.id.w_cam, opt("cam") && store.hasCamera)

                if (shape == "rect") {
                    val thumb = if (opt("thumb") && (active || i.state == "FINISH") && store.coverName.isNotEmpty())
                        Cover.load(ctx, 144) else null
                    if (thumb != null) v.setImageViewBitmap(R.id.w_thumb, thumb)
                    vis(R.id.w_thumb, thumb != null)
                }
            }

            v.setOnClickPendingIntent(R.id.w_root,
                if (store.configured) MonitorService.refreshIntent(ctx) else Notifs.openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_cam, Notifs.openCamera(ctx))
            return v
        }

        /** Futuristický vzhled – data se poskládají sem, kreslí je Neon.kt. */
        private fun buildNeon(ctx: Context, opt: (String) -> Boolean, shape: String, wDp: Int, hDp: Int): RemoteViews {
            val store = Store(ctx)
            val i = Fmt.info(store)
            val panel = shape == "neon_panel"
            val layout = if (panel) R.layout.widget_neon_panel else R.layout.widget_neon_ring
            val v = RemoteViews(ctx.packageName, layout)
            val active = Fmt.active(i.state)
            val err = Fmt.hasError(i)

            var a = Neon.CYAN; var b = Neon.GREEN
            val label: String
            val time: String
            var pct = i.pct
            when {
                !store.configured -> { a = Neon.GREY; b = Neon.GREY; label = "TISK P1S"; time = "NASTAV"; pct = 0 }
                err -> { a = Neon.RED; b = Neon.RED2
                    label = if (i.printError != 0L) "CHYBA ${Fmt.errCode(i.printError)}" else "UPOZORNĚNÍ"
                    time = if (active) hm(i.remain) else "!!" }
                i.state == "PAUSE" -> { a = Neon.AMBER; b = Neon.AMBER2; label = "POZASTAVENO"; time = hm(i.remain) }
                i.state == "PREPARE" -> { label = "PŘÍPRAVA"; time = hm(i.remain) }
                active -> { label = "ZBÝVÁ"; time = hm(i.remain) }
                i.state == "FINISH" -> { b = Neon.GREEN; a = Neon.GREEN; label = "TISK"; time = "HOTOVO"; pct = 100 }
                i.state == "FAILED" -> { a = Neon.RED; b = Neon.RED2; label = "TISK"; time = "CHYBA" }
                i.state == "IDLE" -> { a = Neon.GREY; b = Neon.GREY; label = "TISKÁRNA"; time = "--:--"; pct = 0 }
                else -> { label = "PŘIPOJUJI"; time = "--:--"; pct = 0 }
            }

            val parts = mutableListOf<String>()
            if (store.configured) {
                if (opt("name") && panel && i.name.isNotEmpty() && (active || i.state == "FINISH")) parts += i.name
                if (opt("eta") && active && i.state != "PAUSE") parts += "HOTOVO ${Fmt.eta(i)}"
                if (opt("layers") && active && i.total > 0) parts += "V ${i.layer}/${i.total}"
                if (opt("temps") && i.state.isNotEmpty()) parts += "${i.nozzle.toInt()}°/${i.bed.toInt()}°"
                if (opt("updated") && i.realUpdated > 0) parts += "AKT. ${Fmt.clock(i.realUpdated)}"
                val refreshing = store.refreshAt > i.realUpdated && System.currentTimeMillis() - store.refreshAt < 15_000
                if (refreshing) parts.add(0, "OBNOVUJI…") else if (!i.connected) parts += "OFFLINE"
            } else parts += "KLEPNI A PŘIHLAS SE"
            val shown = if (panel) parts else parts.take(1)

            val fil = if (opt("fil") && active) Ams.current(store) else null
            val showRing = opt("progress") && store.configured && (active || i.state == "FINISH" || !panel)
            val cam = opt("cam") && store.hasCamera
            val data = Neon.Data(
                pct = pct, showRing = showRing,
                time = if (opt("time") || !active) time else "$pct %",
                label = label, info = shown.joinToString("  ·  "),
                filColor = fil?.color, filType = fil?.type ?: "", cam = cam,
                a = a, b = b, light = opt("light"),
            )

            // velikost obrázku = velikost widgetu (max. 760 px na delší straně)
            val dens = ctx.resources.displayMetrics.density
            val wd = if (wDp > 0) wDp else if (panel) 320 else 170
            val hd = if (hDp > 0) hDp else if (panel) 110 else 170
            val k = minOf(dens, 760f / maxOf(wd, hd))
            val wp = (wd * k).toInt().coerceAtLeast(60)
            val hp = (hd * k).toInt().coerceAtLeast(40)
            val bmp = if (panel) Neon.panel(ctx, wp, hp, data) else Neon.ring(ctx, wp, hp, data)
            v.setImageViewBitmap(R.id.w_img, bmp)

            v.setOnClickPendingIntent(R.id.w_root,
                if (store.configured) MonitorService.refreshIntent(ctx) else Notifs.openApp(ctx))
            if (cam) v.setOnClickPendingIntent(R.id.w_cam, Notifs.openCamera(ctx))
            else v.setOnClickPendingIntent(R.id.w_cam,
                if (store.configured) MonitorService.refreshIntent(ctx) else Notifs.openApp(ctx))
            return v
        }

        private fun hm(min: Int): String = "%d:%02d".format(min / 60, min % 60)

        /**
         * Kruhový průběh jako obrázek – ImageView ho se scaleType=fitCenter
         * vždy zmenší tak, aby se celý vešel (kruh se tak nikdy neořízne).
         */
        private fun ring(pct: Int, showRing: Boolean, withCircle: Boolean, light: Boolean): Bitmap {
            val size = 320
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (withCircle) {
                p.style = Paint.Style.FILL
                p.color = if (light) 0x80181A1C.toInt() else 0xE6181A1C.toInt()
                c.drawCircle(size / 2f, size / 2f, size / 2f, p)
            }
            if (showRing) {
                val stroke = size * 0.06f
                val inset = if (withCircle) size * 0.09f else stroke / 2 + 2
                val r = RectF(inset, inset, size - inset, size - inset)
                p.style = Paint.Style.STROKE
                p.strokeWidth = stroke
                p.strokeCap = Paint.Cap.ROUND
                p.color = 0x33FFFFFF
                c.drawArc(r, 0f, 360f, false, p)
                if (pct > 0) {
                    p.color = 0xFF00AE42.toInt()
                    c.drawArc(r, -90f, 360f * pct.coerceAtMost(100) / 100f, false, p)
                }
            }
            return bmp
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store(context).configured) MonitorService.start(context)
    }
}
