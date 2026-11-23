package fr.brume

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Marqueur de position : pastille blanche avec cœur dégradé, onde lumineuse qui pulse pendant
 * l'exploration, faisceau orienté et flèche quand on se déplace.
 * Vue séparée de la carte : l'animation ne redessine pas le brouillard.
 */
@SuppressLint("ViewConstructor")
class MeView(ctx: Context, private val map: MapView) : View(ctx) {
    var active = false      // exploration en cours
    var paused = false      // localisation coupée

    private val d = resources.displayMetrics.density
    private val t0 = SystemClock.uptimeMillis()
    private val gp = GeoPoint(0.0, 0.0)
    private val pt = Point()
    private val path = Path()

    private val accFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F7CC4FF }
    private val accStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x667CC4FF; style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }

    override fun onDraw(c: Canvas) {
        val m = Fog.me ?: return
        gp.setCoords(m[0], m[1])
        map.projection.toPixels(gp, pt)
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()
        if (x < -100 || y < -100 || x > width + 100 || y > height + 100) return

        val live = active && !paused
        val accent = if (live) 0xFF7CC4FF.toInt() else if (active) 0xFFFFB454.toInt() else 0xFF8F98AD.toInt()
        val deep = if (live) 0xFF2F7DE1.toInt() else if (active) 0xFFE08A1E.toInt() else 0xFF5A6478.toInt()

        // Zone d'incertitude GPS
        val accPx = map.projection.metersToPixels(m[2].toFloat())
        if (live && accPx > 16 * d) {
            c.drawCircle(x, y, accPx, accFill)
            c.drawCircle(x, y, accPx, accStroke)
        }

        // Faisceau dans la direction du déplacement
        val brg = if (m.size > 4) m[4] else -1.0
        val moving = live && brg >= 0
        if (moving) {
            val r = 56 * d
            beam.shader = RadialGradient(x, y, r, intArrayOf(0x887CC4FF.toInt(), 0x007CC4FF), null, Shader.TileMode.CLAMP)
            path.reset()
            path.moveTo(x, y)
            path.arcTo(x - r, y - r, x + r, y + r, (brg - 90 - 28).toFloat(), 56f, false)
            path.close()
            c.drawPath(path, beam)
        }

        // Onde qui pulse pendant l'exploration (2 ondes décalées)
        if (live) {
            val now = SystemClock.uptimeMillis() - t0
            for (k in 0..1) {
                val ph = ((now + k * 1000) % 2000) / 2000f
                pulse.color = accent
                pulse.alpha = ((1f - ph) * 150).toInt()
                c.drawCircle(x, y, (13 + 26 * ph) * d, pulse)
            }
            postInvalidateOnAnimation()
        }

        // Pastille
        c.drawCircle(x, y + 1.5f * d, 12.5f * d, shadow)
        c.drawCircle(x, y, 12f * d, ring)
        if (moving) {
            // Flèche de navigation orientée
            c.save()
            c.rotate(brg.toFloat(), x, y)
            core.shader = null
            core.color = deep
            path.reset()
            path.moveTo(x, y - 7.5f * d)
            path.lineTo(x + 6f * d, y + 6f * d)
            path.lineTo(x, y + 3f * d)
            path.lineTo(x - 6f * d, y + 6f * d)
            path.close()
            c.drawPath(path, core)
            c.restore()
        } else {
            core.shader = RadialGradient(x - 2.5f * d, y - 2.5f * d, 9f * d, intArrayOf(accent, deep), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, 8f * d, core)
            c.drawCircle(x - 2.6f * d, y - 2.6f * d, 2.2f * d, shine)
        }
    }
}
