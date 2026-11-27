package fr.brume

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.max

/** Dessine le brouillard par-dessus la carte puis « gomme » les cellules dévoilées. */
class FogOverlay : Overlay() {
    // Texture de nuages, en deux couches d'échelles différentes fondues l'une dans l'autre :
    // elle grossit avec la carte au zoom, sans saut ni effet stroboscopique
    private val noise = makeNoise(256)
    private val mistA = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val mistB = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val mA = Matrix()
    private val mB = Matrix()
    private var anchor: GeoPoint? = null
    private val ap = Point()

    private fun layer(paint: Paint, m: Matrix, scale: Float, weight: Float, c: Canvas) {
        if (weight <= 0.02f) return
        val period = 256f * scale
        m.setScale(scale, scale)
        m.postTranslate(ap.x.toFloat() % period, ap.y.toFloat() % period)
        paint.shader.setLocalMatrix(m)
        paint.alpha = (weight * 255).toInt()
        c.drawPaint(paint)
    }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    // Halo semi-transparent autour des zones dévoilées : bords de brume adoucis
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); alpha = 70 }
    private val meFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7CC4FF.toInt() }
    private val meStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val accPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x307CC4FF }
    private val gp = GeoPoint(0.0, 0.0)
    private val p0 = Point()
    private val p1 = Point()
    private val rowDl = DoubleArray(Fog.CH)

    // Limites des communes
    private val bPath = Path()
    private var dens = 0f
    private val bShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x73000000; strokeJoin = Paint.Join.ROUND }
    private val bOther = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xD9FFFFFF.toInt(); strokeJoin = Paint.Join.ROUND }
    private val bFocus = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF7CC4FF.toInt(); strokeJoin = Paint.Join.ROUND }
    private val bDistrict = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x8CFFFFFF.toInt(); strokeJoin = Paint.Join.ROUND }
    private val bGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x407CC4FF; strokeJoin = Paint.Join.ROUND }

    private fun setupPaints(d: Float) {
        if (d == dens) return
        dens = d
        bShadow.strokeWidth = 4f * d
        bOther.strokeWidth = 1.6f * d
        bOther.pathEffect = DashPathEffect(floatArrayOf(7f * d, 5f * d), 0f)
        bFocus.strokeWidth = 3f * d
        bGlow.strokeWidth = 9f * d
        bDistrict.strokeWidth = 1f * d
        bDistrict.pathEffect = DashPathEffect(floatArrayOf(2f * d, 4f * d), 0f)
    }

    /** Construit le tracé des limites d'une commune en coordonnées écran (points trop proches sautés). */
    private fun buildBorder(c: City, pj: org.osmdroid.views.Projection): Boolean {
        bPath.reset()
        var any = false
        for (poly in c.polys) for (r in poly) {
            var lx = Int.MIN_VALUE; var ly = Int.MIN_VALUE
            var q = 0
            var first = true
            while (q + 1 < r.size) {
                gp.setCoords(r[q + 1], r[q]); pj.toPixels(gp, p0)
                if (first) { bPath.moveTo(p0.x.toFloat(), p0.y.toFloat()); first = false; lx = p0.x; ly = p0.y }
                else if (abs(p0.x - lx) + abs(p0.y - ly) >= 2) { bPath.lineTo(p0.x.toFloat(), p0.y.toFloat()); lx = p0.x; ly = p0.y }
                q += 2
            }
            if (!first) { bPath.close(); any = true }
        }
        return any
    }

    private fun drawBorders(c: Canvas, mv: MapView, pj: org.osmdroid.views.Projection) {
        setupPaints(mv.context.resources.displayMetrics.density)
        val bb = mv.boundingBox
        val m = Fog.me
        val list = synchronized(Fog.lock) { Fog.cities.values.toList() }
        val dlist = synchronized(Fog.lock) { Fog.districts.values.flatten() }
        val fid = Fog.focusId
        val focus = (if (fid != null) list.firstOrNull { it.id == fid } ?: dlist.firstOrNull { it.id == fid } else null)
            ?: if (m != null) list.firstOrNull { it.contains(m[0], m[1]) } else null
        // Arrondissements / quartiers : pointillés fins
        for (d in dlist) {
            val b = d.bbox
            if (b[2] < bb.lonWest || b[0] > bb.lonEast || b[3] < bb.latSouth || b[1] > bb.latNorth) continue
            gp.setCoords(b[1], b[0]); pj.toPixels(gp, p0); val x0 = p0.x; val y0 = p0.y
            gp.setCoords(b[3], b[2]); pj.toPixels(gp, p1)
            if (abs(p1.x - x0) + abs(p1.y - y0) < 40) continue
            if (d === focus) continue
            if (buildBorder(d, pj)) c.drawPath(bPath, bDistrict)
        }
        for (city in list) {
            val b = city.bbox
            if (b[2] < bb.lonWest || b[0] > bb.lonEast || b[3] < bb.latSouth || b[1] > bb.latNorth) continue
            // Trop petite à l'écran : inutile de la dessiner
            gp.setCoords(b[1], b[0]); pj.toPixels(gp, p0); val x0 = p0.x; val y0 = p0.y
            gp.setCoords(b[3], b[2]); pj.toPixels(gp, p1)
            if (abs(p1.x - x0) + abs(p1.y - y0) < 10) continue
            if (city === focus) continue
            if (buildBorder(city, pj)) { c.drawPath(bPath, bShadow); c.drawPath(bPath, bOther) }
        }
        // La commune actuelle par-dessus, en bleu
        if (focus != null && buildBorder(focus, pj)) {
            c.drawPath(bPath, bGlow); c.drawPath(bPath, bShadow); c.drawPath(bPath, bFocus)
        }
    }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = mv.projection
        val bb = mv.boundingBox
        val layer = c.saveLayer(null, null)
        val a = (Fog.fogAlpha.coerceIn(20, 100) * 255 / 100)
        c.drawColor((a shl 24) or 0x101626)
        // Texture accrochée à un point de la carte proche de l'écran
        var an = anchor
        if (an != null) pj.toPixels(an, ap)
        if (an == null || abs(ap.x) > 100_000 || abs(ap.y) > 100_000) {
            val ctr = mv.mapCenter
            an = GeoPoint(ctr.latitude, ctr.longitude); anchor = an
            pj.toPixels(an, ap)
        }
        val z = mv.zoomLevelDouble
        val f = (z - Math.floor(z)).toFloat()
        val s0 = (1.8 * Math.pow(2.0, f.toDouble())).toFloat()
        layer(mistA, mA, s0, f, c)            // petite échelle : apparaît en zoomant
        layer(mistB, mB, s0 * 2f, 1f - f, c)  // grande échelle : s'efface en zoomant
        synchronized(Fog.lock) {
            for ((k, bits) in Fog.chunks) {
                val i0 = Fog.kci(k) * Fog.CH
                val cj = Fog.kcj(k)
                val lat0 = i0 * Fog.DLAT
                val lat1 = (i0 + Fog.CH) * Fog.DLAT
                if (lat1 < bb.latSouth || lat0 > bb.latNorth) continue
                val dl = Fog.dLon(i0 + Fog.CH / 2)
                val lon0 = cj * Fog.CH * dl
                val lon1 = (cj + 1) * Fog.CH * dl
                if (lon1 < bb.lonWest || lon0 > bb.lonEast) continue
                gp.setCoords(lat0, lon0); pj.toPixels(gp, p0)
                gp.setCoords(lat1, lon1); pj.toPixels(gp, p1)
                val w = (p1.x - p0.x).toFloat()
                val h = (p1.y - p0.y).toFloat()
                if (abs(w) < 4f) {
                    val cx = (p0.x + p1.x) / 2f; val cy = (p0.y + p1.y) / 2f
                    c.drawRect(cx - 1.5f, cy - 1.5f, cx + 1.5f, cy + 1.5f, clear)
                    continue
                }
                for (li in 0 until Fog.CH) rowDl[li] = Fog.dLon(i0 + li)
                val cpx = abs(w) / Fog.CH
                val sz = max(cpx * 1.25f, 2f)
                val round = cpx >= 6f
                val span = lon1 - lon0
                Fog.forBits(bits) { li, lj ->
                    val lon = (cj * Fog.CH + lj + 0.5) * rowDl[li]
                    val x = p0.x + ((lon - lon0) / span * w).toFloat()
                    val y = p0.y + (li + 0.5f) / Fog.CH * h
                    if (round) { c.drawCircle(x, y, cpx * 1.9f, soft); c.drawCircle(x, y, cpx * 0.85f, clear) }
                    else c.drawRect(x - sz / 2, y - sz / 2, x + sz / 2, y + sz / 2, clear)
                }
            }
        }
        c.restoreToCount(layer)
        if (Fog.showBorders) drawBorders(c, mv, pj)
    }

    companion object {
        private fun smooth(t: Float) = t * t * (3 - 2 * t)

        /** Bruit de valeur multi-octaves, raccordable sur les bords (motif répétable sans couture). */
        fun makeNoise(size: Int): Bitmap {
            val rnd = java.util.Random(42)
            val acc = FloatArray(size * size)
            var amp = 1f
            var total = 0f
            for (cells in intArrayOf(3, 6, 12, 24, 48)) {
                val g = FloatArray(cells * cells) { rnd.nextFloat() }
                val step = size.toFloat() / cells
                for (y in 0 until size) {
                    val gy = y / step
                    val y0 = gy.toInt()
                    val fy = smooth(gy - y0)
                    val ya = (y0 % cells) * cells
                    val yb = ((y0 + 1) % cells) * cells
                    for (x in 0 until size) {
                        val gx = x / step
                        val x0 = gx.toInt()
                        val fx = smooth(gx - x0)
                        val xa = x0 % cells
                        val xb = (x0 + 1) % cells
                        val top = g[ya + xa] + (g[ya + xb] - g[ya + xa]) * fx
                        val bot = g[yb + xa] + (g[yb + xb] - g[yb + xa]) * fx
                        acc[y * size + x] += amp * (top + (bot - top) * fy)
                    }
                }
                total += amp
                amp *= 0.55f
            }
            val px = IntArray(size * size)
            for (i in px.indices) {
                val v = ((acc[i] / total - 0.38f) / 0.42f).coerceIn(0f, 1f)
                val a = (v * v * 75).toInt()
                px[i] = (a shl 24) or (0xBF shl 16) or (0xCC shl 8) or 0xE6
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
