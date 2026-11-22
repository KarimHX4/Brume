package fr.brume

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.abs

/**
 * Import de l'historique Google Maps (« Vos trajets » / Timeline) :
 *  - export Android récent (semanticSegments / timelinePath / rawSignals, points « 48.8°, 2.3° »),
 *  - export iPhone (« geo:48.8,2.3 »),
 *  - ancien Google Takeout (Records.json : latitudeE7 / longitudeE7).
 * Lecture en flux pour supporter des fichiers de plusieurs dizaines de Mo.
 */
object Timeline {

    private val pts = ArrayList<Pt>()
    private val num = Regex("-?\\d+(?:\\.\\d+)?")

    // Blocs ignorés : départ/arrivée d'un trajet (le tracé est ailleurs) et lieux fréquents sans date
    private val skip = setOf("activity", "activitySegment", "userLocationProfile", "frequentPlaces")
    private val timeKeys = setOf("time", "timestamp", "startTime", "timestampMs", "startTimestamp", "startTimestampMs")
    private val pointKeys = setOf("point", "latLng", "LatLng", "latlng", "placeLocation")

    private fun parseTime(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0L
        if (t.all { it.isDigit() }) return t.toLongOrNull() ?: 0L
        return try { Instant.parse(t).toEpochMilli() } catch (e: Exception) {
            try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (e2: Exception) { 0L }
        }
    }

    private fun parsePoint(s: String): DoubleArray? {
        val v = num.findAll(s).take(2).map { it.value.toDouble() }.toList()
        if (v.size < 2) return null
        val lat = v[0]; val lon = v[1]
        if (abs(lat) > 90 || abs(lon) > 180 || (lat == 0.0 && lon == 0.0)) return null
        return doubleArrayOf(lat, lon)
    }

    private fun readValue(r: JsonReader, inheritedT: Long) {
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(r, inheritedT)
            JsonToken.BEGIN_ARRAY -> { r.beginArray(); while (r.hasNext()) readValue(r, inheritedT); r.endArray() }
            else -> r.skipValue()
        }
    }

    private fun readObject(r: JsonReader, inheritedT: Long) {
        var t = inheritedT
        var p: DoubleArray? = null
        var e7lat = Double.NaN
        var e7lon = Double.NaN
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            val tok = r.peek()
            when {
                name in skip -> r.skipValue()
                name in timeKeys && (tok == JsonToken.STRING || tok == JsonToken.NUMBER) -> t = parseTime(r.nextString())
                name in pointKeys && tok == JsonToken.STRING -> p = parsePoint(r.nextString()) ?: p
                name == "latitudeE7" && tok == JsonToken.NUMBER -> e7lat = r.nextLong() / 1e7
                name == "longitudeE7" && tok == JsonToken.NUMBER -> e7lon = r.nextLong() / 1e7
                tok == JsonToken.BEGIN_OBJECT || tok == JsonToken.BEGIN_ARRAY -> readValue(r, t)
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (p == null && !e7lat.isNaN() && !e7lon.isNaN() && abs(e7lat) <= 90 && abs(e7lon) <= 180) p = doubleArrayOf(e7lat, e7lon)
        p?.let { pts.add(Pt(it[0], it[1], t)) }
    }

    /** Retourne le nombre de points importés. */
    fun load(input: InputStream): Int {
        pts.clear()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            readValue(r, 0L)
        }
        val list = pts.sortedBy { it.t }
        pts.clear()
        if (list.isEmpty()) return 0

        var prev: Pt? = null
        var lastGeo: Pt? = null
        var i = 0
        while (i < list.size) {
            val end = minOf(i + 2000, list.size)
            synchronized(Fog.lock) {
                val out = IntBuf()
                for (k in i until end) {
                    val p = list[k]
                    val q = prev
                    // Points espacés de quelques minutes : on relie si l'écart est plausible
                    // (≤ 10 min et ≤ 250 km/h), sinon simple disque autour du point
                    val link = q != null && p.t > 0 && q.t > 0 && run {
                        val dt = (p.t - q.t) / 1000.0
                        val d = Fog.dist(q.lat, q.lon, p.lat, p.lon)
                        if (dt <= 0) d < 50 else dt <= 600 && d / dt <= 70
                    }
                    if (link) Fog.markPath(q!!, p, out) else Fog.markDisc(p.lat, p.lon, out)
                    prev = p
                }
                Fog.countNew(out)
            }
            for (k in i until end) {
                val p = list[k]
                val lg = lastGeo
                if (lg == null || Fog.dist(lg.lat, lg.lon, p.lat, p.lon) > 700) { Geocoder.maybe(p.lat, p.lon, true); lastGeo = p }
            }
            Fog.notifyChanged()
            i = end
        }
        return list.size
    }
}
