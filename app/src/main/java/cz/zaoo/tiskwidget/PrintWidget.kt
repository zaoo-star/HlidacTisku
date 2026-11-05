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
                try { mgr.updateAppWidget(id, build(ctx, sizeDp = d)) } catch (_: Exception) {}
            }
        }

        /** Sestaví vzhled widgetu (používá se i pro náhled v nastavení). */
        fun build(
            ctx: Context,
            opt: (String) -> Boolean = { WidgetOpts.get(ctx, it) },
            shape: String = WidgetOpts.shape(ctx),
            sizeDp: Int = 0,
        ): RemoteViews {
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
                val parts = mutableListOf<String>()
                if (opt("name") && !round && i.name.isNotEmpty() && (active || i.state == "FINISH")) parts += i.name
                if (opt("eta") && active && i.state != "PAUSE") parts += if (round) Fmt.eta(i) else "hotovo v ${Fmt.eta(i)}"
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
                v.setTextViewText(R.id.w_info, shown.joinToString(if (round) "\n" else " · "))
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
