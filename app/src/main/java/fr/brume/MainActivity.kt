package fr.brume

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.DashPathEffect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var stat: TextView
    private lateinit var gps: TextView
    private lateinit var bTrack: Button
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: LinearLayout
    private var follow = true
    private var centeredOnce = false
    private var panelMode = ""
    private val outlines = ArrayList<Polyline>()

    private val white = 0xFFEEF1F7.toInt()
    private val muted = 0xFF8F98AD.toInt()
    private val accent = 0xFF7CC4FF.toInt()
    private val green = 0xFF5FD39A.toInt()
    private val danger = 0xFFFF7A7A.toInt()

    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_OPEN = 10
        private const val REQ_SAVE = 11
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "Brume/1.0 (fr.brume)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        Fog.init(this)
        goEdgeToEdge()
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        stat = findViewById(R.id.stat)
        gps = findViewById(R.id.gps)
        bTrack = findViewById(R.id.bTrack)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        panelBody = findViewById(R.id.panelBody)
        findViewById<ScrollView>(R.id.panelScroll).layoutParams.height =
            (resources.displayMetrics.heightPixels * 0.5).toInt()

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setTilesScaledToDpi(true)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isHorizontalMapRepetitionEnabled = false
        map.isVerticalMapRepetitionEnabled = false
        map.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 19.0
        map.overlays.add(FogOverlay())
        @Suppress("ClickableViewAccessibility")
        map.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_MOVE) follow = false
            false
        }
        val prefs = getSharedPreferences("brume", MODE_PRIVATE)
        map.controller.setZoom(prefs.getFloat("z", 5f).toDouble())
        map.controller.setCenter(GeoPoint(prefs.getFloat("lat", 46.6f).toDouble(), prefs.getFloat("lon", 2.4f).toDouble()))
        seedLastKnown()

        applyInsets()

        bTrack.setOnClickListener { if (TrackingService.running) TrackingService.stop(this) else startTracking() }
        findViewById<Button>(R.id.bCenter).setOnClickListener {
            follow = true
            clearOutline()
            val m = Fog.me
            if (m != null) zoomAround(m[0], m[1], true)
            else if (!TrackingService.running) startTracking()
        }
        findViewById<Button>(R.id.bCities).setOnClickListener { showPanel("cities") }
        findViewById<Button>(R.id.bSettings).setOnClickListener { showPanel("settings") }
        findViewById<View>(R.id.panelClose).setOnClickListener { panel.visibility = View.GONE }

        Geocoder.onNewCity = { c -> runOnUiThread { toast("Nouvelle ville : ${c.name}") } }

        // Reprend l'exploration si elle tournait quand l'app a été fermée par le système
        if (prefs.getBoolean("tracking", false) && !TrackingService.running && hasLoc()) TrackingService.start(this)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        Fog.addListener(listener)
        refresh()
    }

    override fun onPause() {
        map.onPause()
        Fog.removeListener(listener)
        val c = map.mapCenter
        getSharedPreferences("brume", MODE_PRIVATE).edit()
            .putFloat("lat", c.latitude.toFloat()).putFloat("lon", c.longitude.toFloat())
            .putFloat("z", map.zoomLevelDouble.toFloat()).apply()
        Fog.flushAsync()
        super.onPause()
    }

    // ---------- Plein écran sous les barres système ----------
    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    @Suppress("DEPRECATION")
    private fun applyInsets() {
        val top = findViewById<View>(R.id.topBar)
        val bottom = findViewById<View>(R.id.bottomBar)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, ins ->
            val t = ins.systemWindowInsetTop
            val b = ins.systemWindowInsetBottom
            (top.layoutParams as FrameLayout.LayoutParams).topMargin = t + dp(8)
            (bottom.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + dp(16)
            top.requestLayout(); bottom.requestLayout()
            panel.setPadding(dp(16), dp(12), dp(16), b + dp(16))
            ins
        }
    }

    // ---------- Suivi ----------
    private fun hasLoc() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startTracking() {
        val need = ArrayList<String>()
        if (!hasLoc()) { need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) launchService() else requestPermissions(need.toTypedArray(), REQ_PERM)
    }

    private fun launchService() {
        follow = true
        centeredOnce = false
        seedLastKnown()
        TrackingService.start(this)
        val prefs = getSharedPreferences("brume", MODE_PRIVATE)
        if (!prefs.getBoolean("askedBattery", false) && !ignoringBattery()) {
            prefs.edit().putBoolean("askedBattery", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Exploration écran verrouillé")
                .setMessage("Pour qu'Android ne coupe jamais Brume en arrière-plan (surtout sur Samsung, Xiaomi, Huawei…), autorise-la à ignorer l'optimisation de batterie.")
                .setPositiveButton("Autoriser") { _, _ -> askBattery() }
                .setNegativeButton("Plus tard", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        when {
            hasLoc() -> launchService()
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
                toast("Brume a besoin de la position « précise »")
            else -> toast("Permission de localisation refusée")
        }
    }

    private fun ignoringBattery() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        if (ignoringBattery()) { toast("C'est déjà bon ✓"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    // ---------- Rafraîchissement ----------
    private fun refresh() {
        map.invalidate()
        val n = Fog.cities.size
        stat.text = "Brume · ${Fog.fmtArea(Fog.exploredArea())} · $n ville${if (n > 1) "s" else ""}"
        val m = Fog.me
        val on = TrackingService.running
        gps.text = when {
            !on -> "GPS éteint"
            m == null -> "Recherche GPS…"
            else -> buildString {
                append("±${m[2].roundToInt()} m")
                if (m[3] >= 0) append(" · ${(m[3] * 3.6).roundToInt()} km/h")
                if (m[2] > Fog.maxAcc) append(" (ignoré)")
            }
        }
        gps.setTextColor(if (on && m != null && m[2] <= Fog.maxAcc) green else muted)
        bTrack.text = if (on) "⏸ En cours" else "▶ Explorer"
        bTrack.backgroundTintList = ColorStateList.valueOf(if (on) green else accent)
        if (m != null && !centeredOnce) {
            centeredOnce = true
            zoomAround(m[0], m[1], false)
        } else if (follow && on && m != null) {
            map.controller.setCenter(GeoPoint(m[0], m[1]))
        }
    }

    /** Centre la carte sur le point avec environ 130 m visibles de chaque côté. */
    private fun zoomAround(lat: Double, lon: Double, animated: Boolean) {
        val dLat = 130.0 / Fog.MPD
        val dLon = dLat / Math.cos(lat * Fog.RAD)
        val box = BoundingBox(lat + dLat, lon + dLon, lat - dLat, lon - dLon)
        if (map.width > 0) map.zoomToBoundingBox(box, animated, 0)
        else map.post { map.zoomToBoundingBox(box, animated, 0) }
    }

    /** Dernière position connue du téléphone, pour centrer la carte dès l'ouverture. */
    @Suppress("MissingPermission")
    private fun seedLastKnown() {
        if (Fog.me != null || !hasLoc()) return
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val best = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return
        Fog.me = doubleArrayOf(best.latitude, best.longitude, if (best.hasAccuracy()) best.accuracy.toDouble() else 99.0, -1.0)
    }

    // ---------- Panneaux ----------
    private fun showPanel(mode: String) {
        if (panel.visibility == View.VISIBLE && panelMode == mode) { panel.visibility = View.GONE; return }
        panelMode = mode
        panel.visibility = View.VISIBLE
        rebuildPanel()
    }

    private fun rebuildPanel() {
        panelBody.removeAllViews()
        if (panelMode == "cities") buildCities() else buildSettings()
    }

    private fun buildCities() {
        panelTitle.text = "Villes découvertes"
        val list = synchronized(Fog.lock) { Fog.cities.values.toList() }.sortedByDescending { it.pct() }
        if (list.isEmpty()) {
            panelBody.addView(text("Aucune ville pour l'instant. Lance l'exploration !", 14f, muted).apply { setPadding(0, dp(20), 0, dp(20)) })
            return
        }
        for (c in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setOnClickListener { showCity(c) }
            }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(text(c.name, 16f, white, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(text(Fog.fmtPct(c.pct()), 16f, white, true))
            row.addView(line)
            row.addView(text("${c.sub} · ${Fog.fmtArea(c.n * Fog.CELL * Fog.CELL)} / ${Fog.fmtArea(c.area)}", 12f, muted))
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (c.pct() * 10).roundToInt().coerceAtLeast(5)
                progressTintList = ColorStateList.valueOf(accent)
            }
            row.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
            panelBody.addView(row)
        }
        panelBody.addView(text("% = surface dévoilée dans les limites officielles de la commune (OpenStreetMap) ÷ surface de la commune.", 12f, muted)
            .apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun showCity(c: City) {
        follow = false
        clearOutline()
        for (poly in c.polys) {
            val r = poly[0]
            val pl = Polyline()
            pl.setPoints((0 until r.size / 2).map { GeoPoint(r[2 * it + 1], r[2 * it]) })
            pl.outlinePaint.color = accent
            pl.outlinePaint.strokeWidth = dp(2).toFloat()
            pl.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
            map.overlays.add(pl)
            outlines.add(pl)
        }
        map.zoomToBoundingBox(BoundingBox(c.bbox[3], c.bbox[2], c.bbox[1], c.bbox[0]), true, dp(40))
        panel.visibility = View.GONE
    }

    private fun clearOutline() {
        for (pl in outlines) map.overlays.remove(pl)
        outlines.clear()
        map.invalidate()
    }

    private fun buildSettings() {
        panelTitle.text = "Réglages"
        settingRow("Rayon de découverte : ${Fog.radius.toInt()} m", "Touche pour changer · s'applique aux prochains trajets") {
            val opts = listOf(15, 20, 25, 30)
            Fog.radius = opts[(opts.indexOf(Fog.radius.toInt()) + 1) % opts.size].toDouble()
            Fog.savePrefs(this); rebuildPanel()
        }
        settingRow("Précision GPS max : ±${Fog.maxAcc.toInt()} m", "Touche pour changer · les positions moins précises sont ignorées") {
            val opts = listOf(20, 30, 40, 50, 60, 100)
            Fog.maxAcc = opts[(opts.indexOf(Fog.maxAcc.toInt()) + 1) % opts.size].toFloat()
            Fog.savePrefs(this); rebuildPanel()
        }
        settingRow("Batterie : ne pas restreindre Brume",
            if (ignoringBattery()) "✓ Autorisé : Android ne coupera pas l'exploration" else "Recommandé pour l'exploration écran verrouillé") {
            askBattery()
        }
        settingRow("Importer un tracé", "GPX (Strava, Garmin, Komoot…) ou sauvegarde Brume .json (y compris de la version web)") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(i, REQ_OPEN)
        }
        settingRow("Exporter mes données", "Fichier JSON à garder en lieu sûr") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "brume-${LocalDate.now()}.json")
            startActivityForResult(i, REQ_SAVE)
        }
        settingRow("Tout effacer", "Carte découverte et villes", danger) {
            AlertDialog.Builder(this).setTitle("Tout effacer ?")
                .setMessage("La carte découverte et toutes les villes seront supprimées.")
                .setPositiveButton("Effacer") { _, _ -> Fog.reset(); rebuildPanel(); toast("Tout est effacé") }
                .setNegativeButton("Annuler", null).show()
        }
        panelBody.addView(text("${String.format("%,d", Fog.totalCells)} cellules de 10 m · ${Fog.chunks.size} blocs", 12f, muted)
            .apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun settingRow(title: String, sub: String, color: Int = white, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(text(title, 15f, color, true))
        row.addView(text(sub, 12f, muted))
        panelBody.addView(row)
    }

    // ---------- Import / export ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_OPEN -> {
                toast("Import en cours…")
                Thread {
                    try {
                        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        val txt = String(bytes, 0, min(bytes.size, 256)).trimStart('﻿', ' ', '\n', '\r', '\t')
                        if (txt.startsWith("{")) {
                            Io.importJson(String(bytes).trimStart('﻿'))
                            toast("Sauvegarde restaurée")
                        } else {
                            val n = Io.importGpx(ByteArrayInputStream(bytes))
                            toast(if (n > 0) "$n points importés" else "Aucun point trouvé dans ce fichier")
                        }
                        Fog.flush()
                    } catch (e: Exception) {
                        e.printStackTrace(); toast("Fichier non reconnu")
                    }
                }.start()
            }
            REQ_SAVE -> Thread {
                try {
                    Fog.flush()
                    val s = Io.exportJson()
                    contentResolver.openOutputStream(uri)!!.use { it.write(s.toByteArray()) }
                    toast("Sauvegarde exportée")
                } catch (e: Exception) {
                    e.printStackTrace(); toast("Échec de l'export")
                }
            }.start()
        }
    }

    // ---------- Utilitaires ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
