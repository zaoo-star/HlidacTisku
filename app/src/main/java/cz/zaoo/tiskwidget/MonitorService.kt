package cz.zaoo.tiskwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Tlačítko „Ztlumit“ / smazání notifikace vypne budík. */
class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarm.stop()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Printers.all(context).isNotEmpty()) MonitorService.start(context)
    }
}

/** Drží spojení se všemi tiskárnami a aktualizuje notifikaci + widgety. */
class MonitorService : Service(), Listener {

    companion object {
        const val NOTIF_ID = 1
        const val ACTION_RELOAD = "cz.zaoo.tiskwidget.RELOAD"
        const val ACTION_REFRESH = "cz.zaoo.tiskwidget.REFRESH"
        const val ACTION_CMD = "cz.zaoo.tiskwidget.CMD"

        private fun go(ctx: Context, i: Intent) {
            try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {}
        }

        fun start(ctx: Context) = go(ctx, Intent(ctx, MonitorService::class.java))

        /** Po přidání/smazání tiskárny nebo přihlášení. */
        fun reload(ctx: Context) = go(ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_RELOAD))

        fun refresh(ctx: Context) = go(ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_REFRESH))

        fun command(ctx: Context, id: String, cmd: String) =
            go(ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_CMD).putExtra("id", id).putExtra("cmd", cmd))

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MonitorService::class.java)) }

        /** Klepnutí na widget = vyžádat čerstvá data. */
        fun refreshIntent(ctx: Context): PendingIntent = PendingIntent.getForegroundService(
            ctx, 2, Intent(ctx, MonitorService::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val h = Handler(Looper.getMainLooper())
    private val conns = HashMap<String, Conn>()        // klíč: id tiskárny nebo "cloud"
    private val owner = HashMap<String, String>()      // id tiskárny -> klíč spojení
    private val coverTry = HashMap<String, String>()
    private var uiPending = false
    private var lastUnlock = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            if (now - lastUnlock < 20_000) return
            lastUnlock = now
            doUi()
            conns.values.forEach { it.refresh() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifs.channels(this)
        Store(this).migrate(this)
        try {
            val f = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(unlockReceiver, f)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifs.ongoing(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)

        if (Printers.all(this).isEmpty()) {
            stopConns()
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_RELOAD -> { stopConns(); buildConns() }
            ACTION_REFRESH -> {
                if (conns.isEmpty()) buildConns()
                Store(this).refreshAt = System.currentTimeMillis()
                doUi()
                conns.values.forEach { it.refresh() }
                h.postDelayed({ doUi() }, 16_000)
            }
            ACTION_CMD -> {
                if (conns.isEmpty()) buildConns()
                val id = intent.getStringExtra("id") ?: ""
                val cmd = intent.getStringExtra("cmd") ?: ""
                runCommand(id, cmd)
            }
            else -> if (conns.isEmpty()) buildConns()
        }
        h.removeCallbacks(watchdog)
        h.postDelayed(watchdog, 60_000L)
        Updater.backgroundCheck(this)
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        h.removeCallbacksAndMessages(null)
        stopConns()
        for (p in Printers.all(this)) Statuses.edit(this, p.id) { it.copy(connected = false) }
        PrintWidget.render(this)
        super.onDestroy()
    }

    private fun buildConns() {
        val store = Store(this)
        val all = Printers.all(this)
        val cloud = all.filter { it.type == PrinterCfg.BAMBU_CLOUD }
        if (cloud.isNotEmpty() && store.hasCloud) {
            val c = BambuConn(this, cloud, true, this)
            conns["cloud"] = c
            cloud.forEach { owner[it.id] = "cloud" }
        }
        for (p in all) {
            val c: Conn = when (p.type) {
                PrinterCfg.BAMBU_LAN -> BambuConn(this, listOf(p), false, this)
                PrinterCfg.PRUSA -> PrusaConn(this, p, this)
                PrinterCfg.KLIPPER -> KlipperConn(this, p, this)
                else -> continue
            }
            conns[p.id] = c
            owner[p.id] = p.id
        }
        conns.values.forEach { it.start() }
    }

    private fun stopConns() {
        conns.values.forEach { try { it.stop() } catch (_: Exception) {} }
        conns.clear()
        owner.clear()
    }

    private fun connFor(id: String): Conn? = owner[id]?.let { conns[it] }

    private fun runCommand(id: String, cmd: String) {
        val c = connFor(id) ?: return
        Thread {
            val msg = c.command(id, cmd)
            Statuses.edit(this, id) { it.copy(cmdMsg = msg) }
            h.post { doUi() }
            if (msg != "Odesláno…") return@Thread
            val label = mapOf("light_on" to "Rozsvítit", "light_off" to "Zhasnout", "pause" to "Pauza",
                "resume" to "Pokračovat", "stop" to "Zastavit")[cmd] ?: cmd
            h.postDelayed({
                val s = Statuses.get(this, id)
                val ok = when (cmd) {
                    "light_on" -> s.light == 1
                    "light_off" -> s.light == 0
                    "pause" -> s.state == "PAUSE"
                    "resume" -> s.state == "RUNNING" || s.state == "PREPARE"
                    "stop" -> !s.active
                    else -> true
                }
                val p = Printers.get(this, id)
                val txt = if (ok) "✅ $label – hotovo"
                else if (p?.isBambu == true && cmd != "light_on" && cmd != "light_off")
                    "❌ Tiskárna „$label“ neprovedla. Bambu to cizím appkám bez LAN/Developer módu blokuje."
                else "❌ Tiskárna „$label“ neprovedla."
                Statuses.edit(this, id) { it.copy(cmdMsg = txt) }
                doUi()
            }, 12_000)
        }.start()
    }

    /** Volá se ze spojení (z jiného vlákna) při každé změně. */
    override fun changed(id: String, prev: Status, now: Status, important: Boolean) {
        val p = Printers.get(this, id) ?: return
        if (prev.state.isNotEmpty() && now.state.isNotEmpty() && prev.state != now.state)
            Notifs.transition(this, p, prev.state, now)
        if (now.printError != 0L && now.printError != prev.printError) Notifs.error(this, p, Fmt.errCode(now.printError))
        if (now.alert.isNotEmpty() && now.alert != prev.alert) Notifs.error(this, p, now.alert)

        // náhled modelu – stáhnout, když se tiskne něco nového
        if (now.active && now.name.isNotEmpty() && now.coverKey != now.name && coverTry[id] != now.name) {
            coverTry[id] = now.name
            val c = connFor(id)
            Thread {
                try { Thread.sleep(if (p.type == PrinterCfg.BAMBU_CLOUD) 8000 else 500) } catch (_: InterruptedException) {}
                val bmp = when (c) {
                    is BambuConn -> c.cover(p)
                    is PollConn -> c.cover()
                    else -> null
                }
                if (bmp != null) {
                    Cover.save(this, id, bmp)
                    Statuses.edit(this, id) { it.copy(coverKey = now.name) }
                    h.post { doUi() }
                } else coverTry.remove(id)
            }.start()
        }
        val justRefreshed = System.currentTimeMillis() - Store(this).refreshAt < 16_000
        h.post { postUi(important || justRefreshed) }
    }

    /** Každou minutu: odpočet ve widgetu a kontrola, že chodí data. */
    private val watchdog = object : Runnable {
        override fun run() {
            doUi()
            val now = System.currentTimeMillis()
            for (p in Printers.all(this@MonitorService)) {
                val s = Statuses.get(this@MonitorService, p.id)
                if (s.active && now - s.updated > 2 * 60_000L) connFor(p.id)?.refresh()
            }
            Updater.backgroundCheck(this@MonitorService)
            h.postDelayed(this, 60_000L)
        }
    }

    private fun postUi(force: Boolean) {
        if (force) doUi()
        else if (!uiPending) {
            uiPending = true
            h.postDelayed({ uiPending = false; doUi() }, 5000)
        }
    }

    private fun doUi() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, Notifs.ongoing(this)) } catch (_: Exception) {}
        PrintWidget.render(this)
    }
}

object Notifs {
    private const val CH_MON = "monitor"
    private const val CH_EV = "events"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MON, "Průběh tisku", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(NotificationChannel(CH_EV, "Konec tisku a chyby", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun openPrinter(ctx: Context, id: String): PendingIntent = PendingIntent.getActivity(
        ctx, 1000 + (id.hashCode() and 0xFFF),
        Intent(ctx, PrinterActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun openCamera(ctx: Context, id: String): PendingIntent = PendingIntent.getActivity(
        ctx, 5000 + (id.hashCode() and 0xFFF),
        Intent(ctx, CameraActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Trvalá notifikace – přehled všech tiskáren. */
    fun ongoing(ctx: Context): Notification {
        val all = Printers.all(ctx)
        val b = NotificationCompat.Builder(ctx, CH_MON)
            .setSmallIcon(R.drawable.ic_notif)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (all.isEmpty()) return b.setContentTitle("Hlídač tisku").setContentText("Přidej tiskárnu").build()

        val items = all.map { it to Statuses.get(ctx, it.id) }
        val active = items.filter { it.second.active || it.second.hasError }
        val main = active.firstOrNull() ?: items.first()
        val (p, s) = main
        val prefix = if (all.size > 1) "${p.name}: " else ""
        if (s.hasError) b.setContentTitle(prefix + Fmt.errText(s))
        else b.setContentTitle(prefix + Fmt.title(s))
        val v = Fmt.view(s)
        b.setContentText(when {
            s.active -> (if (s.name.isNotEmpty()) s.name + " · " else "") + "hotovo v ${Fmt.eta(v)}"
            s.error.isNotEmpty() -> s.error
            s.connected -> "Hlídám ${if (all.size > 1) "${all.size} tiskárny" else "tiskárnu"}"
            else -> "Připojuji se…"
        })
        if (s.active) b.setProgress(100, s.pct, false)
        if (all.size > 1) {
            val style = NotificationCompat.InboxStyle()
            for ((pp, ss) in items) {
                val vv = Fmt.view(ss)
                style.addLine("${pp.name}: " + when {
                    ss.hasError -> Fmt.errText(ss)
                    ss.active -> "${ss.pct} % · ${Fmt.remain(vv.remain)} · hotovo ${Fmt.eta(vv)}"
                    !ss.connected -> "offline"
                    else -> Fmt.stateCz(ss.state)
                })
            }
            b.setStyle(style)
        }
        if (p.hasCamera(s)) b.addAction(0, "📷 Kamera", openCamera(ctx, p.id))
        return b.build()
    }

    private fun nid(p: PrinterCfg, k: Int) = 100 + k * 1000 + (p.id.hashCode() and 0x3FF)

    fun openHandy(ctx: Context, p: PrinterCfg): PendingIntent {
        val pm = ctx.packageManager
        val launch = if (p.isBambu) listOf("bbl.intl.bambulab.com", "com.bambulab.handy", "bbl.cn.bambulab.com")
            .firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) } else null
        if (launch == null) return openPrinter(ctx, p.id)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 7000 + (p.id.hashCode() and 0xFFF), launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun error(ctx: Context, p: PrinterCfg, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_EV)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("⚠ ${p.name} hlásí chybu")
            .setContentText(if (p.isBambu) "Kód $text – klepni pro podrobnosti" else text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openHandy(ctx, p))
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid(p, 3), n) } catch (_: Exception) {}
    }

    fun transition(ctx: Context, p: PrinterCfg, from: String, s: Status) {
        val to = s.state
        val active = from == "RUNNING" || from == "PAUSE" || from == "PREPARE"
        val (title, text) = when {
            to == "FINISH" && active -> "✅ ${p.name}: tisk je hotový" to s.name.ifEmpty { "Můžeš sundat výtisk." }
            to == "FAILED" && from != "FAILED" -> "❌ ${p.name}: tisk skončil" to s.name.ifEmpty { "Zkontroluj tiskárnu." }
            to == "PAUSE" && from == "RUNNING" -> "⏸ ${p.name}: tisk se pozastavil" to "Zkontroluj tiskárnu (filament, chyba…)"
            else -> return
        }
        val alarm = Store(ctx).alarmOn && (to == "FINISH" || to == "FAILED")
        val b = NotificationCompat.Builder(ctx, CH_EV)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPrinter(ctx, p.id))
        if (alarm) {
            val stop = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AlarmStopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, "🔕 Ztlumit", stop).setDeleteIntent(stop)
            Alarm.start(ctx)
        }
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid(p, 2), b.build()) } catch (_: Exception) {}
    }
}
