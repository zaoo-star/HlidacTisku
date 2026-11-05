package cz.zaoo.tiskwidget

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Kontrola nových verzí na GitHubu a instalace aktualizace. */
object Updater {
    private const val API = "https://api.github.com/repos/zaoo-star/HlidacTisku/releases/latest"

    data class Rel(val version: String, val url: String, val notes: String)

    @Volatile var available: Rel? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    fun current(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    /** true, když je verze a novější než b (např. 1.1.5 > 1.1.4). */
    fun newer(a: String, b: String): Boolean {
        val x = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Zeptá se GitHubu (ze sítě – volat mimo hlavní vlákno). Vrací novější verzi, nebo null. */
    fun check(ctx: Context): Rel? = try {
        prefs(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "HlidacTisku-Android")
        val body = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
        c.disconnect()
        val j = JSONObject(body)
        val tag = j.optString("tag_name").removePrefix("v")
        val assets = j.optJSONArray("assets")
        var url = ""
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
        }
        val notes = j.optString("body").replace("**", "").lines()
            .firstOrNull { it.startsWith("Změny:") }?.removePrefix("Změny:")?.trim() ?: ""
        val r = if (tag.isNotEmpty() && url.isNotEmpty() && newer(tag, current(ctx))) Rel(tag, url, notes) else null
        available = r
        r
    } catch (_: Exception) { null }

    fun dueForCheck(ctx: Context, everyMs: Long) =
        System.currentTimeMillis() - prefs(ctx).getLong("last", 0L) > everyMs

    /** Ze služby: jednou za 12 h zkontrolovat a dát vědět notifikací (pro každou verzi jen jednou). */
    fun backgroundCheck(ctx: Context) {
        if (!dueForCheck(ctx, 12 * 3600_000L)) return
        Thread {
            val r = check(ctx) ?: return@Thread
            val p = prefs(ctx)
            if (p.getString("notified", "") == r.version) return@Thread
            p.edit().putString("notified", r.version).apply()
            val pi = PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, "events")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("🎉 Nová verze Hlídače tisku ${r.version}")
                .setContentText(r.notes.ifEmpty { "Klepni a aktualizuj." })
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            try { ctx.getSystemService(NotificationManager::class.java).notify(90, n) } catch (_: Exception) {}
        }.start()
    }

    /** Stáhne APK (volat mimo hlavní vlákno). progress 0–100. */
    fun download(ctx: Context, r: Rel, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "HlidacTisku-${r.version}.apk")
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "HlidacTisku-Android")
        val total = c.contentLengthLong
        c.inputStream.use { inp ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pc = (done * 100 / total).toInt()
                        if (pc != last) { last = pc; progress(pc) }
                    }
                }
            }
        }
        c.disconnect()
        return f
    }

    /** Může appka instalovat? Když ne, otevře nastavení „Instalace neznámých aplikací“. */
    fun canInstall(a: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return false
        }
        return true
    }

    fun install(a: Activity, f: File) {
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(i)
    }
}
