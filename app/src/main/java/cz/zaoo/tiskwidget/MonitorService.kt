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
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocketFactory

/** Drží spojení s tiskárnou (MQTT) a aktualizuje notifikaci + widget. */
class MonitorService : Service() {

    companion object {
        const val NOTIF_ID = 1
        const val ACTION_RECONNECT = "cz.zaoo.tiskwidget.RECONNECT"
        const val ACTION_REFRESH = "cz.zaoo.tiskwidget.REFRESH"

        /** Klepnutí na widget = vyžádat si od tiskárny čerstvá data. */
        fun refreshIntent(ctx: Context): PendingIntent = PendingIntent.getForegroundService(
            ctx, 2, Intent(ctx, MonitorService::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun start(ctx: Context, reconnect: Boolean = false) {
            val i = Intent(ctx, MonitorService::class.java)
            if (reconnect) i.action = ACTION_RECONNECT
            try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        private val KEYS = setOf(
            "gcode_state", "mc_percent", "mc_remaining_time", "layer_num", "total_layer_num",
            "subtask_name", "nozzle_temper", "bed_temper", "print_error", "mc_print_stage",
            "hms", "lights_report",
        )

        const val ACTION_CMD = "cz.zaoo.tiskwidget.CMD"

        /** Příkaz pro tiskárnu: light_on, light_off, pause, resume, stop. */
        fun command(ctx: Context, cmd: String) {
            val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_CMD).putExtra("cmd", cmd)
            try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {}
        }
    }

    private lateinit var store: Store
    @Volatile private var client: MqttClient? = null
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val state = JSONObject()
    private var lastState = ""
    private var lastError = -1L
    private var uiPending = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** Po odemčení telefonu si vyžádá čerstvá data (max. jednou za 20 s). */
    private var lastUnlockRefresh = 0L
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            if (now - lastUnlockRefresh < 20_000) return
            lastUnlockRefresh = now
            doUi()  // hned přepočítat odpočet
            worker.execute {
                val c = client
                if (c != null && c.isConnected) pushAll() else connect(true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Notifs.channels(this)
        try {
            val old = JSONObject(store.stateJson)
            old.keys().forEach { state.put(it, old.get(it)) }
        } catch (_: Exception) {}
        lastState = state.optString("gcode_state", "")
        lastError = state.optLong("print_error", 0L)
        try {
            val f = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(unlockReceiver, f)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifs.ongoing(this, Fmt.info(store))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!store.configured) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CMD) {
            val cmd = intent.getStringExtra("cmd") ?: ""
            worker.execute { sendCommand(cmd) }
            return START_STICKY
        }
        val force = intent?.action == ACTION_RECONNECT
        if (force) {
            synchronized(state) {
                val keys = state.keys().asSequence().toList()
                keys.forEach { state.remove(it) }
            }
            lastState = ""
        }
        if (intent?.action == ACTION_REFRESH) {
            store.refreshAt = System.currentTimeMillis()
            postUi(true)
            worker.execute {
                val c = client
                if (c != null && c.isConnected) pushAll() else connect(true)
            }
            // když do 15 s nic nepřijde, schovat „obnovuji…“
            h.postDelayed({ doUi() }, 16_000)
        } else {
            worker.execute { connect(force) }
        }
        h.removeCallbacks(periodic)
        h.postDelayed(periodic, 5 * 60_000L)
        h.removeCallbacks(watchdog)
        h.postDelayed(watchdog, 60_000L)
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        h.removeCallbacksAndMessages(null)
        val c = client
        client = null
        worker.execute {
            try { c?.disconnectForcibly(500, 1000) } catch (_: Exception) {}
            try { c?.close() } catch (_: Exception) {}
        }
        worker.shutdown()
        store.connected = false
        PrintWidget.render(this)
        super.onDestroy()
    }

    private val retry = Runnable { worker.execute { connect(true) } }

    /** Každých 5 minut si vyžádá kompletní stav (a zároveň hlídá, že spojení žije). */
    private val periodic = object : Runnable {
        override fun run() {
            worker.execute {
                val c = client
                if (c == null) connect(true) else if (c.isConnected) pushAll()
            }
            h.postDelayed(this, 5 * 60_000L)
        }
    }

    /**
     * Každou minutu: překreslí widget (odpočet času) a když od tiskárny
     * 2 minuty nic nepřišlo, vyžádá si data / obnoví spojení.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            doUi()
            val quiet = Fmt.active(Fmt.info(store).state) &&
                System.currentTimeMillis() - store.lastUpdate > 2 * 60_000L
            if (quiet) worker.execute {
                val c = client
                if (c != null && c.isConnected) pushAll() else connect(true)
            }
            h.postDelayed(this, 60_000L)
        }
    }

    private fun connect(force: Boolean) {
        if (!force && client != null) return
        h.removeCallbacks(retry)
        client?.let { old ->
            try { old.disconnectForcibly(500, 1000) } catch (_: Exception) {}
            try { old.close() } catch (_: Exception) {}
        }
        client = null

        val cloud = store.mode == "cloud"
        val uri = if (cloud) "ssl://us.mqtt.bambulab.com:8883" else "ssl://${store.ip}:8883"
        val user = if (cloud) store.username else "bblp"
        val pass = if (cloud) store.token else store.accessCode

        var c: MqttClient? = null
        try {
            c = MqttClient(uri, "tw_" + UUID.randomUUID().toString().take(10), MemoryPersistence())
            val o = MqttConnectOptions().apply {
                userName = user
                password = pass.toCharArray()
                isAutomaticReconnect = true
                isCleanSession = true
                keepAliveInterval = 30
                connectionTimeout = 20
                maxReconnectDelay = 60_000
                socketFactory = if (cloud) SSLSocketFactory.getDefault() else Tls.trustAll().socketFactory
                if (!cloud) isHttpsHostnameVerificationEnabled = false
            }
            c.setCallback(callback)
            client = c
            c.connect(o)
        } catch (e: Exception) {
            client = null
            try { c?.close() } catch (_: Exception) {}
            val code = (e as? MqttException)?.reasonCode ?: -1
            val auth = code == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                code == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
            store.connected = false
            store.error = when {
                auth && cloud -> "Přihlášení vypršelo – otevři appku a přihlas se znovu."
                auth -> "Špatný přístupový kód tiskárny."
                else -> "Nejde se připojit (${e.message ?: code}). Zkusím to znovu za minutu."
            }
            postUi(true)
            h.postDelayed(retry, if (auth) 15 * 60_000L else 60_000L)
        }
    }

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            store.connected = true
            store.error = ""
            postUi(true)
            worker.execute {
                try {
                    client?.subscribe("device/${store.serial}/report", 0)
                    pushAll()
                } catch (e: Exception) {
                    store.error = "Nepodařilo se odebírat data: ${e.message}"
                    postUi(true)
                }
            }
        }

        override fun connectionLost(cause: Throwable?) {
            store.connected = false
            postUi(true)
        }

        override fun messageArrived(topic: String?, message: MqttMessage) {
            onMessage(String(message.payload))
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
    }

    private fun sendCommand(cmd: String) {
        val c = client
        if (c == null || !c.isConnected) {
            store.cmdMsg = "Tiskárna není připojená, příkaz nejde poslat."
            return
        }
        val json = when (cmd) {
            "light_on", "light_off" -> """{"system":{"sequence_id":"0","command":"ledctrl","led_node":"chamber_light","led_mode":"${if (cmd == "light_on") "on" else "off"}","led_on_time":500,"led_off_time":500,"loop_times":0,"interval_time":0}}"""
            "pause", "resume", "stop" -> """{"print":{"sequence_id":"0","command":"$cmd","param":""}}"""
            else -> return
        }
        val label = mapOf("light_on" to "Zapnout světlo", "light_off" to "Vypnout světlo",
            "pause" to "Pauza", "resume" to "Pokračovat", "stop" to "Zastavit")[cmd]
        try {
            c.publish("device/${store.serial}/request", MqttMessage(json.toByteArray()))
            store.cmdMsg = "Odesláno: $label…"
        } catch (e: Exception) {
            store.cmdMsg = "Nepodařilo se odeslat: ${e.message}"
            return
        }
        // Po 12 s ověříme, jestli tiskárna poslechla
        h.postDelayed({
            worker.execute { pushAll() }
            h.postDelayed({
                val i = Fmt.info(store)
                val ok = when (cmd) {
                    "light_on" -> i.lightOn == true
                    "light_off" -> i.lightOn == false
                    "pause" -> i.state == "PAUSE"
                    "resume" -> i.state == "RUNNING" || i.state == "PREPARE"
                    "stop" -> !Fmt.active(i.state)
                    else -> true
                }
                store.cmdMsg = if (ok) "✅ $label – hotovo"
                else "❌ Tiskárna příkaz „$label“ neprovedla. Bambu ho bez zapnutého LAN Only / Developer módu na tiskárně blokuje."
                doUi()
            }, 5000)
        }, 7000)
    }

    private fun pushAll() {
        try {
            val msg = """{"pushing":{"sequence_id":"0","command":"pushall","version":1,"push_target":1}}"""
            client?.publish("device/${store.serial}/request", MqttMessage(msg.toByteArray()))
        } catch (_: Exception) {}
    }

    private fun onMessage(s: String) {
        val p = try { JSONObject(s).optJSONObject("print") } catch (e: Exception) { null } ?: return

        // IP tiskárny (pro kameru) – P1 ji posílá jako číslo v pořadí little-endian
        try {
            val ip = p.optJSONObject("net")?.optJSONArray("info")?.optJSONObject(0)?.optLong("ip", 0L) ?: 0L
            if (ip != 0L) {
                val str = "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
                if (str != store.autoIp) store.autoIp = str
            }
        } catch (_: Exception) {}

        var changed = false
        synchronized(state) {
            p.keys().forEach { k ->
                if (k in KEYS) { state.put(k, p.get(k)); changed = true }
            }
            if (changed) store.stateJson = state.toString()
        }
        if (!changed) return
        store.lastUpdate = System.currentTimeMillis()

        val st = state.optString("gcode_state", "")
        var important = false
        val err = state.optLong("print_error", 0L)
        if (err != 0L && err != lastError) Notifs.error(this, Fmt.errCode(err))
        lastError = err
        if (st.isNotEmpty() && st != lastState) {
            Notifs.transition(this, lastState, st, Fmt.info(store))
            lastState = st
            important = true
        }
        val justRefreshed = System.currentTimeMillis() - store.refreshAt < 16_000
        postUi(important || justRefreshed)
    }

    /** Aktualizace UI max. jednou za 5 s (tiskárna posílá data i několikrát za sekundu). */
    private fun postUi(force: Boolean) {
        if (force) {
            h.post { doUi() }
        } else if (!uiPending) {
            uiPending = true
            h.postDelayed({ uiPending = false; doUi() }, 5000)
        }
    }

    private fun doUi() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, Notifs.ongoing(this, Fmt.info(store)))
        } catch (_: Exception) {}
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
        nm.createNotificationChannel(
            NotificationChannel(CH_EV, "Konec tisku a chyby", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun openCamera(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun ongoing(ctx: Context, i: Fmt.Info): Notification {
        val b = NotificationCompat.Builder(ctx, CH_MON)
            .setSmallIcon(R.drawable.ic_notif)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (Fmt.hasError(i)) {
            b.setContentTitle(Fmt.errText(i))
                .setContentText(if (Fmt.active(i.state)) "${i.pct} % · ${Fmt.stateCz(i.state)}" else Fmt.stateCz(i.state))
            if (Fmt.active(i.state)) b.setProgress(100, i.pct, false)
        } else if (Fmt.active(i.state)) {
            val name = if (i.name.isNotEmpty()) i.name + " · " else ""
            b.setContentTitle("${i.pct} % · zbývá ${Fmt.remain(i.remain)}")
                .setContentText("${name}hotovo v ${Fmt.eta(i)}")
                .setProgress(100, i.pct, false)
        } else {
            b.setContentTitle(Fmt.title(i))
                .setContentText(if (i.error.isNotEmpty()) i.error else if (i.connected) "Hlídám tiskárnu" else "Připojuji se…")
        }
        if (Store(ctx).hasCamera) b.addAction(0, "📷 Kamera", openCamera(ctx))
        return b.build()
    }

    /** Otevře Bambu Handy (kde je popis chyby), jinak naši appku. */
    fun openHandy(ctx: Context): PendingIntent {
        val pm = ctx.packageManager
        val launch = listOf("bbl.intl.bambulab.com", "com.bambulab.handy", "bbl.cn.bambulab.com")
            .firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
            ?: Intent(ctx, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 5, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun error(ctx: Context, code: String) {
        val n = NotificationCompat.Builder(ctx, CH_EV)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("⚠ Tiskárna hlásí chybu")
            .setContentText("Kód $code – klepni pro otevření Bambu Handy")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openHandy(ctx))
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(3, n) } catch (_: Exception) {}
    }

    fun transition(ctx: Context, from: String, to: String, i: Fmt.Info) {
        if (from.isEmpty()) return
        val (title, text) = when {
            to == "FINISH" && Fmt.active(from) -> "✅ Tisk je hotový" to i.name.ifEmpty { "Můžeš sundat výtisk." }
            to == "FAILED" && from != "FAILED" -> "❌ Tisk skončil chybou" to i.name.ifEmpty { "Zkontroluj tiskárnu." }
            to == "PAUSE" && from == "RUNNING" -> "⏸ Tisk se pozastavil" to "Zkontroluj tiskárnu (filament, chyba…)"
            else -> return
        }
        val n = NotificationCompat.Builder(ctx, CH_EV)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx))
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(2, n) } catch (_: Exception) {}
    }
}
