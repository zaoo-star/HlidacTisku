package cz.zaoo.tiskwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

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
            val store = Store(ctx)
            val i = Fmt.info(store)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val active = Fmt.active(i.state)

            if (!store.configured) {
                v.setTextViewText(R.id.w_name, "Tisk P1S")
                v.setTextViewText(R.id.w_time, "Nastav mě")
                v.setTextViewText(R.id.w_info, "Klepni a přihlas se")
                v.setTextViewText(R.id.w_sub, "")
                v.setViewVisibility(R.id.w_progress, View.GONE)
            } else {
                v.setTextViewText(R.id.w_name,
                    if (active && i.name.isNotEmpty()) i.name else store.devName.ifEmpty { "Tisk P1S" })
                v.setTextViewText(R.id.w_time, when {
                    active -> Fmt.remain(i.remain)
                    i.state == "FINISH" -> "Hotovo ✅"
                    i.state == "FAILED" -> "Chyba ❌"
                    i.state == "IDLE" -> "Nečinná"
                    i.state.isEmpty() -> "—"
                    else -> Fmt.stateCz(i.state)
                })
                if (active || i.state == "FINISH") {
                    v.setViewVisibility(R.id.w_progress, View.VISIBLE)
                    v.setProgressBar(R.id.w_progress, 100, if (i.state == "FINISH") 100 else i.pct, false)
                } else {
                    v.setViewVisibility(R.id.w_progress, View.GONE)
                }
                v.setTextViewText(R.id.w_info, when {
                    i.state == "PAUSE" -> "⏸ Pozastaveno · ${i.pct} %"
                    i.state == "PREPARE" -> "Připravuje tisk…"
                    active -> "${i.pct} %  ·  hotovo v ${Fmt.eta(i)}"
                    i.error.isNotEmpty() -> i.error
                    else -> ""
                })
                val parts = mutableListOf<String>()
                if (active && i.total > 0) parts += "vrstva ${i.layer}/${i.total}"
                if (i.state.isNotEmpty()) parts += "${i.nozzle.toInt()}° / ${i.bed.toInt()}°"
                if (i.updated > 0) parts += Fmt.clock(i.updated)
                if (!i.connected) parts += "offline"
                v.setTextViewText(R.id.w_sub, parts.joinToString(" · "))
            }

            v.setOnClickPendingIntent(R.id.w_root, Notifs.openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_cam, Notifs.openCamera(ctx))
            v.setViewVisibility(R.id.w_cam, if (store.hasCamera) View.VISIBLE else View.GONE)
            mgr.updateAppWidget(ids, v)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store(context).configured) MonitorService.start(context)
    }
}
