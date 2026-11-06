package cz.zaoo.tiskwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** Barevná témata widgetu. */
object Themes {
    /** style: hud, minimal, terminal, fantasy, parchment, steam, pixel, cyber */
    data class T(val key: String, val label: String, val a: Int, val b: Int, val bgTop: Int, val bgBot: Int, val info: Int, val style: String) {
        val hud get() = style == "hud"
    }

    private fun c(v: Long) = v.toInt()

    val ALL = listOf(
        T("neon", "Neon", c(0xFF00E5FF), c(0xFF00FF9C), c(0xFF0E1624), c(0xFF04070C), c(0xFF8FB3C9), "hud"),
        T("bambu", "Zelená", c(0xFF00C853), c(0xFFB2FF59), c(0xFF0E1A12), c(0xFF040805), c(0xFF9FC4A8), "hud"),
        T("synth", "Synthwave", c(0xFFFF2BD6), c(0xFF7B5CFF), c(0xFF1A0F2A), c(0xFF07030D), c(0xFFC7AEE8), "hud"),
        T("space", "Vesmírná loď", c(0xFF4FC3F7), c(0xFFFF9800), c(0xFF07111F), c(0xFF02060C), c(0xFF8FB8D9), "hud"),
        T("lava", "Láva", c(0xFFFF6A00), c(0xFFFF0055), c(0xFF22100A), c(0xFF0A0403), c(0xFFE0A493), "hud"),
        T("terminal", "Retro terminál (pustina)", c(0xFF41FF6A), c(0xFFB4FFC4), c(0xFF04160A), c(0xFF000502), c(0xFF39C45B), "terminal"),
        T("terminal_amber", "Terminál jantarový", c(0xFFFFB641), c(0xFFFFE1A8), c(0xFF181002), c(0xFF050300), c(0xFFC98B2E), "terminal"),
        T("cyber", "Kyberpunk", c(0xFFFCEE0A), c(0xFF00F0FF), c(0xFF0B0A14), c(0xFF16001F), c(0xFFFF5C93), "cyber"),
        T("fantasy", "Temné fantasy", c(0xFFB3261E), c(0xFFE0574D), c(0xFF1C1D21), c(0xFF0B0B0D), c(0xFFA9ADB3), "fantasy"),
        T("parchment", "Pergamen", c(0xFF8B1E1E), c(0xFF5A3E1B), c(0xFFEBDCB0), c(0xFFCDB27A), c(0xFF5A4326), "parchment"),
        T("steam", "Steampunk", c(0xFFC8963E), c(0xFFF3D27A), c(0xFF2C1D12), c(0xFF120A05), c(0xFFCDAE7E), "steam"),
        T("pixel", "8-bit retro hra", c(0xFFFF004D), c(0xFFFFA300), c(0xFF1D2B53), c(0xFF10183A), c(0xFFC2C3C7), "pixel"),
        T("matrix", "Matrix", c(0xFF00FF41), c(0xFF00A82B), c(0xFF041208), c(0xFF010402), c(0xFF7FD68F), "hud"),
        T("amber", "Retro jantar", c(0xFFFFB000), c(0xFFFFE066), c(0xFF1C1408), c(0xFF090602), c(0xFFD9B97A), "hud"),
        T("ice", "Minimal", c(0xFFFFFFFF), c(0xFF9CC4FF), c(0xFF20262C), c(0xFF161A1E), c(0xFFB0BAC4), "minimal"),
    )

    fun get(k: String) = ALL.firstOrNull { it.key == k } ?: ALL[0]
}

/** Nastavení widgetů – každý widget má svoje (klíč w<id>_…), jinak platí výchozí. */
object WidgetOpts {
    data class Opt(val key: String, val label: String, val def: Boolean)

    val ALL = listOf(
        Opt("time", "Zbývající čas", true),
        Opt("pct", "Procenta", true),
        Opt("progress", "Ukazatel průběhu", true),
        Opt("eta", "Kdy bude hotovo", true),
        Opt("name", "Název modelu", true),
        Opt("thumb", "Náhled modelu (obdélník)", true),
        Opt("fil", "Aktuální filament", false),
        Opt("temps", "Teploty", false),
        Opt("layers", "Vrstvy", false),
        Opt("updated", "Čas aktualizace", false),
        Opt("cam", "Tlačítko kamery", true),
        Opt("pname", "Název tiskárny", false),
        Opt("light", "Průhlednější pozadí", false),
    )

    val SHAPES = listOf(
        Triple("neon_auto", "✨ Futuristický – automaticky", R.layout.widget_neon_panel),
        Triple("neon_panel", "✨ Futuristický panel", R.layout.widget_neon_panel),
        Triple("neon_ring", "✨ Futuristický kruh", R.layout.widget_neon_ring),
        Triple("auto", "Klasický – automaticky", R.layout.widget),
        Triple("rect", "Obdélník", R.layout.widget),
        Triple("strip", "Tenký pruh", R.layout.widget_strip),
        Triple("square", "Zaoblený čtverec", R.layout.widget_square),
        Triple("circle", "Kruh", R.layout.widget_circle),
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("widget", Context.MODE_PRIVATE)
    private fun k(wid: Int, key: String) = if (wid >= 0) "w${wid}_$key" else key

    fun get(ctx: Context, wid: Int, key: String): Boolean {
        val p = prefs(ctx)
        val def = ALL.firstOrNull { it.key == key }?.def ?: true
        return if (wid >= 0 && p.contains(k(wid, key))) p.getBoolean(k(wid, key), def) else p.getBoolean(key, def)
    }

    fun set(ctx: Context, wid: Int, key: String, v: Boolean) = prefs(ctx).edit().putBoolean(k(wid, key), v).apply()

    private fun str(ctx: Context, wid: Int, key: String, def: String): String {
        val p = prefs(ctx)
        return (if (wid >= 0) p.getString(k(wid, key), null) else null) ?: p.getString(key, def) ?: def
    }

    fun shape(ctx: Context, wid: Int) = str(ctx, wid, "shape", "neon_auto")
    fun setShape(ctx: Context, wid: Int, v: String) = prefs(ctx).edit().putString(k(wid, "shape"), v).apply()
    fun theme(ctx: Context, wid: Int) = str(ctx, wid, "theme", "neon")
    fun setTheme(ctx: Context, wid: Int, v: String) = prefs(ctx).edit().putString(k(wid, "theme"), v).apply()
    fun printer(ctx: Context, wid: Int): String = if (wid >= 0) prefs(ctx).getString("w${wid}_printer", "") ?: "" else ""
    fun setPrinter(ctx: Context, wid: Int, id: String) = prefs(ctx).edit().putString("w${wid}_printer", id).apply()
}

class PrintWidget : AppWidgetProvider() {

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        render(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context)
        if (Printers.all(context).isNotEmpty()) MonitorService.start(context)
    }

    companion object {
        fun render(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PrintWidget::class.java))
            if (ids.isEmpty()) return
            val all = Printers.all(ctx)
            for (id in ids) {
                val o = mgr.getAppWidgetOptions(id)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val hgt = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                val p = all.firstOrNull { it.id == WidgetOpts.printer(ctx, id) } ?: all.firstOrNull()
                try {
                    mgr.updateAppWidget(id, build(ctx, p, { WidgetOpts.get(ctx, id, it) },
                        WidgetOpts.shape(ctx, id), WidgetOpts.theme(ctx, id), w, hgt))
                } catch (_: Exception) {}
            }
        }

        /** „Automaticky“ → tvar podle velikosti widgetu na ploše. */
        fun resolve(shape: String, wDp: Int, hDp: Int): String {
            val known = wDp > 0 && hDp > 0
            val ratio = if (known) wDp.toFloat() / hDp else 3f
            return when (shape) {
                "auto" -> when { ratio < 1.45f -> "circle"; known && hDp < 75 -> "strip"; else -> "rect" }
                "neon_auto" -> if (ratio < 1.45f) "neon_ring" else "neon_panel"
                else -> shape
            }
        }

        /** Sestaví vzhled jednoho widgetu (i pro náhled v nastavení). */
        fun build(ctx: Context, p: PrinterCfg?, opt: (String) -> Boolean, shapeIn: String, themeKey: String,
                  wDp: Int, hDp: Int): RemoteViews {
            val shape = resolve(shapeIn, wDp, hDp)
            val theme = Themes.get(themeKey)
            if (shape.startsWith("neon")) return buildNeon(ctx, p, opt, shape, theme, wDp, hDp)

            val s = if (p != null) Statuses.get(ctx, p.id) else Status()
            val vw = Fmt.view(s)
            val layout = WidgetOpts.SHAPES.firstOrNull { it.first == shape }?.third ?: R.layout.widget
            val v = RemoteViews(ctx.packageName, layout)
            val round = shape == "square" || shape == "circle"
            val off = p != null && Fmt.offline(s)
            val active = s.active && !off
            fun vis(id: Int, show: Boolean) = v.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)

            val light = opt("light")
            when (shape) {
                "circle" -> {}
                "strip" -> v.setInt(R.id.w_root, "setBackgroundResource", if (light) R.drawable.pill_bg_light else R.drawable.pill_bg)
                "square" -> v.setInt(R.id.w_root, "setBackgroundResource", if (light) R.drawable.square_bg_light else R.drawable.square_bg)
                else -> v.setInt(R.id.w_root, "setBackgroundResource", if (light) R.drawable.widget_bg_light else R.drawable.widget_bg)
            }
            if (!round && Build.VERSION.SDK_INT >= 31)
                v.setColorStateList(R.id.w_progress, "setProgressTintList", ColorStateList.valueOf(theme.a))

            // ---- velikosti podle widgetu (dp) ----
            val W = if (wDp > 0) wDp.toFloat() else if (round) 150f else 320f
            val H = if (hDp > 0) hDp.toFloat() else when (shape) { "strip" -> 48f; "rect" -> 84f; else -> 150f }
            val dmin = minOf(W, H)
            val k = if (shape == "strip") (H / 44f).coerceIn(0.8f, 1.6f) else (H / 72f).coerceIn(0.8f, 1.7f)
            var szTime = when { round -> maxOf(13f, dmin * 0.15f); shape == "strip" -> 16f * k; else -> 22f * k }
            val szPct = when { round -> maxOf(10f, dmin * 0.095f); shape == "strip" -> 13f * k; else -> 15f * k }
            val szInfo = when { round -> maxOf(9f, dmin * 0.072f); shape == "strip" -> 12f * k; else -> minOf(12f * k, 17f) }
            val szCam = when { round -> maxOf(11f, dmin * 0.085f); shape == "strip" -> 14f * k; else -> 15f * k }
            fun ts(id: Int, sz: Float) = v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, sz)

            if (p == null) {
                v.setTextViewText(R.id.w_time, if (round) "Přidej" else "Hlídač tisku")
                v.setTextViewText(R.id.w_info, if (round) "tiskárnu" else "Klepni a přidej tiskárnu")
                vis(R.id.w_time, true); vis(R.id.w_info, true)
                v.setTextViewText(R.id.w_pct, ""); vis(R.id.w_cam, false)
                ts(R.id.w_time, szTime); ts(R.id.w_info, szInfo)
                if (round) v.setImageViewBitmap(R.id.w_ring, ring(0, false, shape == "circle", light, theme))
                else { vis(R.id.w_progress, false); vis(R.id.w_thumb, false) }
                v.setOnClickPendingIntent(R.id.w_root, Notifs.openApp(ctx))
                return v
            }

            val main = when {
                off -> "Offline"
                active -> if (round) Fmt.remainShort(vw.remain) else Fmt.remain(vw.remain)
                s.state == "FINISH" -> if (round) "Hotovo" else "Hotovo ✅"
                s.state == "FAILED" -> "Skončil ❌"
                s.state == "IDLE" -> "Netiskne"
                s.state.isEmpty() -> "Připojuji…"
                else -> "—"
            }
            val showTime = opt("time") || !active
            val timeText = if (showTime) main else "${s.pct} %"
            val pctText = when { s.state == "PAUSE" -> "⏸ ${s.pct} %"; active -> "${s.pct} %"; else -> "" }
            var pctShown = if (opt("pct") && showTime) pctText else ""
            val cam = opt("cam") && p.hasCamera(s)
            val thumb = if (shape == "rect" && opt("thumb") && (active || s.state == "FINISH") && Cover.valid(s))
                Cover.load(ctx, p.id, 144) else null

            val camW = if (cam && !round) szCam * 1.3f + 18f else 0f
            val contentW = when (shape) {
                "rect" -> W - 20f - (if (thumb != null) 58f else 0f)
                "strip" -> W - 24f
                "circle" -> dmin * 0.58f
                else -> (dmin - 24f) * 0.62f
            }
            fun rowW() = Fit.width(timeText, szTime, true) +
                (if (pctShown.isNotEmpty() && !round) Fit.width(pctShown, szPct) + 8f else 0f) + camW
            val rowLimit = if (shape == "strip") contentW * 0.62f else contentW
            while (rowW() > rowLimit && szTime > 11f) szTime *= 0.92f
            if (rowW() > rowLimit) pctShown = ""
            if (round) while (Fit.width(timeText, szTime, true) > contentW && szTime > 11f) szTime *= 0.92f

            v.setTextViewText(R.id.w_time, timeText)
            vis(R.id.w_time, true)
            v.setTextViewText(R.id.w_pct, pctShown)
            ts(R.id.w_time, szTime); ts(R.id.w_pct, szPct); ts(R.id.w_info, szInfo); ts(R.id.w_cam, szCam)

            val barPct = when { off -> 0; s.state == "FINISH" -> 100; active -> s.pct; else -> 0 }
            val showBar = opt("progress") && (active || (s.state == "FINISH" && !off))
            if (round) v.setImageViewBitmap(R.id.w_ring, ring(barPct, opt("progress"), shape == "circle", light, theme))
            else { vis(R.id.w_progress, showBar); if (showBar) v.setProgressBar(R.id.w_progress, 100, barPct, false) }

            // údaje podle důležitosti
            val parts = mutableListOf<CharSequence>()
            val refreshing = Store(ctx).refreshAt > s.updated && System.currentTimeMillis() - Store(ctx).refreshAt < 15_000
            if (opt("pname")) parts += p.name
            if (refreshing) parts += "⟳ obnovuji…"
            if (off) parts += if (round) Fmt.lastSeen(s).removePrefix("naposledy ") else Fmt.lastSeen(s).ifEmpty { "tiskárna neodpovídá" }
            if (s.hasError && !off) parts += if (round) "⚠ chyba" else Fmt.errText(s)
            if (opt("eta") && active && s.state != "PAUSE") parts += if (round) Fmt.eta(vw) else "hotovo v ${Fmt.eta(vw)}"
            if (opt("fil") && active && p.isBambu) Ams.current(ctx, p.id)?.let { t ->
                val sp = android.text.SpannableString("● ${t.type}")
                sp.setSpan(android.text.style.ForegroundColorSpan(t.color), 0, 1, 0)
                parts += sp
            }
            if (opt("name") && !round && s.name.isNotEmpty() && (active || s.state == "FINISH")) parts += s.name
            if (opt("temps") && s.state.isNotEmpty() && !off) parts += Fmt.temps(s)
            if (opt("layers") && active && s.total > 0) parts += "vrstva ${s.layer}/${s.total}"
            if (opt("updated") && s.updated > 0) parts += Fmt.clock(s.updated)
            if (off && s.error.isNotEmpty() && !round && shape != "strip") parts += s.error

            val lineH = szInfo * 1.35f
            val maxLines = when (shape) {
                "strip" -> 1
                "rect" -> ((H - 16f - szTime * 1.3f - (if (showBar) 9f else 0f) - 3f) / lineH).toInt().coerceIn(1, 3)
                else -> if (dmin >= 190f) 3 else 2
            }
            val infoW = if (shape == "strip") contentW - rowW() - 14f else contentW
            val sep = if (round) "  " else " · "
            val lines = if (infoW > 30f) Fit.pack(parts, szInfo, infoW, maxLines, sep) else emptyList()
            v.setInt(R.id.w_info, "setMaxLines", maxOf(1, lines.size))
            v.setTextViewText(R.id.w_info, Fit.join(lines, sep))
            vis(R.id.w_info, lines.isNotEmpty() || shape == "strip")

            vis(R.id.w_cam, cam)
            if (shape == "rect") {
                if (thumb != null) v.setImageViewBitmap(R.id.w_thumb, thumb)
                vis(R.id.w_thumb, thumb != null)
            }
            v.setOnClickPendingIntent(R.id.w_root, MonitorService.refreshIntent(ctx))
            v.setOnClickPendingIntent(R.id.w_cam, Notifs.openCamera(ctx, p.id))
            return v
        }

        /** Futuristický vzhled – data se poskládají sem, kreslí je Neon.kt. */
        private fun buildNeon(ctx: Context, p: PrinterCfg?, opt: (String) -> Boolean, shape: String, theme: Themes.T,
                              wDp: Int, hDp: Int): RemoteViews {
            val panel = shape == "neon_panel"
            val v = RemoteViews(ctx.packageName, if (panel) R.layout.widget_neon_panel else R.layout.widget_neon_ring)
            val s = if (p != null) Statuses.get(ctx, p.id) else Status()
            val vw = Fmt.view(s)
            val off = p != null && Fmt.offline(s)
            val active = s.active && !off

            var a = theme.a; var b = theme.b
            val label: String
            val time: String
            var pct = s.pct
            when {
                p == null -> { a = Neon.GREY; b = Neon.GREY; label = "HLÍDAČ TISKU"; time = "PŘIDEJ"; pct = 0 }
                off -> { a = Neon.GREY; b = Neon.GREY; label = "TISKÁRNA"; time = "OFFLINE"; pct = 0 }
                s.hasError -> { a = Neon.RED; b = Neon.RED2
                    label = if (s.printError != 0L) "CHYBA ${Fmt.errCode(s.printError)}" else "UPOZORNĚNÍ"
                    time = if (active) Fmt.hm(vw.remain) else "!!" }
                s.state == "PAUSE" -> { a = Neon.AMBER; b = Neon.AMBER2; label = "POZASTAVENO"; time = Fmt.hm(vw.remain) }
                s.state == "PREPARE" -> { label = "PŘÍPRAVA"; time = Fmt.hm(vw.remain) }
                active -> { label = "ZBÝVÁ"; time = Fmt.hm(vw.remain) }
                s.state == "FINISH" -> { label = "TISK"; time = "HOTOVO"; pct = 100 }
                s.state == "FAILED" -> { a = Neon.RED; b = Neon.RED2; label = "TISK"; time = "KONEC" }
                s.state == "IDLE" -> { label = "TISKÁRNA"; time = "NETISKNE"; pct = 0 }
                else -> { label = "PŘIPOJUJI"; time = "--:--"; pct = 0 }
            }
            val fullLabel = if (p != null && opt("pname")) "${p.name.uppercase()} · $label" else label

            val parts = mutableListOf<Neon.Part>()
            if (p != null) {
                val st = Store(ctx)
                val refreshing = st.refreshAt > s.updated && System.currentTimeMillis() - st.refreshAt < 15_000
                if (refreshing) parts += Neon.Part("OBNOVUJI…")
                if (off) parts += Neon.Part(Fmt.lastSeen(s).uppercase().ifEmpty { "NEODPOVÍDÁ" })
                if (s.alert.isNotEmpty()) parts += Neon.Part(s.alert.uppercase())
                if (opt("eta") && active && s.state != "PAUSE") parts += Neon.Part("HOTOVO ${Fmt.eta(vw)}")
                if (opt("fil") && active && p.isBambu) Ams.current(ctx, p.id)?.let { parts += Neon.Part(it.type, it.color) }
                if (opt("name") && panel && s.name.isNotEmpty() && (active || s.state == "FINISH")) parts += Neon.Part(s.name)
                if (opt("temps") && s.state.isNotEmpty() && !off) parts += Neon.Part("${s.nozzle.toInt()}°/${s.bed.toInt()}°")
                if (opt("layers") && active && s.total > 0) parts += Neon.Part("V ${s.layer}/${s.total}")
                if (opt("updated") && s.updated > 0) parts += Neon.Part("AKT. ${Fmt.clock(s.updated)}")
            } else parts += Neon.Part("KLEPNI A PŘIDEJ TISKÁRNU")

            val cam = p != null && opt("cam") && p.hasCamera(s)
            val data = Neon.Data(
                pct = pct,
                showRing = opt("progress") && p != null && (active || (s.state == "FINISH" && !off) || !panel),
                time = if (opt("time") || !active) time else "$pct %",
                label = fullLabel, parts = parts, cam = cam, a = a, b = b, light = opt("light"), theme = theme,
            )
            val dens = ctx.resources.displayMetrics.density
            val wd = if (wDp > 0) wDp else if (panel) 320 else 170
            val hd = if (hDp > 0) hDp else if (panel) 110 else 170
            val kk = minOf(dens, 760f / maxOf(wd, hd))
            val wp = (wd * kk).toInt().coerceAtLeast(60)
            val hp = (hd * kk).toInt().coerceAtLeast(40)
            v.setImageViewBitmap(R.id.w_img, if (panel) Neon.panel(ctx, wp, hp, data) else Neon.ring(ctx, wp, hp, data))

            val tap = if (p != null) MonitorService.refreshIntent(ctx) else Notifs.openApp(ctx)
            v.setOnClickPendingIntent(R.id.w_root, tap)
            v.setOnClickPendingIntent(R.id.w_cam, if (cam && p != null) Notifs.openCamera(ctx, p.id) else tap)
            return v
        }

        /** Kruhový průběh (klasický kruh/čtverec) – obrázek se vždy vejde. */
        private fun ring(pct: Int, showRing: Boolean, withCircle: Boolean, light: Boolean, t: Themes.T): Bitmap {
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
                    p.shader = SweepGradient(size / 2f, size / 2f, intArrayOf(t.a, t.b, t.a), null).also {
                        val m = android.graphics.Matrix(); m.setRotate(-90f, size / 2f, size / 2f); it.setLocalMatrix(m)
                    }
                    c.drawArc(r, -90f, 360f * pct.coerceAtMost(100) / 100f, false, p)
                    p.shader = null
                }
            }
            return bmp
        }
    }
}
