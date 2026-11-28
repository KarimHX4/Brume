package fr.brume

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Trouve la commune d'un point et récupère ses limites via Nominatim (OpenStreetMap).
 * Une requête à la fois, 1,2 s d'écart minimum (règles d'usage de Nominatim).
 */
object Geocoder {
    private val exec = Executors.newSingleThreadExecutor()
    private val misses = ArrayList<DoubleArray>()   // points sans commune exploitable
    private val pending = ArrayList<DoubleArray>()
    private var lastQueued: DoubleArray? = null
    @Volatile var onNewCity: ((City) -> Unit)? = null
    @Volatile var onNewDistrict: ((City, City) -> Unit)? = null
    private val dMisses = ArrayList<DoubleArray>()
    private val dPending = ArrayList<DoubleArray>()

    private fun near(p: DoubleArray, lat: Double, lon: Double, m: Double) =
        Fog.dist(p[0], p[1], lat, lon) < m

    fun maybe(lat: Double, lon: Double, force: Boolean = false) {
        val p = doubleArrayOf(lat, lon)
        synchronized(Fog.lock) {
            val c = Fog.cityAt(lat, lon)
            if (c != null) {
                // Dans une commune connue : on cherche son arrondissement (Paris, Lyon, Marseille…)
                if (Fog.hasNoDistricts(c.id) || Fog.districtAt(c.id, lat, lon) != null) return
                if (dMisses.any { near(it, lat, lon, 300.0) } || dPending.any { near(it, lat, lon, 400.0) }) return
                dPending.add(p)
                exec.execute { districtWork(c, p) }
                return
            }
            if (misses.any { near(it, lat, lon, 800.0) }) return
            val lq = lastQueued
            if (!force && lq != null && near(lq, lat, lon, 300.0)) return
            if (pending.any { near(it, lat, lon, 500.0) }) return
            lastQueued = p
            pending.add(p)
        }
        exec.execute { work(p) }
    }

    fun clear() {
        synchronized(Fog.lock) { misses.clear(); pending.clear(); lastQueued = null; dMisses.clear(); dPending.clear() }
    }

    private fun work(p: DoubleArray) {
        try {
            synchronized(Fog.lock) {
                if (Fog.cityAt(p[0], p[1]) != null || misses.any { near(it, p[0], p[1], 800.0) }) return
            }
            var res: JSONObject? = null
            for (attempt in 0 until 4) {
                try { res = fetch(p[0], p[1]); break } catch (e: IOException) { Thread.sleep(15_000) }
            }
            val d = res ?: return
            val g = d.optJSONObject("geojson")
            val type = g?.optString("type")
            var found: City? = null
            if (g != null && (type == "Polygon" || type == "MultiPolygon")) {
                val id = d.optString("osm_type") + d.optLong("osm_id")
                val a = d.optJSONObject("address") ?: JSONObject()
                val name = listOf("city", "town", "village", "municipality")
                    .map { a.optString(it) }.firstOrNull { it.isNotEmpty() }
                    ?: d.optString("name").ifEmpty { "Lieu inconnu" }
                val region = a.optString("state").ifEmpty { a.optString("county") }
                val sub = listOf(region, a.optString("country")).filter { it.isNotEmpty() }.joinToString(", ")
                val c = City(id, name, sub, City.lighten(g), 0)
                if (Fog.addCity(c)) {
                    onNewCity?.invoke(c)
                    Fog.notifyChanged()
                }
                found = synchronized(Fog.lock) { Fog.cities[id] }
            }
            val fc = found
            synchronized(Fog.lock) {
                if (fc == null || !fc.contains(p[0], p[1])) misses.add(p)
                if (misses.size > 3000) misses.subList(0, 1000).clear()   // mémoire bornée
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            synchronized(Fog.lock) { pending.remove(p) }
            try { Thread.sleep(1200) } catch (e: InterruptedException) { }
        }
    }

    private fun districtWork(city: City, p: DoubleArray) {
        try {
            synchronized(Fog.lock) {
                if (Fog.hasNoDistricts(city.id) || Fog.districtAt(city.id, p[0], p[1]) != null) return
            }
            var res: JSONObject? = null
            for (attempt in 0 until 3) {
                try { res = fetch(p[0], p[1], 12); break } catch (e: IOException) { Thread.sleep(15_000) }
            }
            val d = res ?: return
            val id = d.optString("osm_type") + d.optLong("osm_id")
            val g = d.optJSONObject("geojson")
            val type = g?.optString("type")
            val kind = d.optString("addresstype")
            when {
                // Nominatim renvoie la commune elle-même : elle n'a pas d'arrondissements
                id == city.id -> Fog.setNoDistricts(city.id)
                g != null && (type == "Polygon" || type == "MultiPolygon") &&
                    (kind == "city_district" || kind == "borough" || d.optInt("place_rank") == 18) -> {
                    var name = d.optString("name").ifEmpty { "Arrondissement" }
                    if (name.startsWith(city.name + " ", ignoreCase = true)) name = name.substring(city.name.length + 1)
                    name = name.replaceFirstChar { it.uppercase() }
                    val dist = City(id, name, "", City.lighten(g), 0)
                    if (!dist.contains(p[0], p[1])) synchronized(Fog.lock) { dMisses.add(p) }
                    else if (Fog.addDistrict(city.id, dist)) { onNewDistrict?.invoke(city, dist); Fog.notifyChanged() }
                }
                else -> synchronized(Fog.lock) { dMisses.add(p) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            synchronized(Fog.lock) { dPending.remove(p) }
            try { Thread.sleep(1200) } catch (e: InterruptedException) { }
        }
    }

    private fun fetch(lat: Double, lon: Double, zoom: Int = 10): JSONObject {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=$zoom&polygon_geojson=1" +
                "&polygon_threshold=0.00005&accept-language=fr" +
                String.format(Locale.US, "&lat=%.6f&lon=%.6f", lat, lon)
        )
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Brume/1.0 (application Android personnelle)")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
