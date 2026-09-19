package com.pmfibre.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import java.io.File

/** En dessous, le rectangle visible couvrirait un département entier : illisible. */
private const val ZOOM_MIN_PM = 13.0

/** Les libellés n'apparaissent qu'une fois assez zoomé, sinon ils se chevauchent. */
private const val ZOOM_LABELS = 17

/** Un cercle d'incertitude de 20 m n'a de sens qu'à une échelle où 20 m se voient. */
private const val ZOOM_CERCLE = 16.0

/** Garde-fou : au-delà, on n'affiche plus tout, on demande de zoomer. */
private const val MAX_POINTS = 3000


/**
 * Fond orthophotographique IGN (roadmap 3.7) : BD ORTHO 20 cm/pixel, flux WMTS
 * ouvert de la Géoplateforme, sans clé depuis 2024. Il sert autant à lire — la
 * haie, le recoin et le chemin d'accès se voient — qu'à pointer : sur une image à
 * 20 cm on place une armoire à un ou deux mètres, dix fois mieux que le GNSS d'un
 * téléphone.
 *
 * `XYTileSource` ne convient pas : le WMTS attend des paramètres de requête et non
 * un chemin `z/x/y.png`, d'où la construction d'URL à la main.
 */
private val OrthoIGN = object : OnlineTileSourceBase(
    "OrthoIGN", 6, 19, 256, "",
    arrayOf("https://data.geopf.fr/wmts"),
    "IGN-F / Géoplateforme — BD ORTHO"
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + "?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
            "&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&STYLE=normal&TILEMATRIXSET=PM" +
            "&FORMAT=image/jpeg" +
            "&TILEMATRIX=" + MapTileIndex.getZoom(pMapTileIndex) +
            "&TILEROW=" + MapTileIndex.getY(pMapTileIndex) +
            "&TILECOL=" + MapTileIndex.getX(pMapTileIndex)
}

/** Petit point bleu (halo blanc) pour matérialiser « Ma position ». */
private fun blueDotIcon(context: Context): Drawable {
    val d = context.resources.displayMetrics.density
    val size = (18 * d).toInt().coerceAtLeast(18)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = AndroidColor.WHITE
    cv.drawCircle(r, r, r, p)                    // halo blanc
    p.color = AndroidColor.rgb(0x15, 0x65, 0xC0) // bleu
    cv.drawCircle(r, r, r * 0.68f, p)
    return BitmapDrawable(context.resources, bmp)
}

/**
 * PM au même endroit — les shelters (roadmap 3.9). Un à trois PM partagent
 * régulièrement une armoire ; superposés au pixel près ils sont indiscernables et
 * un appui en choisirait un au hasard. Regroupés, ils deviennent une information
 * utile : trouver le shelter, c'est trouver les trois.
 */
private data class GroupePm(val lat: Double, val lon: Double, val membres: List<PmView>) {
    /** Un seul membre relevé sur place suffit à mener au bon endroit. */
    val exact: Boolean get() = membres.any { it.exact }
    val libelle: String
        get() = if (membres.size > 1) membres.size.toString() + " PM"
                else (membres[0].pm.code ?: "PM")
}

/**
 * Grille de regroupement à la quatrième décimale, soit environ onze mètres. Deux PM
 * distants de onze mètres sont de toute façon dans la même armoire du point de vue
 * de celui qui cherche. Une grille a ses effets de bord — deux points proches de
 * part et d'autre d'une frontière de cellule ne fusionnent pas — mais ce pire cas
 * est exactement l'affichage d'aujourd'hui : deux points au lieu d'un.
 */
private fun regroupe(vues: List<PmView>): List<GroupePm> {
    val paquets = LinkedHashMap<Long, MutableList<PmView>>()
    for (v in vues) {
        val cle = (Math.round(v.lat * 10000) shl 22) xor Math.round(v.lon * 10000)
        paquets.getOrPut(cle) { ArrayList() }.add(v)
    }
    return paquets.values.map { GroupePm(it[0].lat, it[0].lon, it) }
}

/** Onglet Carte : les PM de la zone visible, sur fond OpenStreetMap ou ortho IGN. */
@Composable
fun MapScreen(onSelect: (Pm) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val densite = context.resources.displayMetrics.density
    val couleurs = LocalCouleursPm.current
    // Les points sont dessinés par osmdroid, en dehors de Compose : la couleur
    // du thème doit être convertie en entier ARGB Android.
    val couleurExact = couleurs.exact.toArgb()
    val couleurApprox = couleurs.approx.toArgb()
    val rayonPoint = if (Settings.cartePointsGros) 9f else 6f

    var maPosition by remember { mutableStateOf<GeoPoint?>(null) }
    var groupes by remember { mutableStateOf<List<GroupePm>>(emptyList()) }
    var zoom by remember { mutableDoubleStateOf(16.0) }
    var status by remember { mutableStateOf("Localisation…") }
    // Fond ouvert par défaut : le réglage de l'utilisateur, pas une constante.
    // La bascule du haut reste disponible, elle ne vaut que pour cette session.
    var ortho by remember { mutableStateOf(Settings.carteFond == FondCarte.ORTHO) }
    var choix by remember { mutableStateOf<GroupePm?>(null) }

    // Configurer osmdroid AVANT de créer la MapView (user-agent obligatoire pour les tuiles).
    Configuration.getInstance().apply {
        if (userAgentValue.isNullOrBlank()) {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }

    // Recalcule les PM du rectangle visible. Appelée à chaque déplacement, derrière
    // un `DelayedMapListener` : pendant un glissement osmdroid émet des dizaines
    // d'événements, l'anti-rebond n'en garde que le dernier.
    fun rafraichit() {
        val z = mapView.zoomLevelDouble
        zoom = z
        if (z < ZOOM_MIN_PM) {
            groupes = emptyList()
            status = "Zoome pour voir les PM"
            return
        }
        val bb = mapView.boundingBox ?: return
        scope.launch {
            val g = withContext(Dispatchers.IO) {
                regroupe(PmRepository.inBoundingBox(
                    bb.latSouth, bb.latNorth, bb.lonWest, bb.lonEast, MAX_POINTS))
            }
            groupes = g
            val n = g.sumOf { it.membres.size }
            status = when {
                PmRepository.size == 0 -> "Aucun département installé"
                n == 0 -> "Aucun PM dans cette zone"
                n >= MAX_POINTS -> n.toString() + "+ PM — zoome pour tout voir"
                else -> n.toString() + " PM · ● vert = relevé sur place · ■ rouge = centre de zone"
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun locate() {
        val client = LocationServices.getFusedLocationProviderClient(context)
        scope.launch {
            try {
                val loc = client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
                ).await()
                if (loc != null) {
                    val p = GeoPoint(loc.latitude, loc.longitude)
                    maPosition = p
                    mapView.controller.setZoom(16.0)
                    mapView.controller.setCenter(p)
                    rafraichit()
                } else status = "Position introuvable (GPS ?)"
            } catch (e: Exception) {
                status = "Erreur de localisation : " + (e.message ?: "")
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) locate() else status = "Localisation refusée." }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) locate() else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    DisposableEffect(Unit) {
        val ecouteur = DelayedMapListener(object : MapListener {
            override fun onScroll(e: ScrollEvent?): Boolean { rafraichit(); return true }
            override fun onZoom(e: ZoomEvent?): Boolean { rafraichit(); return true }
        }, 300)
        mapView.addMapListener(ecouteur)
        // Sans fix GPS — permission refusée, sous-sol, GPS coupé — la carte doit
        // quand même se remplir : dès qu'elle a une taille, elle a un rectangle.
        val premierTrace = MapView.OnFirstLayoutListener { _, _, _, _, _ -> rafraichit() }
        if (mapView.isLayoutOccurred) rafraichit() else mapView.addOnFirstLayoutListener(premierTrace)
        mapView.onResume()
        onDispose {
            mapView.removeOnFirstLayoutListener(premierTrace)
            mapView.removeMapListener(ecouteur)
            mapView.onPause()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(status, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                if (ortho) "🗺️ Plan" else "🛰️ Ortho",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1),
                modifier = Modifier.clickable { ortho = !ortho }.padding(4.dp)
            )
        }
        if (ortho) {
            Text(
                "© IGN — BD ORTHO", fontSize = 10.sp, color = Color(0xFF666666),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        HorizontalDivider()
        AndroidView(
            factory = { mapView },
            update = { map ->
                val source = if (ortho) OrthoIGN else TileSourceFactory.MAPNIK
                if (map.tileProvider.tileSource.name() != source.name()) map.setTileSource(source)

                map.overlays.clear()

                // Cercles d'incertitude (roadmap 3.7) : un point net à trente mètres
                // près est un mensonge visuel — le collègue cherchera au mauvais
                // endroit en toute confiance. Seulement là où la précision est connue
                // et mauvaise, et à une échelle où le cercle veut dire quelque chose.
                if (zoom >= ZOOM_CERCLE && groupes.size <= 200) {
                    for (g in groupes) {
                        val prec = g.membres.mapNotNull { it.accuracyM }.maxOrNull() ?: continue
                        if (prec < 10.0) continue
                        map.overlays.add(Polygon(map).apply {
                            points = Polygon.pointsAsCircle(GeoPoint(g.lat, g.lon), prec)
                            fillPaint.color = AndroidColor.argb(40,
                                AndroidColor.red(couleurExact), AndroidColor.green(couleurExact),
                                AndroidColor.blue(couleurExact))
                            outlinePaint.color = AndroidColor.argb(120,
                                AndroidColor.red(couleurExact), AndroidColor.green(couleurExact),
                                AndroidColor.blue(couleurExact))
                            outlinePaint.strokeWidth = 1f * densite
                        })
                    }
                }

                // Deux couches, une par statut. La forme distingue autant que la
                // couleur : environ 8 % des hommes confondent le vert et le rouge,
                // et c'est justement la population du terrain.
                ajouteCouche(
                    map, groupes.filter { it.exact }, couleurExact,
                    SimpleFastPointOverlayOptions.Shape.CIRCLE, densite,
                    rayonPoint, Settings.carteLibelles,
                    onUn = onSelect, onPlusieurs = { choix = it }
                )
                ajouteCouche(
                    map, groupes.filter { !it.exact }, couleurApprox,
                    SimpleFastPointOverlayOptions.Shape.SQUARE, densite,
                    rayonPoint, Settings.carteLibelles,
                    onUn = onSelect, onPlusieurs = { choix = it }
                )

                // Ma position en dernier : elle doit rester visible au-dessus des PM.
                maPosition?.let { p ->
                    map.overlays.add(Marker(map).apply {
                        position = p
                        title = "Ma position"
                        icon = blueDotIcon(map.context)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    })
                }
                map.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    // Shelter : on ne choisit pas à la place de l'utilisateur.
    choix?.let { g ->
        AlertDialog(
            onDismissRequest = { choix = null },
            title = { Text(g.membres.size.toString() + " PM à cet endroit") },
            text = {
                Column {
                    Text(
                        "Ils partagent la même armoire ou le même shelter.",
                        fontSize = 13.sp, color = Color(0xFF666666)
                    )
                    for (v in g.membres) {
                        Text(
                            (if (v.exact) "✅ " else "≈ ") + (v.pm.code ?: "PM") +
                                " · " + PmRepository.operatorName(v.pm),
                            fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { choix = null; onSelect(v.pm) }
                                .padding(vertical = 10.dp)
                        )
                        HorizontalDivider(color = Color(0xFFEEEEEE))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choix = null }) { Text("Fermer") } }
        )
    }
}

/**
 * Une couche de points. `SimpleFastPointOverlay` dessine des milliers de points
 * dans un seul overlay, là où l'ancien code créait un `Marker` par PM — un overlay
 * chacun, et un ralentissement net au-delà de quelques centaines.
 *
 * Écart assumé à la roadmap, qui prévoyait `MAXIMUM_OPTIMIZATION` : cet algorithme
 * met sa grille en cache et ne la recalcule qu'au changement de zoom. Comme la
 * liste est rebâtie à chaque déplacement, le cache ne servirait à rien et rendrait
 * un affichage en retard d'un glissement. `MEDIUM_OPTIMIZATION` recalcule à chaque
 * dessin, ce qui est sans conséquence sur une liste déjà bornée par le rectangle
 * visible, et il sait afficher les libellés.
 */
private fun ajouteCouche(
    map: MapView,
    groupes: List<GroupePm>,
    couleur: Int,
    forme: SimpleFastPointOverlayOptions.Shape,
    densite: Float,
    rayon: Float,
    libelles: Boolean,
    onUn: (Pm) -> Unit,
    onPlusieurs: (GroupePm) -> Unit
) {
    if (groupes.isEmpty()) return
    val points = ArrayList<IGeoPoint>(groupes.size)
    for (g in groupes) points.add(LabelledGeoPoint(g.lat, g.lon, g.libelle))

    val style = SimpleFastPointOverlayOptions.getDefaultStyle()
        .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MEDIUM_OPTIMIZATION)
        .setSymbol(forme)
        .setRadius(rayon * densite)
        .setIsClickable(true)
        .setCellSize((16 * densite).toInt())
        .setPointStyle(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = couleur
            style = Paint.Style.FILL
        })
        .setSelectedPointStyle(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.FILL
        })
        .setTextStyle(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.BLACK
            textAlign = Paint.Align.CENTER
            textSize = 11f * densite
            setShadowLayer(2f, 0f, 0f, AndroidColor.WHITE)
        })
        .setLabelPolicy(SimpleFastPointOverlayOptions.LabelPolicy.ZOOM_THRESHOLD)
        // Libellés coupés : un seuil de zoom inatteignable vaut mieux qu'une
        // seconde branche de style à maintenir.
        .setMinZoomShowLabels(if (libelles) ZOOM_LABELS else 99)

    val overlay = SimpleFastPointOverlay(SimplePointTheme(points, true), style)
    overlay.setOnClickListener { _, index ->
        val g = groupes.getOrNull(index ?: -1) ?: return@setOnClickListener
        if (g.membres.size == 1) onUn(g.membres[0].pm) else onPlusieurs(g)
    }
    map.overlays.add(overlay)
}
