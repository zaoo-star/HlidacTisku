package cz.zaoo.tiskwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Futuristický (HUD / neon) vzhled widgetu – celý se kreslí jako obrázek. */
object Neon {
    data class Data(
        val pct: Int,
        val showRing: Boolean,
        val time: String,
        val label: String,
        val parts: List<Part>,   // údaje seřazené podle důležitosti
        val cam: Boolean,
        val a: Int,          // barva začátku průběhu
        val b: Int,          // barva konce průběhu
        val light: Boolean,
        val theme: Themes.T = Themes.ALL[0],
    )

    /** Jeden údaj, volitelně s barevnou tečkou (filament). */
    data class Part(val text: String, val dot: Int? = null)

    val CYAN = Color.rgb(0, 229, 255)
    val GREEN = Color.rgb(0, 255, 156)
    val AMBER = Color.rgb(255, 179, 0)
    val AMBER2 = Color.rgb(255, 224, 130)
    val RED = Color.rgb(255, 61, 90)
    val RED2 = Color.rgb(255, 138, 128)
    val GREY = Color.rgb(96, 125, 139)
    private val TRACK = Color.rgb(30, 42, 54)
    private val INFO = Color.rgb(143, 179, 201)

    private var orb: Typeface? = null
    private var orbMid: Typeface? = null
    private var raj: Typeface? = null
    private var vt: Typeface? = null
    private var cin: Typeface? = null
    private var cinBold: Typeface? = null
    private var px: Typeface? = null

    private fun fonts(ctx: Context) {
        if (orb != null) return
        val am = ctx.assets
        fun v(file: String, w: Int) = try { Typeface.Builder(am, file).setFontVariationSettings("'wght' $w").build() }
                                      catch (_: Exception) { Typeface.DEFAULT_BOLD }
        fun f(file: String) = try { Typeface.createFromAsset(am, file) } catch (_: Exception) { Typeface.DEFAULT }
        orb = v("fonts/orbitron.ttf", 800)
        orbMid = v("fonts/orbitron.ttf", 600)
        raj = f("fonts/rajdhani.ttf")
        vt = f("fonts/vt323.ttf")
        cin = v("fonts/cinzel.ttf", 500)
        cinBold = v("fonts/cinzel.ttf", 800)
        px = f("fonts/pixel.ttf")
    }

    /** Jak vypadá text a kruh v daném stylu (písma, barvy, efekty). */
    private class Look(
        val time: Typeface?, val timeK: Float,
        val label: Typeface?, val labelK: Float, val spacing: Float,
        val info: Typeface?, val infoK: Float,
        val text: Int, val labelCol: Int,
        val glow: Boolean, val shadow: Boolean, val glitch: Boolean, val aa: Boolean,
        val track: Int, val prefix: String = "", val suffix: String = "",
    )

    private fun look(d: Data): Look {
        val a = d.a
        return when (d.theme.style) {
            "terminal" -> Look(vt, 1.45f, vt, 1.45f, 0.12f, vt, 1.45f, lerp(a, Color.WHITE, 0.35f), alpha(a, 210),
                glow = true, shadow = false, glitch = false, aa = true, track = alpha(a, 45), prefix = "> ", suffix = "_")
            "fantasy" -> Look(cinBold, 0.95f, cin, 1.0f, 0.18f, cin, 1.0f, 0xFFE9E5DC.toInt(), lerp(a, Color.WHITE, 0.15f),
                glow = false, shadow = true, glitch = false, aa = true, track = 0xFF2B2B30.toInt())
            "parchment" -> Look(cinBold, 0.95f, cin, 1.0f, 0.18f, cin, 1.0f, 0xFF3B2A14.toInt(), a,
                glow = false, shadow = false, glitch = false, aa = true, track = 0x553B2A14)
            "steam" -> Look(cinBold, 0.95f, cin, 1.0f, 0.18f, cin, 1.0f, 0xFFF4E6C8.toInt(), lerp(a, Color.WHITE, 0.2f),
                glow = false, shadow = true, glitch = false, aa = true, track = 0xFF3A2918.toInt())
            "pixel" -> Look(px, 0.6f, px, 0.72f, 0f, px, 0.7f, Color.WHITE, a,
                glow = false, shadow = true, glitch = false, aa = false, track = 0xFF2F3B66.toInt())
            "cyber" -> Look(orb, 1f, raj, 1.05f, 0.3f, raj, 1.05f, Color.WHITE, a,
                glow = true, shadow = false, glitch = true, aa = true, track = 0xFF24232E.toInt())
            else -> Look(orb, 1f, raj, 1f, 0.35f, raj, 1f, Color.WHITE, alpha(a, 190),
                glow = true, shadow = false, glitch = false, aa = true, track = TRACK)
        }
    }

    /** Text s efektem stylu: záře, tvrdý stín (pixel/fantasy) nebo glitch (kyberpunk). */
    private fun fx(c: Canvas, s: String, x: Float, y: Float, p: Paint, L: Look, accent: Int, big: Boolean) {
        if (L.glitch && big) {
            val o = p.textSize * 0.045f
            val col = p.color
            p.clearShadowLayer()
            p.color = alpha(0xFF00F0FF.toInt(), 170); drawMid(c, s, x - o, y, p)
            p.color = alpha(0xFFFF2A6D.toInt(), 170); drawMid(c, s, x + o, y + o * 0.3f, p)
            p.color = col
        }
        if (L.shadow) {
            val o = maxOf(2f, p.textSize * (if (L.aa) 0.035f else 0.08f))
            val col = p.color
            p.clearShadowLayer()
            p.color = Color.argb(200, 0, 0, 0); drawMid(c, s, x + o, y + o, p)
            p.color = col
        }
        if (L.glow && big) p.setShadowLayer(p.textSize * 0.15f, 0f, 0f, alpha(accent, 220))
        drawMid(c, s, x, y, p)
        p.clearShadowLayer()
    }

    private fun lerp(c1: Int, c2: Int, t: Float): Int = Color.rgb(
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt(),
    )

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun textPaint(tf: Typeface?, size: Float, color: Int, glow: Int? = null, glowR: Float = 0f, aa: Boolean = true) =
        Paint(if (aa) Paint.ANTI_ALIAS_FLAG else 0).apply {
            typeface = tf
            textSize = size
            this.color = color
            if (glow != null) setShadowLayer(glowR, 0f, 0f, glow)
        }

    /** Text svisle na střed kolem y. */
    private fun drawMid(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, p)
    }

    private fun fit(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private const val SEP = "  ·  "

    private fun partW(pt: Part, p: Paint): Float =
        p.measureText(pt.text) + if (pt.dot != null) p.textSize * 0.55f else 0f

    /** Rozdělí údaje do max. maxLines řádků podle šířky; co se nevejde, vynechá. */
    private fun pack(parts: List<Part>, p: Paint, maxW: Float, maxLines: Int): List<List<Part>> {
        val lines = mutableListOf<MutableList<Part>>()
        val widths = mutableListOf<Float>()
        val sw = p.measureText(SEP)
        for (raw in parts) {
            var pt = raw
            var w = partW(pt, p)
            if (w > maxW) {
                if (lines.size >= maxLines) continue
                pt = pt.copy(text = fit(pt.text, p, maxW - (w - p.measureText(pt.text))))
                w = partW(pt, p)
            }
            var placed = false
            for (li in lines.indices) {
                if (widths[li] + sw + w <= maxW) { lines[li].add(pt); widths[li] += sw + w; placed = true; break }
            }
            if (!placed && lines.size < maxLines) { lines.add(mutableListOf(pt)); widths.add(w) }
        }
        return lines
    }

    private fun lineW(line: List<Part>, p: Paint) =
        line.sumOf { partW(it, p).toDouble() }.toFloat() + p.measureText(SEP) * (line.size - 1)

    /** Nakreslí řádek údajů; x = levý okraj (nebo střed, když center). */
    private fun drawLine(c: Canvas, line: List<Part>, x0: Float, y: Float, p: Paint, center: Boolean) {
        var x = if (center) x0 - lineW(line, p) / 2 else x0
        val align = p.textAlign
        p.textAlign = Paint.Align.LEFT
        line.forEachIndexed { idx, pt ->
            if (idx > 0) { drawMid(c, SEP, x, y, p); x += p.measureText(SEP) }
            if (pt.dot != null) {
                val r = p.textSize * 0.2f
                val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pt.dot; setShadowLayer(r, 0f, 0f, alpha(pt.dot, 180)) }
                c.drawCircle(x + r, y, r, dp)
                x += p.textSize * 0.55f
            }
            drawMid(c, pt.text, x, y, p)
            x += p.measureText(pt.text)
        }
        p.textAlign = align
    }

    private fun segRing(c: Canvas, cx: Float, cy: Float, r: Float, w: Float, pct: Int, segs: Int, a: Int, b: Int,
                        track: Int = TRACK, glow: Boolean = true) {
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        val step = 360f / segs
        val gap = step * 0.16f
        val filled = (segs * pct / 100f).roundToInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w }
        for (i in 0 until segs) {
            val start = -90f + i * step + gap
            val sweep = step - 2 * gap
            if (i < filled) {
                val col = lerp(a, b, i / (segs - 1f).coerceAtLeast(1f))
                p.color = col
                if (glow) p.setShadowLayer(w * 0.9f, 0f, 0f, alpha(col, 200))
            } else {
                p.color = track
                p.clearShadowLayer()
            }
            c.drawArc(box, start, sweep, false, p)
        }
    }

    /** Průběh podle stylu: segmenty (HUD, terminál, kyber), plný oblouk s ornamenty (fantasy…), kostičky (pixel). */
    private fun styledRing(c: Canvas, cx: Float, cy: Float, r: Float, w: Float, d: Data, L: Look, segs: Int) {
        when (d.theme.style) {
            "fantasy", "parchment", "steam" -> {
                val edge = when (d.theme.style) { "parchment" -> 0xAA5A3E1B.toInt(); "steam" -> lerp(d.a, Color.BLACK, 0.3f); else -> 0xCCBFC3C7.toInt() }
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1.5f, w * 0.12f); color = edge }
                if (d.theme.style == "steam") {   // ozubení
                    val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lerp(d.a, Color.BLACK, 0.25f); strokeWidth = w * 0.55f }
                    for (k in 0 until 24) {
                        val ang = Math.toRadians(k * 15.0)
                        val r1 = r + w * 0.5f; val r2 = r + w * 0.95f
                        c.drawLine(cx + r1 * cos(ang).toFloat(), cy + r1 * sin(ang).toFloat(), cx + r2 * cos(ang).toFloat(), cy + r2 * sin(ang).toFloat(), tooth)
                    }
                }
                val tr = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; color = L.track }
                c.drawCircle(cx, cy, r, tr)
                if (d.pct > 0) {
                    val pr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = w * 0.8f; strokeCap = Paint.Cap.BUTT
                        shader = android.graphics.SweepGradient(cx, cy, intArrayOf(d.a, d.b, d.a), null).also {
                            val m = android.graphics.Matrix(); m.setRotate(-90f, cx, cy); it.setLocalMatrix(m)
                        }
                    }
                    c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * d.pct.coerceAtMost(100) / 100f, false, pr)
                }
                c.drawCircle(cx, cy, r + w / 2, line)
                c.drawCircle(cx, cy, r - w / 2, line)
                // kosočtverce na 12, 3, 6 a 9 hodinách
                val dia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = edge; style = Paint.Style.FILL }
                for (k in 0 until 4) {
                    val ang = Math.toRadians(k * 90.0 - 90)
                    val x = cx + (r + w * 0.9f) * cos(ang).toFloat(); val y = cy + (r + w * 0.9f) * sin(ang).toFloat()
                    val s2 = w * 0.45f
                    val path = android.graphics.Path().apply { moveTo(x, y - s2); lineTo(x + s2 * 0.7f, y); lineTo(x, y + s2); lineTo(x - s2 * 0.7f, y); close() }
                    c.drawPath(path, dia)
                }
            }
            "pixel" -> {
                val n = 20
                val box = w * 1.25f
                val p = Paint().apply { isAntiAlias = false }
                val filled = (n * d.pct / 100f).roundToInt()
                for (k in 0 until n) {
                    val ang = Math.toRadians(k * 360.0 / n - 90)
                    val x = cx + r * cos(ang).toFloat(); val y = cy + r * sin(ang).toFloat()
                    p.color = if (k < filled) lerp(d.a, d.b, k / (n - 1f)) else L.track
                    c.drawRect(x - box / 2, y - box / 2, x + box / 2, y + box / 2, p)
                }
            }
            else -> segRing(c, cx, cy, r, w, d.pct, segs, d.a, d.b, L.track, L.glow)
        }
    }

    private fun camIcon(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = maxOf(2f, s * 0.09f); color = col
            setShadowLayer(s * 0.15f, 0f, 0f, alpha(col, 160))
        }
        c.drawRoundRect(RectF(x, y + s * 0.2f, x + s, y + s * 0.85f), s * 0.12f, s * 0.12f, p)
        c.drawCircle(x + s / 2, y + s * 0.52f, s * 0.2f, p)
        p.style = Paint.Style.FILL
        c.drawRect(x + s * 0.3f, y + s * 0.08f, x + s * 0.6f, y + s * 0.22f, p)
    }

    /** Pozadí ve stylu HUD: tmavý přechod, jemné řádky, tenký rámeček a rohové závorky. */
    private fun hudFrame(c: Canvas, W: Float, H: Float, rad: Float, light: Boolean, accent: Int, t: Themes.T) {
        when (t.style) {
            "terminal" -> { terminalBg(c, W, H, rad * 0.4f, light, accent, t); return }
            "fantasy" -> { fantasyBg(c, W, H, rad * 0.5f, light, accent, t); return }
            "parchment" -> { parchmentBg(c, W, H, rad * 0.35f, light, t); return }
            "steam" -> { steamBg(c, W, H, rad * 0.45f, light, accent, t); return }
            "pixel" -> { pixelBg(c, W, H, light, accent, t); return }
            "cyber" -> { cyberBg(c, W, H, light, accent, t); return }
        }
        val r = RectF(1f, 1f, W - 1, H - 1)
        val a = if (light) 150 else 235
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, H, alpha(t.bgTop, a), alpha(t.bgBot, a), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, rad, rad, bg)
        if (!t.hud) {
            // Minimal: jen jemný rámeček
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = alpha(accent, 40) }
            c.drawRoundRect(r, rad, rad, border)
            return
        }
        c.save()
        val clip = android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        val sl = Paint().apply { color = alpha(accent, 10); strokeWidth = 1f }
        var y = 0f
        while (y < H) { c.drawLine(0f, y, W, y, sl); y += 4f }
        c.restore()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = alpha(accent, 60) }
        c.drawRoundRect(r, rad, rad, border)
        val L = min(W, H) * 0.16f
        val ins = rad * 0.45f
        val br = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 3f; color = alpha(accent, 210); strokeCap = Paint.Cap.SQUARE
            setShadowLayer(4f, 0f, 0f, alpha(accent, 150))
        }
        for ((cx, cy, sx, sy) in listOf(
            arrayOf(ins, ins, 1f, 1f), arrayOf(W - ins, ins, -1f, 1f),
            arrayOf(ins, H - ins, 1f, -1f), arrayOf(W - ins, H - ins, -1f, -1f),
        )) {
            c.drawLine(cx, cy, cx + sx * L, cy, br)
            c.drawLine(cx, cy, cx, cy + sy * L, br)
        }
    }


    // ------------------------------------------------------------ pozadí jednotlivých stylů

    private fun grad(H: Float, t: Themes.T, a: Int) = LinearGradient(0f, 0f, 0f, H, alpha(t.bgTop, a), alpha(t.bgBot, a), Shader.TileMode.CLAMP)

    private fun shapeBg(c: Canvas, path: android.graphics.Path, W: Float, H: Float, t: Themes.T, light: Boolean) {
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad(H, t, if (light) 150 else 238) })
    }

    /** Retro terminál: zelený/jantarový fosfor, řádkování, ztmavené okraje (CRT). */
    private fun terminalBg(c: Canvas, W: Float, H: Float, rad: Float, light: Boolean, acc: Int, t: Themes.T) {
        val r = RectF(1f, 1f, W - 1, H - 1)
        val path = android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) }
        shapeBg(c, path, W, H, t, light)
        c.save(); c.clipPath(path)
        val glow = Paint().apply { shader = RadialGradient(W / 2, H / 2, maxOf(W, H) * 0.6f, alpha(acc, 28), Color.TRANSPARENT, Shader.TileMode.CLAMP) }
        c.drawRect(r, glow)
        val sl = Paint().apply { color = Color.argb(70, 0, 0, 0); strokeWidth = 1.2f }
        var y = 0f
        while (y < H) { c.drawLine(0f, y, W, y, sl); y += 3f }
        val vig = Paint().apply { shader = RadialGradient(W / 2, H / 2, maxOf(W, H) * 0.75f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) }
        c.drawRect(r, vig)
        c.restore()
        c.drawRoundRect(r, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = alpha(acc, 120); setShadowLayer(6f, 0f, 0f, alpha(acc, 120)) })
        val i = minOf(W, H) * 0.05f
        c.drawRoundRect(RectF(i, i, W - i, H - i), rad * 0.6f, rad * 0.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = alpha(acc, 40) })
    }

    /** Temné fantasy: tmavá ocel, stříbrný dvojitý rámeček s kosočtverci v rozích. */
    private fun fantasyBg(c: Canvas, W: Float, H: Float, rad: Float, light: Boolean, acc: Int, t: Themes.T) {
        val r = RectF(1f, 1f, W - 1, H - 1)
        val path = android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) }
        shapeBg(c, path, W, H, t, light)
        c.save(); c.clipPath(path)
        val rnd = java.util.Random(7)
        val dot = Paint().apply { color = Color.argb(14, 255, 255, 255) }
        repeat((W * H / 180).toInt().coerceAtMost(4000)) { c.drawPoint(rnd.nextFloat() * W, rnd.nextFloat() * H, dot) }
        c.drawRect(r, Paint().apply { shader = RadialGradient(W / 2, H / 2, maxOf(W, H) * 0.7f, intArrayOf(Color.TRANSPARENT, Color.argb(120, 0, 0, 0)), null, Shader.TileMode.CLAMP) })
        c.restore()
        val silver = 0xFFBFC3C7.toInt()
        val u = minOf(W, H)
        val o = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(2f, u * 0.018f); color = alpha(silver, 200) }
        c.drawRoundRect(RectF(3f, 3f, W - 3, H - 3), rad, rad, o)
        val ins = u * 0.075f
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = alpha(silver, 90) }
        c.drawRect(ins, ins, W - ins, H - ins, inner)
        val dia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = acc; setShadowLayer(u * 0.02f, 0f, 0f, alpha(acc, 160)) }
        val s2 = u * 0.045f
        for ((x, y) in listOf(ins to ins, W - ins to ins, ins to H - ins, W - ins to H - ins)) {
            c.drawPath(android.graphics.Path().apply { moveTo(x, y - s2); lineTo(x + s2 * 0.7f, y); lineTo(x, y + s2); lineTo(x - s2 * 0.7f, y); close() }, dia)
        }
    }

    /** Pergamen: světlý papír se skvrnami, hnědý inkoustový dvojitý rámeček. */
    private fun parchmentBg(c: Canvas, W: Float, H: Float, rad: Float, light: Boolean, t: Themes.T) {
        val r = RectF(1f, 1f, W - 1, H - 1)
        val path = android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) }
        shapeBg(c, path, W, H, t, light)
        c.save(); c.clipPath(path)
        val rnd = java.util.Random(3)
        repeat(7) {
            val x = rnd.nextFloat() * W; val y = rnd.nextFloat() * H; val rr = minOf(W, H) * (0.2f + rnd.nextFloat() * 0.5f)
            c.drawCircle(x, y, rr, Paint().apply { shader = RadialGradient(x, y, rr, Color.argb(35, 120, 80, 30), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        }
        c.drawRect(r, Paint().apply { shader = RadialGradient(W / 2, H / 2, maxOf(W, H) * 0.7f, intArrayOf(Color.TRANSPARENT, Color.argb(110, 90, 55, 20)), null, Shader.TileMode.CLAMP) })
        c.restore()
        val ink = 0xFF5A3E1B.toInt()
        val u = minOf(W, H)
        c.drawRoundRect(RectF(4f, 4f, W - 4, H - 4), rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(2f, u * 0.015f); color = alpha(ink, 200) })
        val ins = u * 0.07f
        c.drawRect(ins, ins, W - ins, H - ins, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = alpha(ink, 110) })
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(ink, 170) }
        for ((x, y) in listOf(ins to ins, W - ins to ins, ins to H - ins, W - ins to H - ins)) c.drawCircle(x, y, u * 0.022f, dot)
    }

    /** Steampunk: tmavá kůže, mosazný rámeček s nýty. */
    private fun steamBg(c: Canvas, W: Float, H: Float, rad: Float, light: Boolean, acc: Int, t: Themes.T) {
        val r = RectF(1f, 1f, W - 1, H - 1)
        val path = android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) }
        shapeBg(c, path, W, H, t, light)
        c.save(); c.clipPath(path)
        c.drawRect(r, Paint().apply { shader = RadialGradient(W * 0.35f, H * 0.3f, maxOf(W, H) * 0.8f, Color.argb(40, 255, 200, 140), Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP) })
        c.restore()
        val u = minOf(W, H)
        val bw = u * 0.05f
        val brass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = bw
            shader = LinearGradient(0f, 0f, W, H, intArrayOf(lerp(acc, Color.BLACK, 0.3f), lerp(acc, Color.WHITE, 0.35f), lerp(acc, Color.BLACK, 0.3f)), null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(bw / 2 + 1, bw / 2 + 1, W - bw / 2 - 1, H - bw / 2 - 1), rad, rad, brass)
        val rv = u * 0.028f
        val rivets = mutableListOf(rad * 0.7f to rad * 0.7f, W - rad * 0.7f to rad * 0.7f, rad * 0.7f to H - rad * 0.7f, W - rad * 0.7f to H - rad * 0.7f)
        if (W > H * 1.8f) { rivets += W / 2 to bw / 2 + 1; rivets += W / 2 to H - bw / 2 - 1 }
        for ((x, y) in rivets) c.drawCircle(x, y, rv, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x - rv * 0.3f, y - rv * 0.3f, rv * 1.3f, lerp(acc, Color.WHITE, 0.6f), lerp(acc, Color.BLACK, 0.45f), Shader.TileMode.CLAMP)
        })
    }

    /** 8-bit: plná barva, kostičkový rámeček se „schodovitými“ rohy, bez vyhlazení. */
    private fun pixelBg(c: Canvas, W: Float, H: Float, light: Boolean, acc: Int, t: Themes.T) {
        val b = maxOf(3f, minOf(W, H) * 0.035f)
        val p = Paint().apply { isAntiAlias = false }
        p.color = alpha(t.bgTop, if (light) 160 else 255)
        c.drawRect(b, 0f, W - b, H, p); c.drawRect(0f, b, W, H - b, p)
        p.color = acc
        c.drawRect(b * 2, 0f, W - b * 2, b, p); c.drawRect(b * 2, H - b, W - b * 2, H, p)
        c.drawRect(0f, b * 2, b, H - b * 2, p); c.drawRect(W - b, b * 2, W, H - b * 2, p)
        for ((x, y) in listOf(b to b, W - b * 2 to b, b to H - b * 2, W - b * 2 to H - b * 2)) c.drawRect(x, y, x + b, y + b, p)
        p.color = alpha(Color.BLACK, 90)
        c.drawRect(b * 2, H - b * 2, W - b * 2, H - b, p)
    }

    /** Kyberpunk: zkosené rohy, žlutý rámeček, výstražné pruhy v rohu. */
    private fun cyberBg(c: Canvas, W: Float, H: Float, light: Boolean, acc: Int, t: Themes.T) {
        val k = minOf(W, H) * 0.2f
        val path = android.graphics.Path().apply {
            moveTo(1f, 1f); lineTo(W - k, 1f); lineTo(W - 1, k); lineTo(W - 1, H - 1); lineTo(k, H - 1); lineTo(1f, H - k); close()
        }
        shapeBg(c, path, W, H, t, light)
        c.save(); c.clipPath(path)
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(acc, 55); strokeWidth = k * 0.12f }
        var x = W - k * 1.6f
        while (x < W + k) { c.drawLine(x, H, x + k * 0.6f, H - k * 0.6f, stripe); x += k * 0.25f }
        val sl = Paint().apply { color = Color.argb(20, 255, 255, 255); strokeWidth = 1f }
        var y = 0f
        while (y < H) { c.drawLine(0f, y, W, y, sl); y += 5f }
        c.restore()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = acc; setShadowLayer(5f, 0f, 0f, alpha(acc, 150)) })
        c.drawRect(1f, H * 0.25f, maxOf(3f, k * 0.08f), H * 0.6f, Paint().apply { color = acc })
    }

    /** Pozadí kulatého widgetu podle stylu. */
    private fun ringBg(c: Canvas, cx: Float, cy: Float, S: Float, d: Data) {
        val t = d.theme
        val a = if (d.light) 150 else 238
        when (t.style) {
            "pixel" -> {
                c.save(); c.translate(cx - S / 2, cy - S / 2); pixelBg(c, S, S, d.light, d.a, t); c.restore(); return
            }
            "cyber" -> {
                val k = S * 0.29f; val l = cx - S / 2; val tp = cy - S / 2
                val path = android.graphics.Path().apply {
                    moveTo(l + k, tp + 1); lineTo(l + S - k, tp + 1); lineTo(l + S - 1, tp + k); lineTo(l + S - 1, tp + S - k)
                    lineTo(l + S - k, tp + S - 1); lineTo(l + k, tp + S - 1); lineTo(l + 1, tp + S - k); lineTo(l + 1, tp + k); close()
                }
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(cx, cy, S / 2, alpha(t.bgTop, a), alpha(t.bgBot, a), Shader.TileMode.CLAMP) })
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = d.a; setShadowLayer(5f, 0f, 0f, alpha(d.a, 150)) })
                return
            }
        }
        c.drawCircle(cx, cy, S / 2 - 1, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, S / 2, alpha(t.bgTop, a), alpha(t.bgBot, a), Shader.TileMode.CLAMP)
        })
        val edge = when (t.style) { "parchment" -> 0xFF5A3E1B.toInt(); "fantasy" -> 0xFFBFC3C7.toInt(); "steam" -> lerp(d.a, Color.WHITE, 0.2f); else -> d.a }
        val o = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = if (t.style == "steam") S * 0.035f else 2f; color = alpha(edge, if (t.style == "hud" || t.style == "minimal") 70 else 190) }
        c.drawCircle(cx, cy, S / 2 - o.strokeWidth / 2 - 1, o)
        if (t.style == "terminal") {
            c.save(); c.clipPath(android.graphics.Path().apply { addCircle(cx, cy, S / 2 - 2, android.graphics.Path.Direction.CW) })
            val sl = Paint().apply { color = Color.argb(70, 0, 0, 0); strokeWidth = 1.2f }
            var y = cy - S / 2
            while (y < cy + S / 2) { c.drawLine(cx - S / 2, y, cx + S / 2, y, sl); y += 3f }
            c.restore()
        }
        if (t.style == "fantasy" || t.style == "parchment") c.drawCircle(cx, cy, S * 0.455f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = alpha(edge, 100) })
    }

    /** Široký panel (3×1, 4×1, 4×2). */
    fun panel(ctx: Context, W: Int, H: Int, d: Data): Bitmap {
        fonts(ctx)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = W.toFloat()
        val h = H.toFloat()
        // u = „jednotka“ velikosti: u nízkého panelu výška, u vysokého (4×2) podle šířky
        val u = min(h, w / 2.6f)
        hudFrame(c, w, h, u * 0.24f, d.light, d.a, d.theme)

        val R = u * 0.36f
        val cx = u * 0.52f
        val cy = h / 2
        var x = u * 0.16f
        val L = look(d)
        if (d.showRing) {
            styledRing(c, cx, cy, R, u * 0.075f, d, L, 36)
            if (d.theme.style == "hud" || d.theme.style == "minimal" || d.theme.style == "cyber") {
                val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = alpha(d.a, 50) }
                c.drawCircle(cx, cy, R * 0.7f, inner)
            }
            val pp = textPaint(L.time, R * 0.62f * L.timeK, L.text, aa = L.aa).apply { textAlign = Paint.Align.CENTER }
            fx(c, "${d.pct}", cx, cy, pp, L, d.a, true)
            val pc = textPaint(L.label, R * 0.32f * L.labelK, L.labelCol, aa = L.aa).apply { textAlign = Paint.Align.CENTER }
            drawMid(c, "%", cx, cy + R * 0.5f, pc)
            x = cx + R + u * 0.16f
        }
        val camReserve = if (d.cam) u * 0.5f else u * 0.15f
        val lp = textPaint(L.label, u * 0.13f * L.labelK, L.labelCol, aa = L.aa).apply { letterSpacing = L.spacing }
        var tsz = u * 0.33f * L.timeK
        val tp = textPaint(L.time, tsz, L.text, aa = L.aa)
        while (tp.measureText(d.time) > w - x - u * 0.15f && tsz > u * 0.1f) { tsz *= 0.92f; tp.textSize = tsz }
        val ip = textPaint(L.info, u * 0.13f * L.infoK, d.theme.info, aa = L.aa)

        // kolik řádků údajů se vejde na výšku (víc u 4×2)
        val lineH = ip.textSize * 1.3f
        val labelH = lp.textSize * 1.5f
        val timeH = tsz * 1.15f
        val budget = h - u * 0.2f - labelH - timeH
        val maxLines = (budget / lineH).toInt().coerceIn(1, 4)
        val lines = pack(d.parts, ip, w - x - u * 0.15f, maxLines)

        // celý blok svisle na střed
        val blockH = labelH + timeH + lines.size * lineH
        val top = ((h - blockH) / 2).coerceAtLeast(u * 0.06f)
        fx(c, fit(L.prefix + d.label + L.suffix, lp, w - x - camReserve), x, top + labelH * 0.5f, lp, L, d.a, false)
        fx(c, d.time, x - 2, top + labelH + timeH * 0.5f, tp, L, d.a, true)
        lines.forEachIndexed { li, line ->
            drawLine(c, line, x, top + labelH + timeH + lineH * (li + 0.5f), ip, false)
        }

        if (d.cam) camIcon(c, w - u * 0.42f, u * 0.14f, u * 0.2f, if (d.theme.style == "parchment") L.text else alpha(d.a, 220))
        return bmp
    }

    /** Kruh – kreslí se do celé plochy widgetu, kruh je uprostřed. */
    fun ring(ctx: Context, W: Int, H: Int, d: Data): Bitmap {
        fonts(ctx)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val S = min(W, H).toFloat()
        val cx = W / 2f
        val cy = H / 2f

        val L = look(d)
        ringBg(c, cx, cy, S, d)

        // ryski jako na ciferníku (HUD a terminál)
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = maxOf(1.5f, S * 0.006f) }
        if (d.theme.style == "hud" || d.theme.style == "terminal") for (k in 0 until 60) {
            val ang = Math.toRadians(k * 6.0 - 90)
            val r1 = S * 0.47f
            val r2 = S * if (k % 5 == 0) 0.43f else 0.445f
            tick.color = alpha(d.a, if (k % 5 == 0) 120 else 45)
            c.drawLine(cx + r1 * cos(ang).toFloat(), cy + r1 * sin(ang).toFloat(),
                cx + r2 * cos(ang).toFloat(), cy + r2 * sin(ang).toFloat(), tick)
        }
        if (d.showRing) styledRing(c, cx, cy, S * 0.37f, S * 0.055f, d, L, 48)
        if (d.theme.style == "hud" || d.theme.style == "minimal" || d.theme.style == "cyber") {
            val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = alpha(d.a, 40) }
            c.drawCircle(cx, cy, S * 0.29f, inner)
        }

        val maxW = S * 0.52f
        val lp = textPaint(L.label, S * 0.065f * L.labelK, L.labelCol, aa = L.aa).apply { textAlign = Paint.Align.CENTER; letterSpacing = L.spacing }
        fx(c, fit(L.prefix + d.label + L.suffix, lp, maxW), cx, cy - S * 0.17f, lp, L, d.a, false)

        var tsz = S * 0.15f * L.timeK
        val tp = textPaint(L.time, tsz, L.text, aa = L.aa).apply { textAlign = Paint.Align.CENTER }
        while (tp.measureText(d.time) > maxW && tsz > S * 0.05f) { tsz *= 0.92f; tp.textSize = tsz }
        fx(c, d.time, cx, cy - S * 0.02f, tp, L, d.a, true)

        if (d.showRing || d.pct > 0) {
            val pp = textPaint(if (d.theme.style == "hud" || d.theme.style == "minimal") orbMid else L.time, S * 0.07f * L.timeK, if (d.theme.style == "parchment") L.labelCol else d.b, aa = L.aa).apply { textAlign = Paint.Align.CENTER }
            drawMid(c, "${d.pct} %", cx, cy + S * 0.12f, pp)
        }
        val ip = textPaint(L.info, S * 0.06f * L.infoK, d.theme.info, aa = L.aa)
        val lines = pack(d.parts, ip, maxW, 2)
        lines.forEachIndexed { li, line -> drawLine(c, line, cx, cy + S * (0.2f + 0.07f * li), ip, true) }
        // kamera v pravém dolním rohu widgetu (tam je i klikací plocha)
        if (d.cam) {
            val s = S * 0.1f
            camIcon(c, W - s * 1.6f, H - s * 1.5f, s, if (d.theme.style == "parchment") L.text else alpha(d.a, 220))
        }
        return bmp
    }
}
