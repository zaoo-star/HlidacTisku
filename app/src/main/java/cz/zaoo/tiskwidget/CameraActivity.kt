package cz.zaoo.tiskwidget

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Živý obraz z kamery: Bambu P1/A1 (port 6000) nebo Klipper (snímky z webkamery). */
class CameraActivity : Activity() {
    @Volatile private var running = true
    @Volatile private var sock: SSLSocket? = null
    private lateinit var img: ImageView
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48); text = "Připojuji kameru…"
        }
        root.addView(img, FrameLayout.LayoutParams(-1, -1))
        root.addView(info, FrameLayout.LayoutParams(-1, -1))
        root.setOnClickListener { finish() }
        setContentView(root)

        val p = Printers.get(this, intent.getStringExtra("id")) ?: Printers.all(this).firstOrNull()
        if (p == null) { info.text = "Žádná tiskárna."; return }
        val st = Statuses.get(this, p.id)
        thread {
            while (running) {
                try {
                    if (p.isBambu) bambu(p.camHost(st), p.accessCode) else klipper(p, p.type == PrinterCfg.CREALITY)
                } catch (e: Exception) {
                    if (!running) break
                    runOnUiThread {
                        info.visibility = TextView.VISIBLE
                        info.text = "Kamera nejde připojit\n(${e.message ?: e.javaClass.simpleName})\n\n" +
                            "• Telefon musí být na stejné Wi-Fi jako tiskárna\n" +
                            (if (p.isBambu) "• U Bambu musí být na tiskárně zapnutý LAN Only / Developer mód, jinak kameru cizím appkám blokuje\n" else "") +
                            "\nZkouším znovu…"
                    }
                    try { Thread.sleep(4000) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    private fun show(b: android.graphics.Bitmap) = runOnUiThread { img.setImageBitmap(b); info.visibility = TextView.GONE }

    private fun bambu(ip: String, code: String) {
        if (ip.isEmpty()) throw Exception("neznám IP adresu tiskárny – vyplň ji v detailu tiskárny")
        val s = Tls.trustAll().socketFactory.createSocket() as SSLSocket
        sock = s
        try {
            s.connect(InetSocketAddress(ip, 6000), 6000)
            s.soTimeout = 15000
            s.startHandshake()
            val auth = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
            auth.putInt(0x40).putInt(0x3000).putInt(0).putInt(0)
            auth.put("bblp".toByteArray().copyOf(32))
            auth.put(code.toByteArray().copyOf(32))
            s.outputStream.write(auth.array())
            s.outputStream.flush()
            val input = DataInputStream(BufferedInputStream(s.inputStream))
            val hdr = ByteArray(16)
            while (running) {
                input.readFully(hdr)
                val size = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).int
                if (size <= 0 || size > 8_000_000) throw Exception("neplatná data z kamery")
                val jpg = ByteArray(size)
                input.readFully(jpg)
                BitmapFactory.decodeByteArray(jpg, 0, size)?.let { show(it) }
            }
        } finally { try { s.close() } catch (_: Exception) {} }
    }

    /** Klipper: najde adresu snímku webkamery a opakovaně ji načítá. */
    private fun klipper(p: PrinterCfg, creality: Boolean) {
        val hostOnly = "http://" + Http.hostOnly(p.host)
        val urls = mutableListOf<String>()
        if (!creality) try {
            val r = Http.request(p, "GET", "/server/webcams/list")
            val cams = JSONObject(r.body).optJSONObject("result")?.optJSONArray("webcams")
            if (cams != null) for (i in 0 until cams.length()) {
                val u = cams.optJSONObject(i)?.optString("snapshot_url", "") ?: ""
                if (u.isNotEmpty()) urls += if (u.startsWith("http")) u else hostOnly + (if (u.startsWith("/")) u else "/$u")
            }
        } catch (_: Exception) {}
        urls += "$hostOnly:8080/?action=snapshot"   // Creality K1 / Hi
        urls += "$hostOnly/webcam/?action=snapshot"
        var url: String? = null
        for (u in urls) {
            if (Http.image(p, u, 1600) != null) { url = u; break }
        }
        if (url == null) throw Exception(if (creality) "kamera nenalezena (novější Creality K2 posílají video jinak a zatím je neumím)" else "webkamera nenalezena")
        while (running) {
            val b = Http.image(p, url, 1600) ?: throw Exception("kamera přestala odpovídat")
            show(b)
            Thread.sleep(400)
        }
    }

    override fun onDestroy() {
        running = false
        try { sock?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
