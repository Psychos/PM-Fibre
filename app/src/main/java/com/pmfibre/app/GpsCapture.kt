package com.pmfibre.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Durée par défaut d'une capture précise, en secondes (roadmap 3.5). */
const val CAPTURE_PRECISE_S = 30

/**
 * Fusion d'une série de fixes GNSS (roadmap 3.5). Séparé de l'interface pour être
 * éprouvé sur la JVM : c'est le seul endroit du client où une erreur de calcul
 * produirait une position fausse *et crédible* — pire qu'une position absente,
 * parce qu'un collègue s'y fierait.
 */
object GpsFusion {

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val tMs: Long)

    data class Resultat(
        val lat: Double,
        val lon: Double,
        /** Médiane des précisions retenues, pas la meilleure valeur vue. */
        val accuracyM: Double,
        val retenus: Int,
        /** Écart maximal entre les fixes retenus : au-delà, l'utilisateur bouge. */
        val deriveM: Double
    )

    /** Les cinq premières secondes servent à converger : elles ne comptent pas. */
    const val REJET_MS = 5_000L

    /** Au-delà, les fixes ne décrivent plus un point mais un trajet. */
    const val DERIVE_ALERTE_M = 15.0

    fun mediane(valeurs: List<Double>): Double {
        val v = valeurs.sorted()
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2.0
    }

    /**
     * Fixes conservés pour le calcul : ceux d'après la convergence, et parmi
     * eux ceux dont la précision approche la meilleure observée. Un fix annoncé
     * à 40 m au milieu d'une série à 6 m n'apporte rien — sauf du bruit.
     *
     * La marge est relative *et* absolue : à 4 m de précision, `4 × 1,5 = 6 m`
     * rejetterait des fixes parfaitement bons, d'où le plancher de +3 m.
     */
    fun retenus(fixes: List<Fix>, debutMs: Long): List<Fix> {
        if (fixes.isEmpty()) return emptyList()
        // Repli sur la totalité si l'utilisateur valide avant la fin de la
        // convergence : mieux vaut une position moyennée sur trois fixes tièdes
        // que pas de position du tout.
        val apres = fixes.filter { it.tMs - debutMs >= REJET_MS }
        val base = if (apres.isEmpty()) fixes else apres
        val meilleure = base.minOf { it.accuracyM }
        val plafond = maxOf(meilleure * 1.5, meilleure + 3.0)
        return base.filter { it.accuracyM <= plafond }
    }

    /** Distance maximale entre deux fixes retenus. */
    fun derive(fixes: List<Fix>): Double {
        var max = 0.0
        for (i in fixes.indices) for (j in i + 1 until fixes.size) {
            val d = haversine(fixes[i].lat, fixes[i].lon, fixes[j].lat, fixes[j].lon)
            if (d > max) max = d
        }
        return max
    }

    /**
     * Position retenue : **médiane** des latitudes et des longitudes, pas moyenne.
     * Un seul fix aberrant — réflexion sur un mur, passage en réseau — décale une
     * moyenne de plusieurs mètres ; la médiane l'ignore.
     */
    fun fusionne(fixes: List<Fix>, debutMs: Long): Resultat? {
        val gardes = retenus(fixes, debutMs)
        if (gardes.isEmpty()) return null
        return Resultat(
            lat = mediane(gardes.map { it.lat }),
            lon = mediane(gardes.map { it.lon }),
            accuracyM = mediane(gardes.map { it.accuracyM }),
            retenus = gardes.size,
            deriveM = derive(gardes)
        )
    }

    /**
     * Arrêt anticipé : inutile de faire attendre trente secondes quelqu'un qui a
     * déjà une position stable au mètre près. Il faut assez de fixes, une bonne
     * précision médiane, et une dérive faible — les trois, sinon on attend.
     */
    fun peutConclure(r: Resultat?): Boolean =
        r != null && r.retenus >= 8 && r.accuracyM <= 6.0 && r.deriveM <= 4.0
}

/**
 * Capture précise : compte à rebours, jauge de précision en direct, et un bouton
 * « Valider maintenant » toujours disponible — sur le terrain on n'impose pas
 * trente secondes d'immobilité à quelqu'un qui est pressé.
 */
@SuppressLint("MissingPermission")
@Composable
fun CapturePreciseDialog(
    dureeS: Int = CAPTURE_PRECISE_S,
    onValider: (lat: Double, lon: Double, accuracyM: Double) -> Unit,
    onAnnuler: () -> Unit
) {
    val context = LocalContext.current
    val fixes = remember { mutableStateListOf<GpsFusion.Fix>() }
    val debut = remember { SystemClock.elapsedRealtime() }
    var restant by remember { mutableIntStateOf(dureeS) }
    var fini by remember { mutableStateOf(false) }

    val resultat = GpsFusion.fusionne(fixes.toList(), debut)
    val derniere = fixes.lastOrNull()?.accuracyM

    // Android bride le GNSS quand l'écran s'éteint : une capture de trente
    // secondes, écran verrouillé, ne rapporterait que deux ou trois fixes.
    DisposableEffect(Unit) {
        val fenetre = (context as? Activity)?.window
        fenetre?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val client = LocationServices.getFusedLocationProviderClient(context)
        val rappel = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                for (loc in r.locations) {
                    if (!loc.hasAccuracy()) continue
                    fixes.add(GpsFusion.Fix(loc.latitude, loc.longitude,
                        loc.accuracy.toDouble(), SystemClock.elapsedRealtime()))
                }
            }
        }
        val accorde = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (accorde) {
            val requete = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(1_000L)
                .setWaitForAccurateLocation(false)
                .build()
            client.requestLocationUpdates(requete, rappel, Looper.getMainLooper())
        }
        onDispose {
            client.removeLocationUpdates(rappel)
            fenetre?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(Unit) {
        while (restant > 0 && !fini) {
            delay(1_000)
            restant -= 1
            val r = GpsFusion.fusionne(fixes.toList(), debut)
            if (GpsFusion.peutConclure(r)) fini = true
        }
        fini = true
    }

    // Le `LaunchedEffect` ne peut pas appeler `onValider` lui-même : il serait
    // annulé par la recomposition qui suit. On conclut ici, une seule fois.
    LaunchedEffect(fini) {
        if (fini) {
            val r = GpsFusion.fusionne(fixes.toList(), debut)
            if (r != null) onValider(r.lat, r.lon, r.accuracyM) else onAnnuler()
        }
    }

    val ecoule = dureeS - restant
    AlertDialog(
        onDismissRequest = { },   // capture en cours : on sort par un bouton
        title = { Text("🎯 Capture précise") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { if (dureeS > 0) ecoule / dureeS.toFloat() else 1f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        derniere == null -> "Recherche des satellites…"
                        else -> "±" + derniere.roundToInt() + " m"
                    },
                    fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = couleurPrecision(derniere)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (resultat != null)
                        resultat.retenus.toString() + " mesures retenues · position à ±" +
                            resultat.accuracyM.roundToInt() + " m"
                    else "Aucune mesure exploitable pour l'instant",
                    fontSize = 13.sp
                )
                Text(restant.toString() + " s restantes", fontSize = 13.sp, color = Color(0xFF666666))

                if (resultat != null && resultat.deriveM > GpsFusion.DERIVE_ALERTE_M) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "⚠️ Les mesures s'éloignent de " + resultat.deriveM.roundToInt() +
                            " m : reste immobile devant le PM.",
                        fontSize = 13.sp, color = Color(0xFFD32F2F)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tiens le téléphone à hauteur de poitrine, dégagé du mur et de " +
                        "l'armoire : le métal renvoie le signal.",
                    fontSize = 12.sp, color = Color(0xFF666666)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { fini = true }, enabled = resultat != null) {
                Text("Valider maintenant")
            }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } }
    )
}

/** Vert sous 8 m, orange jusqu'à 20 m, rouge au-delà — le seuil du terrain. */
private fun couleurPrecision(precision: Double?): Color = when {
    precision == null -> Color(0xFF666666)
    precision <= 8.0 -> Color(0xFF2E7D32)
    precision <= 20.0 -> Color(0xFFE65100)
    else -> Color(0xFFD32F2F)
}

/** Formatage court d'une précision, pour les libellés de boutons. */
fun precisionCourte(m: Double?): String =
    if (m == null) "?" else "±" + (if (abs(m) < 10) String.format("%.1f", m) else m.roundToInt().toString()) + " m"
