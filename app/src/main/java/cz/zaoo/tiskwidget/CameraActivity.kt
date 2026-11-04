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
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Živý obraz z kamery P1S (port 6000, JPEG snímky).
 * Funguje jen na stejné Wi-Fi jako tiskárna.
 */
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
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            text = "Připojuji kameru…"
        }
        root.addView(img, FrameLayout.LayoutParams(-1, -1))
        root.addView(info, FrameLayout.LayoutParams(-1, -1))
        root.setOnClickListener { finish() }
        setContentView(root)

        val store = Store(this)
        val ip = store.camIp()
        val code = store.accessCode
        if (ip.isEmpty() || code.isEmpty()) {
            info.text = "Kamera zatím nezná IP adresu tiskárny.\n\nOtevři appku a v nastavení vyplň „IP tiskárny“ (najdeš ji na displeji tiskárny: Nastavení → WLAN)."
            return
        }
        thread {
            while (running) {
                try {
                    stream(ip, code)
                } catch (e: Exception) {
                    if (!running) break
                    runOnUiThread {
                        info.visibility = TextView.VISIBLE
                        info.text = "Kamera nejde připojit\n(${e.message ?: e.javaClass.simpleName})\n\n" +
                            "• Musíš být na stejné Wi-Fi jako tiskárna (IP $ip)\n" +
                            "• Na tiskárně musí být v Nastavení zapnutý LAN Only / Developer mód, jinak Bambu kameru pro jiné appky blokuje\n\n" +
                            "Zkouším znovu…"
                    }
                    try { Thread.sleep(4000) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    private fun stream(ip: String, code: String) {
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
                val bmp = BitmapFactory.decodeByteArray(jpg, 0, size) ?: continue
                runOnUiThread {
                    img.setImageBitmap(bmp)
                    info.visibility = TextView.GONE
                }
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        try { sock?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
