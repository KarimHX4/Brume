package fr.brume

import android.content.Context
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Tampon d'entiers extensible (paires i, j de cellules nouvellement dévoilées). */
class IntBuf {
    var a = IntArray(512)
    var n = 0
    fun add(x: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = x
    }
}

class Pt(val lat: Double, val lon: Double, val t: Long)

private class Batch(
    val chunks: List<Pair<Long, ByteArray>>,
    val newCities: List<City>,
    val counts: List<Pair<String, Long>>,
    val newDistricts: List<Pair<String, List<City>>>,
    val dCounts: List<Pair<String, Long>>
)

/**
 * Le monde est découpé en cellules d'environ 10 m × 10 m, regroupées en blocs de 64×64
 * stockés sous forme de bitmaps de 512 octets.
 */
object Fog {
    const val CELL = 10.0
    const val MPD = 111320.0          // mètres par degré de latitude
    const val DLAT = CELL / MPD
    const val CH = 64
    const val RAD = PI / 180.0

    val lock = Any()
    val chunks = HashMap<Long, ByteArray>()
    val cities = LinkedHashMap<String, City>()
    /** Arrondissements / quartiers par commune (liste vide = commune sans découpage). */
    val districts = HashMap<String, List<City>>()
    private val newDistricts = HashSet<String>()   // id de commune dont la liste est à enregistrer
    private val dirtyD = HashSet<String>()         // id de quartier dont le compte a changé
    private val dirty = HashSet<Long>()
    private val dirtyN = HashSet<String>()
    private val newCities = HashSet<String>()

    @Volatile var totalCells = 0L
        private set
    private var last: Pt? = null

    @Volatile var radius = 20.0
    @Volatile var maxAcc = 50f
    /** Opacité du brouillard en % (on doit encore deviner les routes dessous). */
    @Volatile var fogAlpha = 70
    /** Afficher les limites des communes découvertes. */
    @Volatile var showBorders = true
    /** Commune mise en avant après l'avoir touchée dans « Mes villes » (sinon : celle où l'on est). */
    @Volatile var focusId: String? = null
    /** Dernière position connue : lat, lon, précision (m), vitesse (m/s ou -1). */
    @Volatile var me: DoubleArray? = null

    private var store: Store? = null
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (store != null) return
            val p = ctx.getSharedPreferences("brume", Context.MODE_PRIVATE)
            prefsCtx = ctx.applicationContext
            noDistricts.addAll(p.getStringSet("noDistricts", emptySet()) ?: emptySet())
            radius = p.getInt("radius", 20).toDouble()
            maxAcc = p.getInt("maxAcc", 50).toFloat()
            fogAlpha = p.getInt("fogAlpha", 70)
            showBorders = p.getBoolean("borders", true)
            val s = Store(ctx.applicationContext)
            store = s
            try { s.load(chunks, cities, districts) } catch (e: Exception) { e.printStackTrace() }
            var n = 0L
            for (b in chunks.values) n += bitCount(b)
            totalCells = n
        }
        io.scheduleWithFixedDelay({ flush() }, 5, 5, TimeUnit.SECONDS)
    }

    fun savePrefs(ctx: Context) {
        ctx.getSharedPreferences("brume", Context.MODE_PRIVATE).edit()
            .putInt("radius", radius.toInt()).putInt("maxAcc", maxAcc.toInt()).putInt("fogAlpha", fogAlpha).putBoolean("borders", showBorders).apply()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { for (l in listeners) try { l() } catch (e: Exception) { e.printStackTrace() } }

    // ---------- Grille ----------
    fun rowOf(lat: Double) = floor(lat / DLAT).toInt()
    fun rowLat(i: Int) = (i + 0.5) * DLAT
    fun dLon(i: Int) = CELL / (MPD * max(cos(rowLat(i) * RAD), 0.02))
    fun key(ci: Int, cj: Int): Long = (ci.toLong() shl 32) or (cj.toLong() and 0xffffffffL)
    fun kci(k: Long): Int = (k shr 32).toInt()
    fun kcj(k: Long): Int = k.toInt()

    fun bitCount(b: ByteArray): Int {
        var n = 0
        for (x in b) n += Integer.bitCount(x.toInt() and 0xff)
        return n
    }

    inline fun forBits(b: ByteArray, f: (Int, Int) -> Unit) {
        for (p in b.indices) {
            val v = b[p].toInt() and 0xff
            if (v == 0) continue
            for (q in 0 until 8) {
                if (((v shr q) and 1) == 1) {
                    val idx = (p shl 3) or q
                    f(idx shr 6, idx and 63)
                }
            }
        }
    }

    fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val x = (bLon - aLon) * cos((aLat + bLat) / 2 * RAD)
        val y = bLat - aLat
        return sqrt(x * x + y * y) * MPD
    }

    private fun setCell(i: Int, j: Int, out: IntBuf) {
        val ci = Math.floorDiv(i, CH)
        val cj = Math.floorDiv(j, CH)
        val k = key(ci, cj)
        val b = chunks.getOrPut(k) { ByteArray(CH * CH / 8) }
        val idx = (i - ci * CH) * CH + (j - cj * CH)
        val p = idx shr 3
        val m = 1 shl (idx and 7)
        if ((b[p].toInt() and m) != 0) return
        b[p] = (b[p].toInt() or m).toByte()
        dirty.add(k)
        totalCells++
        out.add(i); out.add(j)
    }

    /** Dévoile un disque de rayon [radius] autour du point. À appeler sous [lock]. */
    internal fun markDisc(lat: Double, lon: Double, out: IntBuf) {
        val r = radius
        val i0 = rowOf(lat)
        val k = ceil(r / CELL).toInt() + 1
        for (di in -k..k) {
            val i = i0 + di
            val dy = (rowLat(i) - lat) * MPD
            if (abs(dy) > r) continue
            val half = sqrt(r * r - dy * dy)
            val dl = dLon(i)
            val hd = half / CELL * dl
            val j0 = ceil((lon - hd) / dl - 0.5).toInt()
            val j1 = floor((lon + hd) / dl - 0.5).toInt()
            for (j in j0..j1) setCell(i, j, out)
        }
    }

    /** Dévoile une bande continue entre deux points (pas de 5 m) : aucune limite de vitesse. */
    internal fun markPath(a: Pt, b: Pt, out: IntBuf) {
        val n = max(1, ceil(dist(a.lat, a.lon, b.lat, b.lon) / 5.0).toInt())
        for (s in 1..n) {
            val t = s.toDouble() / n
            markDisc(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, out)
        }
    }

    /** Position GPS en direct. On relie au point précédent si le flux est continu (< 60 s). */
    fun addFix(lat: Double, lon: Double, t: Long) {
        synchronized(lock) {
            val p = Pt(lat, lon, t)
            val out = IntBuf()
            val l = last
            if (l != null && t - l.t in 0..60_000L) markPath(l, p, out) else markDisc(lat, lon, out)
            last = p
            countNew(out)
        }
    }

    fun breakPath() { synchronized(lock) { last = null } }

    /** Point d'un tracé importé. À appeler sous [lock]. */
    internal fun addTrackPoint(prev: Pt?, p: Pt, out: IntBuf) {
        if (prev != null) {
            val cont = if (prev.t > 0 && p.t > 0) p.t - prev.t in 0..120_000L
                       else dist(prev.lat, prev.lon, p.lat, p.lon) < 2000
            if (cont) { markPath(prev, p, out); return }
        }
        markDisc(p.lat, p.lon, out)
    }

    // ---------- Villes ----------
    internal fun countNew(out: IntBuf) {
        if (out.n == 0 || cities.isEmpty()) return
        val m = out.n / 2
        val la = DoubleArray(m)
        val lo = DoubleArray(m)
        var a0 = 90.0; var a1 = -90.0; var o0 = 180.0; var o1 = -180.0
        for (q in 0 until m) {
            val i = out.a[2 * q]
            val j = out.a[2 * q + 1]
            la[q] = rowLat(i); lo[q] = (j + 0.5) * dLon(i)
            if (la[q] < a0) a0 = la[q]
            if (la[q] > a1) a1 = la[q]
            if (lo[q] < o0) o0 = lo[q]
            if (lo[q] > o1) o1 = lo[q]
        }
        for (c in cities.values) {
            val b = c.bbox
            if (o1 < b[0] || o0 > b[2] || a1 < b[1] || a0 > b[3]) continue
            var n = 0
            for (q in 0 until m) if (c.contains(la[q], lo[q])) n++
            if (n > 0) { c.n += n; dirtyN.add(c.id) }
            if (n == 0) continue
            val ds = districts[c.id] ?: continue
            for (d in ds) {
                val db = d.bbox
                if (o1 < db[0] || o0 > db[2] || a1 < db[1] || a0 > db[3]) continue
                var nd = 0
                for (q in 0 until m) if (d.contains(la[q], lo[q])) nd++
                if (nd > 0) { d.n += nd; dirtyD.add(d.id) }
            }
        }
    }

    fun districtAt(cityId: String, lat: Double, lon: Double): City? = synchronized(lock) {
        districts[cityId]?.firstOrNull { it.contains(lat, lon) }
    }

    fun findArea(id: String): City? = synchronized(lock) {
        cities[id] ?: districts.values.asSequence().flatten().firstOrNull { it.id == id }
    }

    /** Communes vérifiées sans arrondissements (Nominatim renvoie la commune elle-même). */
    private val noDistricts = HashSet<String>()
    private var prefsCtx: Context? = null

    fun hasNoDistricts(cityId: String) = synchronized(lock) { cityId in noDistricts }

    fun setNoDistricts(cityId: String) {
        val set = synchronized(lock) { noDistricts.add(cityId); HashSet(noDistricts) }
        prefsCtx?.getSharedPreferences("brume", Context.MODE_PRIVATE)?.edit()?.putStringSet("noDistricts", set)?.apply()
    }

    /** Ajoute un arrondissement visité à sa commune (tri naturel : 2e avant 10e). */
    fun addDistrict(cityId: String, d: City): Boolean {
        synchronized(lock) {
            val cur = districts[cityId] ?: emptyList()
            if (cur.any { it.id == d.id }) return false
            d.n = countIn(d)
            districts[cityId] = (cur + d).sortedWith(naturalOrder)
            newDistricts.add(cityId)
        }
        return true
    }

    val naturalOrder = Comparator<City> { a, b ->
        val na = Regex("\\d+").find(a.name)?.value?.toIntOrNull()
        val nb = Regex("\\d+").find(b.name)?.value?.toIntOrNull()
        if (na != null && nb != null && na != nb) na.compareTo(nb) else a.name.compareTo(b.name, ignoreCase = true)
    }

    /** Arrondissements où l'on est passé, pour une commune. */
    fun visitedDistricts(cityId: String): List<City> = synchronized(lock) { districts[cityId]?.filter { it.n > 0 } ?: emptyList() }

    fun cityAt(lat: Double, lon: Double): City? = synchronized(lock) {
        cities.values.firstOrNull { it.contains(lat, lon) }
    }

    private fun recount(c: City) {
        c.n = countIn(c)
        dirtyN.add(c.id)
        districts[c.id]?.forEach { d -> d.n = countIn(d); dirtyD.add(d.id) }
    }

    private fun countIn(c: City): Long {
        val b = c.bbox
        var n = 0L
        for ((k, bits) in chunks) {
            val i0 = kci(k) * CH
            val cj = kcj(k)
            val lat0 = i0 * DLAT
            val lat1 = (i0 + CH) * DLAT
            if (lat1 < b[1] || lat0 > b[3]) continue
            val dl = dLon(i0 + CH / 2)
            val lonA = cj * CH * dl * 0.97
            val lonB = (cj + 1) * CH * dl * 1.03
            if (max(lonA, lonB) < b[0] || minOf(lonA, lonB) > b[2]) continue
            forBits(bits) { li, lj ->
                val i = i0 + li
                if (c.contains(rowLat(i), (cj * CH + lj + 0.5) * dLon(i))) n++
            }
        }
        return n
    }

    fun recountAll() { synchronized(lock) { for (c in cities.values) recount(c) } }

    fun addCity(c: City): Boolean {
        synchronized(lock) {
            if (cities.containsKey(c.id)) return false
            cities[c.id] = c
            recount(c)
            newCities.add(c.id)
        }
        return true
    }

    fun mergeChunks(list: List<Pair<Long, ByteArray>>) {
        synchronized(lock) {
            for ((k, u) in list) {
                if (u.size != CH * CH / 8) continue
                val b = chunks[k]
                if (b == null) {
                    chunks[k] = u.copyOf(); totalCells += bitCount(u)
                } else {
                    for (p in b.indices) {
                        val add = u[p].toInt() and b[p].toInt().inv() and 0xff
                        if (add != 0) { b[p] = (b[p].toInt() or add).toByte(); totalCells += Integer.bitCount(add) }
                    }
                }
                dirty.add(k)
            }
        }
    }

    // ---------- Stockage ----------
    fun flush() {
        val s = store ?: return
        val batch = synchronized(lock) {
            if (dirty.isEmpty() && dirtyN.isEmpty() && newCities.isEmpty() && newDistricts.isEmpty() && dirtyD.isEmpty()) null
            else {
                val b = Batch(
                    dirty.mapNotNull { k -> chunks[k]?.let { k to it.copyOf() } },
                    newCities.mapNotNull { cities[it] },
                    dirtyN.mapNotNull { id -> cities[id]?.let { id to it.n } },
                    newDistricts.mapNotNull { id -> districts[id]?.let { id to it } },
                    districts.values.asSequence().flatten().filter { it.id in dirtyD }.map { it.id to it.n }.toList()
                )
                dirty.clear(); newCities.clear(); dirtyN.clear(); newDistricts.clear(); dirtyD.clear()
                b
            }
        } ?: return
        try { s.save(batch.chunks, batch.newCities, batch.counts, batch.newDistricts, batch.dCounts) } catch (e: Exception) { e.printStackTrace() }
    }

    fun flushAsync() { io.execute { flush() } }

    fun reset() {
        synchronized(lock) {
            chunks.clear(); cities.clear(); dirty.clear(); dirtyN.clear(); newCities.clear()
            districts.clear(); newDistricts.clear(); dirtyD.clear(); noDistricts.clear()
            totalCells = 0; last = null
        }
        prefsCtx?.getSharedPreferences("brume", Context.MODE_PRIVATE)?.edit()?.remove("noDistricts")?.apply()
        Geocoder.clear()
        io.execute { try { store?.clearAll() } catch (e: Exception) { e.printStackTrace() } }
        notifyChanged()
    }

    // ---------- Affichage ----------
    fun fmtArea(m2: Double): String =
        if (m2 >= 1e6) String.format(Locale.FRANCE, if (m2 >= 1e8) "%.0f km²" else "%.2f km²", m2 / 1e6)
        else String.format(Locale.FRANCE, "%,.0f m²", m2)

    fun fmtPct(v: Double): String =
        String.format(Locale.FRANCE, when { v >= 10 -> "%.1f"; v >= 1 -> "%.2f"; else -> "%.3f" }, v) + " %"

    fun exploredArea() = totalCells * CELL * CELL

    fun summary(): String {
        val area = fmtArea(exploredArea())
        val m = me
        val c = if (m != null) cityAt(m[0], m[1]) else null
        return if (c != null) "$area dévoilés · ${c.name} ${fmtPct(c.pct())}" else "$area dévoilés"
    }
}
