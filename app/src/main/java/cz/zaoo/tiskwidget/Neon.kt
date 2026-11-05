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

    private fun fonts(ctx: Context) {
        if (orb != null) return
        val am = ctx.assets
        orb = try { Typeface.Builder(am, "fonts/orbitron.ttf").setFontVariationSettings("'wght' 800").build() }
              catch (_: Exception) { Typeface.DEFAULT_BOLD }
        orbMid = try { Typeface.Builder(am, "fonts/orbitron.ttf").setFontVariationSettings("'wght' 600").build() }
                 catch (_: Exception) { Typeface.DEFAULT_BOLD }
        raj = try { Typeface.createFromAsset(am, "fonts/rajdhani.ttf") } catch (_: Exception) { Typeface.DEFAULT }
    }

    private fun lerp(c1: Int, c2: Int, t: Float): Int = Color.rgb(
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt(),
    )

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun textPaint(tf: Typeface?, size: Float, color: Int, glow: Int? = null, glowR: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private fun segRing(c: Canvas, cx: Float, cy: Float, r: Float, w: Float, pct: Int, segs: Int, a: Int, b: Int) {
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
                p.setShadowLayer(w * 0.9f, 0f, 0f, alpha(col, 200))
            } else {
                p.color = TRACK
                p.clearShadowLayer()
            }
            c.drawArc(box, start, sweep, false, p)
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
        if (d.showRing) {
            segRing(c, cx, cy, R, u * 0.075f, d.pct, 36, d.a, d.b)
            val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = alpha(d.a, 50) }
            c.drawCircle(cx, cy, R * 0.7f, inner)
            val pp = textPaint(orb, R * 0.62f, Color.WHITE, alpha(d.a, 220), R * 0.12f).apply { textAlign = Paint.Align.CENTER }
            drawMid(c, "${d.pct}", cx, cy, pp)
            val pc = textPaint(raj, R * 0.32f, alpha(d.a, 220)).apply { textAlign = Paint.Align.CENTER }
            drawMid(c, "%", cx, cy + R * 0.48f, pc)
            x = cx + R + u * 0.16f
        }
        val camReserve = if (d.cam) u * 0.5f else u * 0.15f
        val lp = textPaint(raj, u * 0.13f, alpha(d.a, 190)).apply { letterSpacing = 0.35f }
        var tsz = u * 0.33f
        val tp = textPaint(orb, tsz, Color.WHITE, alpha(d.a, 220), u * 0.05f)
        while (tp.measureText(d.time) > w - x - u * 0.15f && tsz > u * 0.12f) { tsz *= 0.92f; tp.textSize = tsz }
        val ip = textPaint(raj, u * 0.13f, d.theme.info)

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
        drawMid(c, fit(d.label, lp, w - x - camReserve), x, top + labelH * 0.5f, lp)
        drawMid(c, d.time, x - 2, top + labelH + timeH * 0.5f, tp)
        lines.forEachIndexed { li, line ->
            drawLine(c, line, x, top + labelH + timeH + lineH * (li + 0.5f), ip, false)
        }

        if (d.cam) camIcon(c, w - u * 0.42f, u * 0.14f, u * 0.2f, alpha(d.a, 220))
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
        val a = if (d.light) 150 else 235

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, S / 2, alpha(d.theme.bgTop, a), alpha(d.theme.bgBot, a), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, S / 2 - 1, bg)
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = alpha(d.a, 70) }
        c.drawCircle(cx, cy, S / 2 - 2, outer)

        // ryski jako na ciferníku
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = maxOf(1.5f, S * 0.006f) }
        if (d.theme.hud) for (k in 0 until 60) {
            val ang = Math.toRadians(k * 6.0 - 90)
            val r1 = S * 0.47f
            val r2 = S * if (k % 5 == 0) 0.43f else 0.445f
            tick.color = alpha(d.a, if (k % 5 == 0) 120 else 45)
            c.drawLine(cx + r1 * cos(ang).toFloat(), cy + r1 * sin(ang).toFloat(),
                cx + r2 * cos(ang).toFloat(), cy + r2 * sin(ang).toFloat(), tick)
        }
        if (d.showRing) segRing(c, cx, cy, S * 0.37f, S * 0.055f, d.pct, 48, d.a, d.b)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = alpha(d.a, 40) }
        c.drawCircle(cx, cy, S * 0.29f, inner)

        val maxW = S * 0.52f
        val lp = textPaint(raj, S * 0.065f, alpha(d.a, 190)).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.35f }
        drawMid(c, fit(d.label, lp, maxW), cx, cy - S * 0.17f, lp)

        var tsz = S * 0.15f
        val tp = textPaint(orb, tsz, Color.WHITE, alpha(d.a, 220), S * 0.025f).apply { textAlign = Paint.Align.CENTER }
        while (tp.measureText(d.time) > maxW && tsz > S * 0.06f) { tsz *= 0.92f; tp.textSize = tsz }
        drawMid(c, d.time, cx, cy - S * 0.02f, tp)

        if (d.showRing || d.pct > 0) {
            val pp = textPaint(orbMid, S * 0.07f, d.b).apply { textAlign = Paint.Align.CENTER }
            drawMid(c, "${d.pct} %", cx, cy + S * 0.12f, pp)
        }
        val ip = textPaint(raj, S * 0.06f, d.theme.info)
        val lines = pack(d.parts, ip, maxW, 2)
        lines.forEachIndexed { li, line -> drawLine(c, line, cx, cy + S * (0.2f + 0.07f * li), ip, true) }
        // kamera v pravém dolním rohu widgetu (tam je i klikací plocha)
        if (d.cam) {
            val s = S * 0.1f
            camIcon(c, W - s * 1.6f, H - s * 1.5f, s, alpha(d.a, 220))
        }
        return bmp
    }
}
