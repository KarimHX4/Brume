package fr.brume

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Une commune avec ses limites officielles (GeoJSON OpenStreetMap). */
class City(val id: String, val name: String, val sub: String, val geo: JSONObject, var n: Long) {
    /** Polygones → anneaux (le premier est l'extérieur, les suivants des trous) → [x0, y0, x1, y1, …]. */
    val polys = ArrayList<List<DoubleArray>>()
    /** minLon, minLat, maxLon, maxLat */
    val bbox = doubleArrayOf(180.0, 90.0, -180.0, -90.0)
    val area: Double

    init {
        val coords = geo.getJSONArray("coordinates")
        val parsed = if (geo.getString("type") == "Polygon") listOf(parsePoly(coords))
                     else (0 until coords.length()).map { parsePoly(coords.getJSONArray(it)) }
        // Contours vides ou dégénérés ignorés (sinon plantage à l'accès poly[0])
        for (p in parsed) if (p.isNotEmpty()) polys.add(p)
        require(polys.isNotEmpty()) { "contour vide" }
        for (poly in polys) {
            val r = poly[0]
            var q = 0
            while (q + 1 < r.size) {
                bbox[0] = min(bbox[0], r[q]); bbox[1] = min(bbox[1], r[q + 1])
                bbox[2] = max(bbox[2], r[q]); bbox[3] = max(bbox[3], r[q + 1])
                q += 2
            }
        }
        val lat0 = (bbox[1] + bbox[3]) / 2
        var a = 0.0
        for (poly in polys) poly.forEachIndexed { k, r -> a += (if (k == 0) 1.0 else -1.0) * ringArea(r, lat0) }
        area = a
    }

    /** Anneaux d'un polygone ; un extérieur invalide (< 3 points) invalide tout le polygone. */
    private fun parsePoly(arr: JSONArray): List<DoubleArray> {
        val rings = ArrayList<DoubleArray>()
        for (ri in 0 until arr.length()) {
            val ring = arr.optJSONArray(ri) ?: continue
            // Très gros contours : on ne garde qu'un point sur k (calculs et dessin plus légers)
            val step = maxOf(1, ring.length() / MAX_RING)
            val d = ArrayList<Double>(ring.length() / step * 2 + 2)
            var p = 0
            while (p < ring.length()) {
                val pt = ring.optJSONArray(p)
                if (pt != null && pt.length() >= 2) {
                    val x = pt.optDouble(0); val y = pt.optDouble(1)
                    if (!x.isNaN() && !y.isNaN()) { d.add(x); d.add(y) }
                }
                p += step
            }
            if (d.size < 6) { if (ri == 0) return emptyList() else continue }
            rings.add(d.toDoubleArray())
        }
        return rings
    }

    private fun ringArea(r: DoubleArray, lat0: Double): Double {
        val c = cos(lat0 * Fog.RAD)
        val m = r.size / 2
        var s = 0.0
        var j = m - 1
        for (i in 0 until m) {
            s += (r[2 * j] * c) * r[2 * i + 1] - (r[2 * i] * c) * r[2 * j + 1]
            j = i
        }
        return abs(s / 2) * Fog.MPD * Fog.MPD
    }

    private fun inRing(x: Double, y: Double, r: DoubleArray): Boolean {
        val m = r.size / 2
        var inside = false
        var j = m - 1
        for (i in 0 until m) {
            val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun contains(lat: Double, lon: Double): Boolean {
        if (lon < bbox[0] || lon > bbox[2] || lat < bbox[1] || lat > bbox[3]) return false
        for (poly in polys) {
            if (!inRing(lon, lat, poly[0])) continue
            var hole = false
            for (k in 1 until poly.size) if (inRing(lon, lat, poly[k])) { hole = true; break }
            if (!hole) return true
        }
        return false
    }

    fun pct(): Double = if (area > 0) min(100.0, n * Fog.CELL * Fog.CELL / area * 100.0) else 0.0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("sub", sub).put("geo", geo).put("n", n).put("area", area)

    companion object {
        private const val MAX_RING = 5000

        /** Allège un GeoJSON trop détaillé avant de le garder (limite de taille SQLite, mémoire). */
        fun lighten(geo: JSONObject, maxPoints: Int = 30000): JSONObject {
            return try {
                val coords = geo.getJSONArray("coordinates")
                val polys = if (geo.getString("type") == "Polygon") listOf(coords) else (0 until coords.length()).map { coords.getJSONArray(it) }
                var total = 0
                for (poly in polys) for (r in 0 until poly.length()) total += poly.getJSONArray(r).length()
                if (total <= maxPoints) return geo
                val step = (total + maxPoints - 1) / maxPoints
                fun thin(ring: JSONArray): JSONArray {
                    val out = JSONArray()
                    var i = 0
                    while (i < ring.length()) { out.put(ring.get(i)); i += step }
                    if (ring.length() > 0) out.put(ring.get(0))   // anneau refermé
                    return out
                }
                fun thinPoly(poly: JSONArray): JSONArray { val o = JSONArray(); for (r in 0 until poly.length()) o.put(thin(poly.getJSONArray(r))); return o }
                val newCoords = if (geo.getString("type") == "Polygon") thinPoly(coords)
                                else JSONArray().also { a -> for (p in polys) a.put(thinPoly(p)) }
                JSONObject().put("type", geo.getString("type")).put("coordinates", newCoords)
            } catch (e: Exception) { geo }
        }

        fun fromJson(o: JSONObject) = City(
            o.getString("id"), o.optString("name", "?"), o.optString("sub", ""),
            o.getJSONObject("geo"), o.optLong("n", 0L)
        )
    }
}
