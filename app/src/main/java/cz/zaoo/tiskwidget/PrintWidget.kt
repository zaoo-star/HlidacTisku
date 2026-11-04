package cz.zaoo.tiskwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        Triple("square", "Čtverec s kruhovým průběhem (2×2)", R.layout.widget_square),
        Triple("circle", "Kruh (2×2)", R.layout.widget_circle),
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

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context)
        if (Store(context).configured) MonitorService.start(context)
    }

    companion object {
        fun render(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PrintWidget::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, build(ctx))
        }

        /** Sestaví vzhled widgetu (používá se i pro náhled v nastavení). */
        fun build(
            ctx: Context,
            opt: (String) -> Boolean = { WidgetOpts.get(ctx, it) },
            shape: String = WidgetOpts.shape(ctx),
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
                "circle" -> v.setInt(R.id.w_bg, "setBackgroundResource",
                    if (light) R.drawable.circle_bg_light else R.drawable.circle_bg)
                "strip" -> v.setInt(R.id.w_root, "setBackgroundResource",
                    if (light) R.drawable.pill_bg_light else R.drawable.pill_bg)
                else -> v.setInt(R.id.w_root, "setBackgroundResource",
                    if (light) R.drawable.widget_bg_light else R.drawable.widget_bg)
            }

            if (!store.configured) {
                v.setTextViewText(R.id.w_time, if (round) "P1S" else "Tisk P1S")
                v.setTextViewText(R.id.w_info, if (round) "Klepni" else "Klepni a přihlas se")
                vis(R.id.w_time, true); vis(R.id.w_info, true)
                v.setTextViewText(R.id.w_pct, ""); vis(R.id.w_progress, false); vis(R.id.w_cam, false)
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

                val showBar = opt("progress") && (active || i.state == "FINISH" || round)
                vis(R.id.w_progress, showBar)
                if (showBar) v.setProgressBar(R.id.w_progress, 100,
                    when { i.state == "FINISH" -> 100; active -> i.pct; else -> 0 }, false)

                // Spodní řádek – poskládaný z vybraných údajů
                val parts = mutableListOf<String>()
                if (opt("name") && !round && i.name.isNotEmpty() && (active || i.state == "FINISH")) parts += i.name
                if (opt("eta") && active && i.state != "PAUSE") parts += if (round) Fmt.eta(i) else "hotovo v ${Fmt.eta(i)}"
                if (opt("layers") && active && i.total > 0) parts += "vrstva ${i.layer}/${i.total}"
                if (opt("temps") && i.state.isNotEmpty()) parts += "${i.nozzle.toInt()}° / ${i.bed.toInt()}°"
                if (opt("updated") && i.realUpdated > 0) parts += Fmt.clock(i.realUpdated)
                val refreshing = store.refreshAt > i.realUpdated && System.currentTimeMillis() - store.refreshAt < 15_000
                if (refreshing) parts.add(0, "⟳ obnovuji…")
                else if (!i.connected) parts += "offline"
                if (i.error.isNotEmpty() && !active) parts += i.error
                v.setTextViewText(R.id.w_info, parts.joinToString(" · "))
                vis(R.id.w_info, parts.isNotEmpty() || shape == "strip")

                vis(R.id.w_cam, opt("cam") && store.hasCamera)
            }

            v.setOnClickPendingIntent(R.id.w_root,
                if (store.configured) MonitorService.refreshIntent(ctx) else Notifs.openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_cam, Notifs.openCamera(ctx))
            return v
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store(context).configured) MonitorService.start(context)
    }
}
