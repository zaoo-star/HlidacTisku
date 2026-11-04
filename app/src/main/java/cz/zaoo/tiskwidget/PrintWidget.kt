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
        fun build(ctx: Context, opt: (String) -> Boolean = { WidgetOpts.get(ctx, it) }): RemoteViews {
            val store = Store(ctx)
            val i = Fmt.info(store)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val active = Fmt.active(i.state)
            fun vis(id: Int, show: Boolean) = v.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)

            v.setInt(R.id.w_root, "setBackgroundResource",
                if (opt("light")) R.drawable.widget_bg_light else R.drawable.widget_bg)

            if (!store.configured) {
                v.setTextViewText(R.id.w_time, "Tisk P1S")
                v.setTextViewText(R.id.w_info, "Klepni a přihlas se")
                vis(R.id.w_time, true); vis(R.id.w_info, true)
                v.setTextViewText(R.id.w_pct, ""); vis(R.id.w_progress, false); vis(R.id.w_cam, false)
            } else {
                // Hlavní řádek
                val main = when {
                    active -> Fmt.remain(i.remain)
                    i.state == "FINISH" -> "Hotovo ✅"
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

                val showBar = opt("progress") && (active || i.state == "FINISH")
                vis(R.id.w_progress, showBar)
                if (showBar) v.setProgressBar(R.id.w_progress, 100, if (i.state == "FINISH") 100 else i.pct, false)

                // Spodní řádek – poskládaný z vybraných údajů
                val parts = mutableListOf<String>()
                if (opt("name") && i.name.isNotEmpty() && (active || i.state == "FINISH")) parts += i.name
                if (opt("eta") && active && i.state != "PAUSE") parts += "hotovo v ${Fmt.eta(i)}"
                if (opt("layers") && active && i.total > 0) parts += "vrstva ${i.layer}/${i.total}"
                if (opt("temps") && i.state.isNotEmpty()) parts += "${i.nozzle.toInt()}° / ${i.bed.toInt()}°"
                if (opt("updated") && i.updated > 0) parts += Fmt.clock(i.updated)
                val refreshing = store.refreshAt > i.updated && System.currentTimeMillis() - store.refreshAt < 15_000
                if (refreshing) parts.add(0, "⟳ obnovuji…")
                else if (!i.connected) parts += "offline"
                if (i.error.isNotEmpty() && !active) parts += i.error
                v.setTextViewText(R.id.w_info, parts.joinToString(" · "))
                vis(R.id.w_info, parts.isNotEmpty())

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
