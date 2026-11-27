package fr.brume

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Découpage d'une commune en arrondissements (Paris, Lyon, Marseille…) ou en quartiers administratifs.
 *  1. Overpass liste les limites administratives de niveau 9 (sinon 10) contenues dans la commune ;
 *  2. Nominatim renvoie leurs contours, 40 par requête.
 */
object Districts {
    private val exec = Executors.newSingleThreadExecutor()
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    /** Chargement automatique (ville actuelle) : pas de nouvel essai pendant 10 min après un échec. */
    fun autoLoad(city: City) {
        if (has(city.id) || isLoading(city.id)) return
        val f = synchronized(pending) { failedAt[city.id] }
        if (f != null && System.currentTimeMillis() - f < 600_000) return
        load(city) { }
    }
    private const val UA = "Brume/1.0 (application Android personnelle)"

    fun has(cityId: String) = synchronized(Fog.lock) { Fog.districts.containsKey(cityId) }
    fun isLoading(cityId: String) = synchronized(pending) { cityId in pending }

    /** Charge le découpage si besoin ; [done] reçoit false en cas d'échec réseau. */
    fun load(city: City, done: (Boolean) -> Unit) {
        if (has(city.id)) { done(true); return }
        synchronized(pending) { if (!pending.add(city.id)) return }
        exec.execute {
            var ok = false
            try {
                Fog.addDistricts(city.id, fetch(city))
                ok = true
            } catch (e: Exception) {
                e.printStackTrace()
                synchronized(pending) { failedAt[city.id] = System.currentTimeMillis() }
            } finally {
                synchronized(pending) { pending.remove(city.id) }
                done(ok)
            }
        }
    }

    private fun get(url: String, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15_000
        c.readTimeout = timeout
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun fetch(city: City): List<City> {
        if (!city.id.startsWith("relation")) return emptyList()
        val rel = city.id.removePrefix("relation")
        val q = "[out:json][timeout:40];rel($rel);map_to_area->.a;" +
            "rel(area.a)[\"boundary\"=\"administrative\"][\"admin_level\"~\"^(9|10)$\"];out tags;"
        val js = JSONObject(get("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"), 60_000))
        val els = js.optJSONArray("elements") ?: JSONArray()
        val l9 = ArrayList<Long>(); val l10 = ArrayList<Long>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            when (e.optJSONObject("tags")?.optString("admin_level")) { "9" -> l9.add(e.getLong("id")); "10" -> l10.add(e.getLong("id")) }
        }
        var result = lookup(city, if (l9.size >= 2) l9 else l10)
        if (result.size < 2 && l9.size >= 2 && l10.size >= 2) result = lookup(city, l10)
        return if (result.size >= 2) result.sortedWith(naturalOrder) else emptyList()
    }

    private fun lookup(city: City, ids: List<Long>): List<City> {
        val out = ArrayList<City>()
        for (part in ids.take(120).chunked(40)) {
            Thread.sleep(1100) // règles d'usage Nominatim
            val url = "https://nominatim.openstreetmap.org/lookup?format=jsonv2&polygon_geojson=1&polygon_threshold=0.00002" +
                "&accept-language=fr&osm_ids=" + part.joinToString(",") { "R$it" }
            val arr = JSONArray(get(url, 60_000))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val g = o.optJSONObject("geojson") ?: continue
                val type = g.optString("type")
                if (type != "Polygon" && type != "MultiPolygon") continue
                // On garde seulement les quartiers dont le centre est dans la commune (pas les voisins)
                val lat = o.optString("lat").toDoubleOrNull() ?: continue
                val lon = o.optString("lon").toDoubleOrNull() ?: continue
                if (!city.contains(lat, lon)) continue
                var name = o.optString("name").ifEmpty { o.optString("display_name").substringBefore(",") }
                if (name.startsWith(city.name + " ", ignoreCase = true)) name = name.substring(city.name.length + 1)
                name = name.replaceFirstChar { it.uppercase() }
                out.add(City("relation" + o.optLong("osm_id"), name, "", g, 0))
            }
        }
        return out
    }

    /** Tri « naturel » : 2e avant 10e. */
    private val naturalOrder = Comparator<City> { a, b ->
        val na = Regex("\\d+").find(a.name)?.value?.toIntOrNull()
        val nb = Regex("\\d+").find(b.name)?.value?.toIntOrNull()
        if (na != null && nb != null && na != nb) na.compareTo(nb) else a.name.compareTo(b.name, ignoreCase = true)
    }

    fun label(list: List<City>?): String = when {
        list == null -> "Arrondissements et quartiers"
        list.any { it.name.contains("rrondissement", ignoreCase = true) } -> "Arrondissements"
        else -> "Quartiers"
    }
}
