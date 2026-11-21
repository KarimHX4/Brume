package fr.brume

import android.graphics.Canvas
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
    private val fogColor = 0xE80E121C.toInt()
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
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
                    if (round) c.drawCircle(x, y, cpx * 0.85f, clear)
                    else c.drawRect(x - sz / 2, y - sz / 2, x + sz / 2, y + sz / 2, clear)
                }
            }
        }
        c.restoreToCount(layer)

        val m = Fog.me ?: return
        gp.setCoords(m[0], m[1]); pj.toPixels(gp, p0)
        val accPx = pj.metersToPixels(m[2].toFloat())
        if (accPx > 16f) c.drawCircle(p0.x.toFloat(), p0.y.toFloat(), accPx, accPaint)
        c.drawCircle(p0.x.toFloat(), p0.y.toFloat(), 14f, meFill)
        c.drawCircle(p0.x.toFloat(), p0.y.toFloat(), 14f, meStroke)
    }
}
