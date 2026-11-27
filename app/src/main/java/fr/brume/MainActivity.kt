package fr.brume

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.DashPathEffect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var cityLabel: TextView
    private lateinit var cityName: TextView
    private lateinit var cityPct: TextView
    private lateinit var cityProg: ProgressBar
    private lateinit var total: TextView
    private lateinit var gps: TextView
    private lateinit var gpsDot: View
    private lateinit var banner: TextView
    private lateinit var bTrack: View
    private lateinit var bTrackIcon: ImageView
    private lateinit var bTrackText: TextView
    private lateinit var scrim: View
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: LinearLayout
    private lateinit var meView: MeView
    private var follow = true
    private var centeredOnce = false
    private var recenter = false
    private var panelMode = ""
    private val outlines = ArrayList<Polyline>()

    private val fg = 0xFFEEF1F7.toInt()
    private val muted = 0xFF8F98AD.toInt()
    private val accent = 0xFF7CC4FF.toInt()
    private val green = 0xFF5FD39A.toInt()
    private val warn = 0xFFFFB454.toInt()
    private val danger = 0xFFFF7A7A.toInt()
    private val rowColor = 0xFF222A3D.toInt()

    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_BG = 2
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
        cityLabel = findViewById(R.id.cityLabel)
        cityName = findViewById(R.id.cityName)
        cityPct = findViewById(R.id.cityPct)
        cityProg = findViewById(R.id.cityProg)
        total = findViewById(R.id.total)
        gps = findViewById(R.id.gps)
        gpsDot = findViewById(R.id.gpsDot)
        banner = findViewById(R.id.banner)
        bTrack = findViewById(R.id.bTrack)
        bTrackIcon = findViewById(R.id.bTrackIcon)
        bTrackText = findViewById(R.id.bTrackText)
        scrim = findViewById(R.id.scrim)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        panelBody = findViewById(R.id.panelBody)
        findViewById<ScrollView>(R.id.panelScroll).layoutParams.height =
            (resources.displayMetrics.heightPixels * 0.55).toInt()

        map.setTilesScaledToDpi(true)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isHorizontalMapRepetitionEnabled = false
        map.isVerticalMapRepetitionEnabled = false
        map.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 19.0
        MapStyles.apply(map, getSharedPreferences("brume", MODE_PRIVATE).getInt("style", 0))
        map.overlays.add(FogOverlay())
        meView = MeView(this, map)
        findViewById<FrameLayout>(R.id.root).addView(meView, 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        map.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean { meView.invalidate(); return false }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean { meView.invalidate(); return false }
        })
        map.overlays.add(CopyrightOverlay(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(9)
            setAlignBottom(true)
            setAlignRight(false)
        })
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

        bTrack.setOnClickListener { press(it); if (TrackingService.running) TrackingService.stop(this) else startTracking() }
        findViewById<View>(R.id.bCenter).setOnClickListener {
            press(it)
            follow = true
            clearOutline()
            val m = Fog.me
            if (m != null) zoomAround(m[0], m[1], true)
            else if (!TrackingService.running) startTracking()
        }
        findViewById<View>(R.id.bStyle).setOnClickListener { press(it); chooseStyle() }
        findViewById<View>(R.id.bCities).setOnClickListener { press(it); showPanel("cities") }
        findViewById<View>(R.id.bSettings).setOnClickListener { press(it); showPanel("settings") }
        findViewById<View>(R.id.panelClose).setOnClickListener { hidePanel() }
        scrim.setOnClickListener { hidePanel() }
        findViewById<View>(R.id.topCard).setOnClickListener { showPanel("cities") }

        Geocoder.onNewCity = { c -> runOnUiThread { showBanner("Nouvelle ville · ${c.name}") } }
        Geocoder.onNewDistrict = { _, d -> runOnUiThread { showBanner("Nouvel arrondissement · ${d.name}") } }

        // Reprend l'exploration si elle tournait quand l'app a été fermée par le système
        if (prefs.getBoolean("tracking", false) && !TrackingService.running) {
            if (hasLoc() && TrackingService.locationOn(this)) TrackingService.start(this)
            else prefs.edit().putBoolean("tracking", false).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        Fog.addListener(listener)
        // Retour dans l'app : on se recentre sur sa position
        follow = true
        recenter = true
        if (outlines.isNotEmpty() || Fog.focusId != null) clearOutline()
        if (!TrackingService.running) seedLastKnown(true)
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

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) hidePanel() else super.onBackPressed()
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
        val top = findViewById<View>(R.id.topCard)
        val bottom = findViewById<View>(R.id.bottomBar)
        val center = findViewById<View>(R.id.bCenter)
        val style = findViewById<View>(R.id.bStyle)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, ins ->
            val t = ins.systemWindowInsetTop
            val b = ins.systemWindowInsetBottom
            (top.layoutParams as FrameLayout.LayoutParams).topMargin = t + dp(8)
            (bottom.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + dp(18)
            (center.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + dp(92)
            (style.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + dp(154)
            style.requestLayout()
            (banner.layoutParams as FrameLayout.LayoutParams).topMargin = t + dp(130)
            top.requestLayout(); bottom.requestLayout(); center.requestLayout(); banner.requestLayout()
            panel.setPadding(dp(16), dp(10), dp(16), b + dp(16))
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

    private fun askLocationOn() {
        AlertDialog.Builder(this)
            .setTitle("Localisation désactivée")
            .setMessage("Active la localisation du téléphone pour explorer.")
            .setPositiveButton("Activer") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun launchService() {
        follow = true
        centeredOnce = false
        seedLastKnown()
        TrackingService.start(this)
        if (!TrackingService.locationOn(this)) toast("En pause : l'exploration démarrera dès que tu activeras la localisation")
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
        if (requestCode == REQ_BG) { if (panel.visibility == View.VISIBLE) rebuildPanel(); return }
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
        val on = TrackingService.running
        val m = Fog.me
        val n = Fog.cities.size

        // Carte du haut
        val c = if (m != null) Fog.cityAt(m[0], m[1]) else null
        if (c != null) {
            cityLabel.text = if (c.sub.isNotEmpty()) c.sub else "Ville actuelle"
            cityName.text = c.name
            cityPct.text = Fog.fmtPct(c.pct())
            cityProg.visibility = View.VISIBLE
            cityProg.progress = (c.pct() * 10).roundToInt().coerceAtLeast(4)
        } else {
            cityLabel.text = if (on) "Exploration en cours" else "Brume"
            cityName.text = when {
                on && TrackingService.paused -> "En pause · rallume la localisation"
                on && m == null -> "Recherche de ta position…"
                on -> "Identification de la commune…"
                Fog.totalCells == 0L -> "Touche Explorer pour dissiper la brume"
                else -> "Prêt à explorer"
            }
            cityPct.text = ""
            cityProg.visibility = View.GONE
        }
        total.text = "${Fog.fmtArea(Fog.exploredArea())} dévoilés · $n ville${if (n > 1) "s" else ""}"

        // État du GPS
        val good = on && m != null && m[2] <= Fog.maxAcc
        gps.text = when {
            !on -> "GPS éteint"
            TrackingService.paused -> "Localisation coupée"
            m == null -> "Recherche…"
            else -> buildString {
                if (m[3] >= 1.0) append("${(m[3] * 3.6).roundToInt()} km/h · ")
                append("±${m[2].roundToInt()} m")
                if (m[2] > Fog.maxAcc) append(" · trop imprécis")
            }
        }
        val gc = when { !on -> muted; good && !TrackingService.paused -> green; else -> warn }
        gps.setTextColor(if (on) gc else muted)
        gpsDot.backgroundTintList = ColorStateList.valueOf(gc)

        // Bouton principal
        val paused = on && TrackingService.paused
        bTrackText.text = when { paused -> "En pause"; on -> "En cours"; else -> "Explorer" }
        bTrackIcon.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play)
        bTrack.backgroundTintList = ColorStateList.valueOf(when { paused -> warn; on -> green; else -> accent })
        meView.active = on
        meView.paused = paused
        meView.invalidate()

        if (m != null && !centeredOnce) {
            centeredOnce = true
            recenter = false
            zoomAround(m[0], m[1], false)
        } else if (m != null && recenter) {
            recenter = false
            // On garde le zoom choisi, sauf si la carte était trop dézoomée pour voir autour de soi
            if (map.zoomLevelDouble < 14.0) zoomAround(m[0], m[1], false)
            else map.controller.setCenter(GeoPoint(m[0], m[1]))
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
    private fun seedLastKnown(refreshIfIdle: Boolean = false) {
        if ((Fog.me != null && !refreshIfIdle) || !hasLoc()) return
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val best = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return
        Fog.me = doubleArrayOf(best.latitude, best.longitude, if (best.hasAccuracy()) best.accuracy.toDouble() else 99.0, -1.0)
    }

    // ---------- Animations ----------
    private fun press(v: View) {
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun showBanner(s: String) {
        banner.text = s
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = -dp(20).toFloat()
        banner.animate().alpha(1f).translationY(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).withEndAction {
            banner.animate().alpha(0f).setStartDelay(2600).setDuration(400).withEndAction { banner.visibility = View.GONE }.start()
        }.start()
    }

    // ---------- Panneaux ----------
    private fun showPanel(mode: String) {
        if (panel.visibility == View.VISIBLE && panelMode == mode) { hidePanel(); return }
        val wasOpen = panel.visibility == View.VISIBLE
        panelMode = mode
        rebuildPanel()
        if (wasOpen) return
        panel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        panel.translationY = resources.displayMetrics.heightPixels.toFloat()
        panel.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2f)).start()
        scrim.animate().alpha(1f).setDuration(250).start()
    }

    private fun hidePanel() {
        if (panel.visibility != View.VISIBLE) return
        panel.animate().translationY(panel.height.toFloat()).setDuration(220)
            .withEndAction { panel.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(220).withEndAction { scrim.visibility = View.GONE }.start()
    }

    private fun rebuildPanel() {
        val sv = findViewById<ScrollView>(R.id.panelScroll)
        val y = sv.scrollY
        panelBody.removeAllViews()
        when {
            panelMode == "cities" -> buildCities()
            panelMode.startsWith("city:") -> buildCityDetail(panelMode.substring(5))
            else -> buildSettings()
        }
        sv.post { sv.scrollTo(0, y) }
    }

    private fun buildCities() {
        panelTitle.text = "Mes villes"
        val list = synchronized(Fog.lock) { Fog.cities.values.toList() }.sortedByDescending { it.pct() }

        // Tuiles de résumé
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(statTile(Fog.fmtArea(Fog.exploredArea()), "dévoilés"), weighted())
        tiles.addView(statTile("${list.size}", if (list.size > 1) "villes" else "ville"), weighted().apply { marginStart = dp(8) })
        tiles.addView(statTile(list.firstOrNull()?.let { Fog.fmtPct(it.pct()) } ?: "—", "record"), weighted().apply { marginStart = dp(8) })
        panelBody.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        if (list.isEmpty()) {
            panelBody.addView(text("Aucune ville pour l'instant.\nLance l'exploration et la première commune apparaîtra ici.", 14f, muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24))
            })
            return
        }
        list.forEachIndexed { idx, c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(rowColor, 14f)
                setPadding(dp(12), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener {
                    press(this)
                    if (Fog.visitedDistricts(c.id).isNotEmpty()) { panelMode = "city:" + c.id; rebuildPanel() } else showCity(c)
                }
            }
            // Rang
            row.addView(text("${idx + 1}", 13f, if (idx < 3) 0xFF0B1220.toInt() else fg, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(when (idx) { 0 -> 0xFFFFD166.toInt(); 1 -> 0xFFD7DEE8.toInt(); 2 -> 0xFFE0A872.toInt(); else -> 0xFF2E3850.toInt() })
                }
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(text(c.name, 16f, fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(text(Fog.fmtPct(c.pct()), 16f, accent, true))
            col.addView(line)
            col.addView(text("${Fog.fmtArea(c.n * Fog.CELL * Fog.CELL)} sur ${Fog.fmtArea(c.area)}" +
                (if (c.sub.isNotEmpty()) " · ${c.sub}" else ""), 12f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (c.pct() * 10).roundToInt().coerceAtLeast(4)
                progressDrawable = getDrawable(R.drawable.progress)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply { topMargin = dp(8) })
            // Arrondissements visités : la ville s'ouvre sur leur détail
            val nd = Fog.visitedDistricts(c.id).size
            if (nd > 0) col.addView(text("$nd arrondissement${if (nd > 1) "s" else ""} visité${if (nd > 1) "s" else ""}  ›", 12f, accent, true).apply { setPadding(0, dp(8), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            panelBody.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        }
        panelBody.addView(text("Touche une ville pour voir ses limites. Le % compare la surface dévoilée à la surface officielle de la commune (OpenStreetMap).", 12f, muted)
            .apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    /** Détail d'une ville : avancement global + arrondissements visités. */
    private fun buildCityDetail(id: String) {
        val c = synchronized(Fog.lock) { Fog.cities[id] }
        if (c == null) { panelMode = "cities"; buildCities(); return }
        panelTitle.text = c.name
        panelBody.addView(text("‹  Mes villes", 14f, accent, true).apply {
            setPadding(dp(4), dp(2), 0, dp(10))
            isClickable = true
            setOnClickListener { panelMode = "cities"; rebuildPanel() }
        })
        val ds = Fog.visitedDistricts(id)
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(statTile(Fog.fmtPct(c.pct()), "de la ville"), weighted())
        tiles.addView(statTile(Fog.fmtArea(c.n * Fog.CELL * Fog.CELL), "dévoilés"), weighted().apply { marginStart = dp(8) })
        tiles.addView(statTile("${ds.size}", if (ds.size > 1) "arrondissements" else "arrondissement"), weighted().apply { marginStart = dp(8) })
        panelBody.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        settingRow("Voir sur la carte", "Cadrer la ville et afficher ses limites", null) { showCity(c) }
        section("Arrondissements visités")
        for (d in ds) panelBody.addView(districtRow(d), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        panelBody.addView(text("Un arrondissement apparaît ici dès que tu y passes. Touche-le pour le voir sur la carte.", 12f, muted)
            .apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    private fun districtRow(d: City): View {
        val v = d.pct()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF1C2333.toInt(), 12f)
            setPadding(dp(12), dp(9), dp(12), dp(10))
            isClickable = true
            setOnClickListener { press(this); showCity(d) }
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(text(d.name, 14f, if (d.n > 0) fg else muted, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(text(if (d.n > 0) Fog.fmtPct(v) else "—", 14f, if (d.n > 0) accent else muted, true))
        row.addView(line)
        row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (d.n > 0) (v * 10).roundToInt().coerceAtLeast(4) else 0
            progressDrawable = getDrawable(R.drawable.progress)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })
        return row
    }

    private fun statTile(value: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(rowColor, 14f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(text(value, 16f, fg, true).apply { maxLines = 1 })
        addView(text(label, 11f, muted))
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun showCity(c: City) {
        follow = false
        clearOutline()
        Fog.focusId = c.id
        map.zoomToBoundingBox(BoundingBox(c.bbox[3], c.bbox[2], c.bbox[1], c.bbox[0]), true, dp(48))
        hidePanel()
        showBanner("${c.name} · ${Fog.fmtPct(c.pct())}")
    }

    private fun clearOutline() {
        for (pl in outlines) map.overlays.remove(pl)
        outlines.clear()
        Fog.focusId = null
        map.invalidate()
    }

    private fun chooseStyle() {
        val cur = getSharedPreferences("brume", MODE_PRIVATE).getInt("style", 0)
        val names = MapStyles.all.map { "${it.name} · ${it.desc}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Style de carte")
            .setSingleChoiceItems(names, cur) { dlg, which ->
                getSharedPreferences("brume", MODE_PRIVATE).edit().putInt("style", which).apply()
                MapStyles.apply(map, which)
                if (panel.visibility == View.VISIBLE && panelMode == "settings") rebuildPanel()
                dlg.dismiss()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun buildSettings() {
        panelTitle.text = "Réglages"
        section("Carte")
        val st = MapStyles.all[getSharedPreferences("brume", MODE_PRIVATE).getInt("style", 0).coerceIn(0, MapStyles.all.size - 1)]
        settingRow("Style de carte", st.desc, st.name) { chooseStyle() }
        fogSliderRow()
        settingRow("Limites des villes", "Contour des communes découvertes, la tienne en bleu", if (Fog.showBorders) "Oui" else "Non") {
            Fog.showBorders = !Fog.showBorders
            Fog.savePrefs(this); map.invalidate(); rebuildPanel()
        }
        section("Exploration")
        settingRow("Rayon de découverte", "Appliqué aux prochains trajets", "${Fog.radius.toInt()} m") {
            val opts = listOf(15, 20, 25, 30, 40, 50)
            Fog.radius = opts[(opts.indexOf(Fog.radius.toInt()) + 1) % opts.size].toDouble()
            Fog.savePrefs(this); rebuildPanel()
        }
        settingRow("Précision GPS acceptée", "Les positions moins précises sont ignorées", "±${Fog.maxAcc.toInt()} m") {
            val opts = listOf(20, 30, 40, 50, 60, 100)
            Fog.maxAcc = opts[(opts.indexOf(Fog.maxAcc.toInt()) + 1) % opts.size].toFloat()
            Fog.savePrefs(this); rebuildPanel()
        }
        val okBg = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        settingRow("Relance automatique",
            if (okBg) "Après un redémarrage du téléphone ou une mise à jour" else "Choisis « Toujours autoriser » pour la position",
            if (okBg) "✓" else null, if (okBg) green else warn) {
            if (okBg) toast("C'est déjà bon ✓")
            else if (!hasLoc()) toast("Lance d'abord une exploration")
            else requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
        }
        val okBatt = ignoringBattery()
        settingRow("Batterie sans restriction",
            if (okBatt) "Android ne coupera pas l'exploration" else "Recommandé pour l'écran verrouillé",
            if (okBatt) "✓" else null, if (okBatt) green else warn) { askBattery() }

        section("Données")
        settingRow("Importer un historique", "Google Maps « Vos trajets », GPX (Strava, Garmin…) ou sauvegarde Brume", null) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(i, REQ_OPEN)
        }
        settingRow("Exporter mes données", "Fichier JSON à garder en lieu sûr", null) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "brume-${LocalDate.now()}.json")
            startActivityForResult(i, REQ_SAVE)
        }
        settingRow("Tout effacer", "Carte découverte et villes", null, danger) {
            AlertDialog.Builder(this).setTitle("Tout effacer ?")
                .setMessage("La carte découverte et toutes les villes seront supprimées.")
                .setPositiveButton("Effacer") { _, _ -> Fog.reset(); rebuildPanel(); toast("Tout est effacé") }
                .setNegativeButton("Annuler", null).show()
        }
        panelBody.addView(text("${String.format("%,d", Fog.totalCells)} cellules de 10 m · ${Fog.chunks.size} blocs · Brume 1.1", 11f, muted)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
    }

    private fun section(title: String) {
        panelBody.addView(text(title.uppercase(), 11f, muted, true).apply {
            letterSpacing = 0.08f
            setPadding(dp(4), dp(10), 0, dp(6))
        })
    }

    private fun fogDesc(v: Int) = when {
        v >= 100 -> "Carte totalement cachée"
        v >= 85 -> "On distingue à peine les routes"
        v >= 60 -> "On devine les routes dessous"
        else -> "Brume légère, carte bien visible"
    }

    /** Occultation du brouillard : curseur de 20 à 100 %, appliqué en direct sur la carte. */
    private fun fogSliderRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(rowColor, 14f)
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("Occultation du brouillard", 15f, fg, true))
        val sub = text(fogDesc(Fog.fogAlpha), 12f, muted)
        col.addView(sub)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = text("${Fog.fogAlpha} %", 13f, accent, true).apply {
            background = rounded(0x267CC4FF, 10f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        top.addView(value)
        row.addView(top)
        val seek = android.widget.SeekBar(this).apply {
            max = 16                                   // 20 % → 100 % par pas de 5
            progress = ((Fog.fogAlpha.coerceIn(20, 100) - 20) / 5)
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    Fog.fogAlpha = 20 + p * 5
                    value.text = "${Fog.fogAlpha} %"
                    sub.text = fogDesc(Fog.fogAlpha)
                    map.invalidate()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { Fog.savePrefs(this@MainActivity) }
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        panelBody.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun settingRow(title: String, sub: String, value: String?, color: Int = fg, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(rowColor, 14f)
            setPadding(dp(14), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener { press(this); onClick() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f, color, true))
        col.addView(text(sub, 12f, muted))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            row.addView(text(value, 13f, if (color == fg) accent else color, true).apply {
                background = rounded(0x267CC4FF, 10f)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        } else {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(muted)
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) })
        }
        panelBody.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
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
                        val head = contentResolver.openInputStream(uri)!!.use { st ->
                            val buf = ByteArray(4096); val n = st.read(buf); if (n > 0) String(buf, 0, n) else ""
                        }.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                        when {
                            head.startsWith("{") && head.contains("\"app\"") && head.contains("brume") -> {
                                val txt = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                                Io.importJson(String(txt).trimStart('\uFEFF'))
                                toast("Sauvegarde restaurée")
                            }
                            head.startsWith("{") || head.startsWith("[") -> {
                                toast("Lecture de l'historique Google…")
                                val n = contentResolver.openInputStream(uri)!!.use { Timeline.load(it) }
                                toast(if (n > 0) "${String.format("%,d", n)} positions importées · les villes apparaissent peu à peu" else "Aucune position trouvée dans ce fichier")
                            }
                            else -> {
                                val n = contentResolver.openInputStream(uri)!!.use { Io.importGpx(it) }
                                toast(if (n > 0) "$n points importés" else "Aucun point trouvé dans ce fichier")
                            }
                        }
                        Fog.flush()
                    } catch (e: Exception) {
                        e.printStackTrace(); toast("Fichier non reconnu")
                    } catch (e: OutOfMemoryError) {
                        toast("Fichier trop volumineux")
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

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
