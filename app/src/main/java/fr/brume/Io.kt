package fr.brume

import android.util.Base64
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime

/** Import de tracés GPX et sauvegarde/restauration JSON (même format que la version web). */
object Io {

    fun exportJson(): String {
        // Copie rapide sous verrou, puis encodage (lent) sans bloquer la carte
        val (chunks, cities) = synchronized(Fog.lock) {
            Fog.chunks.map { (k, b) -> k to b.copyOf() } to Fog.cities.values.toList()
        }
        val ch = JSONObject()
        for ((k, b) in chunks) ch.put("${Fog.kci(k)},${Fog.kcj(k)}", Base64.encodeToString(b, Base64.NO_WRAP))
        val cs = JSONArray()
        for (c in cities) cs.put(c.toJson())
        return JSONObject().put("app", "brume").put("v", 1).put("date", Instant.now().toString())
            .put("chunks", ch).put("cities", cs).toString()
    }

    fun importJson(text: String) {
        val o = JSONObject(text)
        require(o.optString("app") == "brume") { "format inconnu" }
        val ch = o.getJSONObject("chunks")
        val list = ArrayList<Pair<Long, ByteArray>>()
        for (k in ch.keys()) {
            val parts = k.split(",")
            if (parts.size != 2) continue
            list.add(Fog.key(parts[0].trim().toInt(), parts[1].trim().toInt()) to Base64.decode(ch.getString(k), Base64.DEFAULT))
        }
        Fog.mergeChunks(list)
        val cs = o.optJSONArray("cities")
        if (cs != null) {
            for (i in 0 until cs.length()) {
                try { Fog.addCity(City.fromJson(cs.getJSONObject(i)).also { it.n = 0 }) } catch (e: Exception) { e.printStackTrace() }
            }
        }
        Fog.recountAll()
        Fog.notifyChanged()
    }

    private fun parseTime(s: String): Long {
        val t = s.trim()
        return try { Instant.parse(t).toEpochMilli() } catch (e: Exception) {
            try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (e2: Exception) { 0L }
        }
    }

    /** Retourne le nombre de points importés. */
    fun importGpx(input: InputStream): Int {
        val xp = Xml.newPullParser()
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        xp.setInput(input, null)
        var prev: Pt? = null
        var lastGeo: Pt? = null
        val batch = ArrayList<Pt>()
        var total = 0
        var inPt = false
        var inTime = false
        var lat = Double.NaN
        var lon = Double.NaN
        var time = 0L

        fun flushBatch() {
            if (batch.isEmpty()) return
            synchronized(Fog.lock) {
                val out = IntBuf()
                for (p in batch) { Fog.addTrackPoint(prev, p, out); prev = p }
                Fog.countNew(out)
            }
            for (p in batch) {
                val lg = lastGeo
                if (lg == null || Fog.dist(lg.lat, lg.lon, p.lat, p.lon) > 700) {
                    Geocoder.maybe(p.lat, p.lon, true); lastGeo = p
                }
            }
            batch.clear()
            Fog.notifyChanged()
        }

        var ev = xp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val name = xp.name?.substringAfter(':')
            when (ev) {
                XmlPullParser.START_TAG -> when (name) {
                    "trkseg", "rte" -> { flushBatch(); prev = null }
                    "trkpt", "rtept" -> {
                        lat = xp.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                        lon = xp.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                        time = 0L; inPt = true
                    }
                    "time" -> if (inPt) inTime = true
                }
                XmlPullParser.TEXT -> if (inTime) time = parseTime(xp.text ?: "")
                XmlPullParser.END_TAG -> when (name) {
                    "time" -> inTime = false
                    "trkpt", "rtept" -> {
                        if (!lat.isNaN() && !lon.isNaN()) { batch.add(Pt(lat, lon, time)); total++ }
                        inPt = false
                        if (batch.size >= 100) flushBatch()
                    }
                }
            }
            ev = xp.next()
        }
        flushBatch()
        return total
    }
}
