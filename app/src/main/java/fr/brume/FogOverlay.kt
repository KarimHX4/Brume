package fr.brume

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Color
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
    private val fogColor = 0xF0101626.toInt()
    // Texture de nuages qui donne du relief à la brume
    private val mist = Paint().apply {
        shader = BitmapShader(makeNoise(256), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val mistMatrix = Matrix()
    private val origin = GeoPoint(0.0, 0.0)
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

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = mv.projection
        val bb = mv.boundingBox
        val layer = c.saveLayer(null, null)
        c.drawColor(fogColor)
        // La texture suit la carte quand on la déplace
        pj.toPixels(origin, p0)
        mistMatrix.setScale(2.6f, 2.6f)
        mistMatrix.postTranslate(p0.x.toFloat() % 665.6f, p0.y.toFloat() % 665.6f)
        mist.shader.setLocalMatrix(mistMatrix)
        c.drawPaint(mist)
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
                val a = (v * v * 95).toInt()
                px[i] = (a shl 24) or (0xBF shl 16) or (0xCC shl 8) or 0xE6
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
