package com.pmfibre.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.material3.Text
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

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

private val PmExactColor = AndroidColor.rgb(0x2E, 0x7D, 0x32)   // vert : géolocalisé précisément
private val PmApproxColor = AndroidColor.rgb(0xD3, 0x2F, 0x2F)  // rouge : position approximative

/** Pin de carte façon "goutte" (contour blanc + point central), coloré selon le statut. */
private fun pmMarkerIcon(context: Context, pinColor: Int): Drawable {
    val d = context.resources.displayMetrics.density
    val w = (28 * d).toInt().coerceAtLeast(28)
    val h = (36 * d).toInt().coerceAtLeast(36)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val stroke = 1.6f * d
    val cx = w / 2f
    val r = w / 2f - stroke
    val cy = r + stroke
    val tipY = h - stroke * 0.5f

    fun teardrop(headR: Float, tipYy: Float): Path = Path().apply {
        addCircle(cx, cy, headR, Path.Direction.CW)
        val baseHalfWidth = headR * 0.62f
        val baseY = cy + headR * 0.8f
        moveTo(cx - baseHalfWidth, baseY)
        lineTo(cx, tipYy)
        lineTo(cx + baseHalfWidth, baseY)
        close()
    }

    // Ombre légère
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = AndroidColor.argb(50, 0, 0, 0) }
    cv.save()
    cv.translate(0f, stroke * 0.5f)
    cv.drawPath(teardrop(r, tipY), shadowPaint)
    cv.restore()

    // Contour blanc (légèrement plus grand que le corps coloré)
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = AndroidColor.WHITE }
    cv.drawPath(teardrop(r + stroke, tipY + stroke * 0.4f), borderPaint)

    // Corps coloré
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = pinColor }
    cv.drawPath(teardrop(r, tipY), fillPaint)

    // Point central blanc
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = AndroidColor.WHITE }
    cv.drawCircle(cx, cy, r * 0.32f, dotPaint)

    return BitmapDrawable(context.resources, bmp)
}

/** Onglet Carte : PM autour de ma position sur un fond OpenStreetMap. */
@Composable
fun MapScreen(onSelect: (Pm) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var center by remember { mutableStateOf<GeoPoint?>(null) }
    var nearby by remember { mutableStateOf<List<PmDistance>>(emptyList()) }
    var status by remember { mutableStateOf("Localisation…") }

    // Configurer osmdroid AVANT de créer la MapView (user-agent obligatoire pour les tuiles).
    Configuration.getInstance().apply {
        if (userAgentValue.isNullOrBlank()) {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
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
                    center = GeoPoint(loc.latitude, loc.longitude)
                    nearby = withContext(Dispatchers.IO) {
                        PmRepository.nearest(loc.latitude, loc.longitude, 120)
                    }
                    status = "${nearby.size} PM autour · ✅ exacte · ≈ à géolocaliser"
                } else status = "Position introuvable (GPS ?)"
            } catch (e: Exception) {
                status = "Erreur de localisation : ${e.message}"
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

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    Column(Modifier.fillMaxSize()) {
        Text(status, Modifier.padding(8.dp), fontSize = 13.sp)
        AndroidView(
            factory = { mapView },
            update = { map ->
                val c = center ?: return@AndroidView
                map.controller.setCenter(c)
                map.overlays.clear()
                // Ma position : point bleu distinct
                map.overlays.add(Marker(map).apply {
                    position = c
                    title = "Ma position"
                    icon = blueDotIcon(map.context)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
                // PM autour : pin vert = géolocalisé précisément, rouge = position approximative
                val exactIcon = pmMarkerIcon(map.context, PmExactColor)
                val approxIcon = pmMarkerIcon(map.context, PmApproxColor)
                nearby.forEach { pd ->
                    val v = pd.view
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(v.lat, v.lon)
                        title = (if (v.exact) "✅ " else "≈ ") + (v.pm.code ?: "PM")
                        subDescription = "${PmRepository.operatorName(v.pm)} · ${v.pm.com ?: ""}"
                        icon = if (v.exact) exactIcon else approxIcon
                        setOnMarkerClickListener { _, _ -> onSelect(v.pm); true }
                    })
                }
                map.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
