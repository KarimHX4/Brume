package fr.brume

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import kotlin.math.cos
import kotlin.math.sin

/** Styles de carte, tous utilisables sans clé d'accès. */
object MapStyles {
    class Style(val name: String, val desc: String, val source: ITileSource, val maxZoom: Double, val filter: () -> ColorMatrix?, val loading: Int)

    private val satellite = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "© Esri, Maxar, Earthstar Geographics"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
    }

    private val relief = XYTileSource(
        "OpenTopoMapR", 0, 17, 256, ".png",
        arrayOf("https://tile.openmaps.fr/opentopomap/"),
        "© OpenTopoMap-R · © OpenStreetMap"
    )

    /** Couleurs plus vives et plus contrastées que le rendu OSM d'origine, un peu pâle. */
    private fun vivid(): ColorMatrix {
        val m = ColorMatrix(); m.setSaturation(1.45f)
        val c = 1.12f; val t = (1f - c) * 128f
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    private fun hueRotate(deg: Double): ColorMatrix {
        val r = Math.toRadians(deg); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return ColorMatrix(floatArrayOf(
            0.213f + 0.787f * c - 0.213f * s, 0.715f - 0.715f * c - 0.715f * s, 0.072f - 0.072f * c + 0.928f * s, 0f, 0f,
            0.213f - 0.213f * c + 0.143f * s, 0.715f + 0.285f * c + 0.140f * s, 0.072f - 0.072f * c - 0.283f * s, 0f, 0f,
            0.213f - 0.213f * c - 0.787f * s, 0.715f - 0.715f * c + 0.715f * s, 0.072f + 0.928f * c + 0.072f * s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
    }

    /** Carte nocturne : inversion des clartés en gardant les teintes (eau bleue, parcs verts). */
    private fun night(): ColorMatrix {
        val m = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        m.postConcat(hueRotate(180.0))
        val sat = ColorMatrix(); sat.setSaturation(0.8f); m.postConcat(sat)
        m.postConcat(ColorMatrix(floatArrayOf(0.85f, 0f, 0f, 0f, 4f, 0f, 0.88f, 0f, 0f, 8f, 0f, 0f, 0.95f, 0f, 18f, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    val all = listOf(
        Style("Couleurs", "Plan OpenStreetMap ravivé", TileSourceFactory.MAPNIK, 19.0, { vivid() }, 0xFFE8E4DC.toInt()),
        Style("Nuit", "Plan sombre assorti à l'app", TileSourceFactory.MAPNIK, 19.0, { night() }, 0xFF1A2232.toInt()),
        Style("Satellite", "Images aériennes (Esri)", satellite, 19.0, { null }, 0xFF1B2620.toInt()),
        Style("Relief", "Courbes de niveau et ombrage (OpenTopoMap)", relief, 17.0, { null }, 0xFFE9EDE0.toInt()),
        Style("Classique", "Plan OpenStreetMap d'origine", TileSourceFactory.MAPNIK, 19.0, { null }, 0xFFE8E4DC.toInt())
    )

    fun apply(map: MapView, index: Int) {
        val st = all[index.coerceIn(0, all.size - 1)]
        map.setTileSource(st.source)
        map.maxZoomLevel = st.maxZoom
        if (map.zoomLevelDouble > st.maxZoom) map.controller.setZoom(st.maxZoom)
        val tiles = map.overlayManager.tilesOverlay
        tiles.setColorFilter(st.filter()?.let { ColorMatrixColorFilter(it) })
        tiles.setLoadingBackgroundColor(st.loading)
        tiles.setLoadingLineColor(st.loading)
        map.invalidate()
    }
}
