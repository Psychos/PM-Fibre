package com.pmfibre.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Ce qu'un technicien ajoute depuis le terrain sur une fiche : indication
 * d'acces, etiquettes rapides, photos (roadmap 3.6 et 3.7).
 *
 * Trois blocs sortis de `PmDetailScreen`, qui est deja long, mais qui ecrivent
 * tous selon la meme regle : **local d'abord**. Le PM qu'on a mis un quart
 * d'heure a trouver est souvent au fond d'une zone sans reseau ; attendre le
 * serveur pour enregistrer ce qu'on vient d'apprendre reviendrait a ne rien
 * enregistrer du tout. `MetaStore` et `PhotoStore` gardent, `Sync` remonte.
 */

private val Orange: Color @Composable get() = LocalCouleursPm.current.avert

// ---------------------------------------------------------------- Acces

/**
 * Indication d'acces, **en tete de fiche** (roadmap 3.7).
 *
 * Sa place n'est pas decorative : « troisieme maison apres le virage, le PM est
 * derriere le transformateur » est l'information qui fait gagner les dernieres
 * minutes, et elle doit se lire sans derouler la fiche.
 */
@Composable
fun BlocAcces(code: String?, meta: PmMeta, onModifier: () -> Unit) {
    val context = LocalContext.current
    if (meta.note == null && !meta.aUnPointAcces) {
        OutlinedButton(
            onClick = onModifier, enabled = code != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("🔑 Indiquer comment y accéder") }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("🔑 Accès", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            meta.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 15.sp)
            }
            meta.auteurAcces?.let {
                Spacer(Modifier.height(2.dp))
                Text("— $it", fontSize = 12.sp, color = Color.Gray)
            }
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (meta.aUnPointAcces) {
                    // Deuxieme couple de coordonnees : « ou se garer / par ou
                    // entrer ». Le GPS de la voiture doit viser celui-la, pas le
                    // PM, qui peut etre au fond d'un terrain sans acces direct.
                    OutlinedButton(onClick = {
                        openItinerary(context, meta.accesLat!!, meta.accesLon!!,
                            (code ?: "PM") + " (accès)")
                    }) { Text("🚗 Y aller (accès)") }
                }
                TextButton(onClick = onModifier) { Text("✏️ Modifier") }
            }
        }
    }
}

/**
 * Saisie de l'indication d'acces et de son point d'arrivee facultatif.
 *
 * La position se prend sur place, au bouton : personne ne tape des coordonnees
 * a la main devant un portail, et les champs restent surtout pour corriger ou
 * effacer.
 */
@SuppressLint("MissingPermission")
@Composable
fun DialogueAcces(
    noteInitiale: String?, latInitiale: Double?, lonInitiale: Double?,
    onDismiss: () -> Unit,
    onValider: (String?, Double?, Double?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(noteInitiale ?: "") }
    var lat by remember { mutableStateOf(latInitiale?.toString() ?: "") }
    var lon by remember { mutableStateOf(lonInitiale?.toString() ?: "") }
    var occupe by remember { mutableStateOf(false) }

    fun prend() {
        occupe = true
        val client = LocationServices.getFusedLocationProviderClient(context)
        scope.launch {
            try {
                val loc = client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
                ).await()
                if (loc != null) {
                    lat = String.format(Locale.US, "%.6f", loc.latitude)
                    lon = String.format(Locale.US, "%.6f", loc.longitude)
                } else {
                    Toast.makeText(context, "Position introuvable (GPS ?)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                occupe = false
            }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) prend()
        else Toast.makeText(context, "Localisation refusée", Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comment y accéder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = note, onValueChange = { if (it.length <= 255) note = it },
                    label = { Text("En une phrase") },
                    placeholder = { Text("Derrière le transformateur, portail vert") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("${note.length}/255", fontSize = 11.sp, color = Color.Gray)

                Spacer(Modifier.height(12.dp))
                Text("Point d'accès (facultatif)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Où se garer, par où entrer. À laisser vide si on arrive " +
                        "directement sur le PM.",
                    fontSize = 12.sp, color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat, onValueChange = { lat = it }, label = { Text("Lat") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lon, onValueChange = { lon = it }, label = { Text("Lon") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !occupe,
                        onClick = {
                            val ok = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (ok) prend()
                            else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    ) { Text(if (occupe) "Localisation…" else "📍 Je suis au point d'accès") }
                    if (lat.isNotBlank() || lon.isNotBlank()) {
                        TextButton(onClick = { lat = ""; lon = "" }) { Text("Effacer") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val la = lat.trim().replace(',', '.').toDoubleOrNull()
                val lo = lon.trim().replace(',', '.').toDoubleOrNull()
                // Une seule des deux coordonnees ne veut rien dire, et le serveur
                // la refuse en 422 : autant le dire ici, hors ligne compris.
                if ((la == null) != (lo == null)) {
                    Toast.makeText(
                        context, "Latitude et longitude vont ensemble", Toast.LENGTH_LONG
                    ).show()
                } else {
                    onValider(note.trim().ifEmpty { null }, la, lo)
                }
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ----------------------------------------------------------- Etiquettes

/** Les etiquettes posees, en une ligne qui se deroule (roadmap 3.6). */
@Composable
fun BlocEtiquettes(code: String?, tags: List<String>, onModifier: () -> Unit) {
    if (tags.isEmpty()) {
        OutlinedButton(
            onClick = onModifier, enabled = code != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("🏷️ Ajouter des étiquettes") }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tags.forEach { EtiquettePuce(it) }
        }
        TextButton(onClick = onModifier) { Text("✏️ Modifier les étiquettes") }
    }
}

@Composable
private fun EtiquettePuce(slug: String) {
    val fond = if (Etiquettes.difficile(listOf(slug)))
        MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(fond)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text("${Etiquettes.icone(slug)} ${Etiquettes.libelle(slug)}", fontSize = 13.sp)
    }
}

/**
 * Multi-selection, deux familles. Rien n'est envoye d'ici : l'appelant ecrit
 * dans `MetaStore`, qui vaut hors ligne.
 */
@Composable
fun DialogueEtiquettes(
    initiales: List<String>,
    onDismiss: () -> Unit,
    onValider: (List<String>) -> Unit
) {
    val choisies = remember { mutableStateListOf<String>().apply { addAll(initiales) } }
    // Une etiquette venue d'un serveur plus recent que l'application n'apparait
    // dans aucune des deux familles : on la conserve sans l'afficher plutot que
    // de la perdre au premier enregistrement.
    val inconnues = remember { initiales.filterNot { Etiquettes.connue(it) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Étiquettes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Ce qui aide le suivant à le trouver.", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                FamilleEtiquettes("Accès", Etiquettes.acces, choisies)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                FamilleEtiquettes("Type de site", Etiquettes.site, choisies)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onValider(Etiquettes.ordonne(choisies.toList() + inconnues))
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun FamilleEtiquettes(
    titre: String, liste: List<Etiquettes.Etiquette>, choisies: MutableList<String>
) {
    Text(titre, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    liste.forEach { e ->
        val coche = choisies.contains(e.slug)
        Row(
            Modifier.fillMaxWidth().clickable {
                if (coche) choisies.remove(e.slug) else choisies.add(e.slug)
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = coche, onCheckedChange = {
                if (coche) choisies.remove(e.slug) else choisies.add(e.slug)
            })
            Text("${e.icone} ${e.libelle}", fontSize = 15.sp)
        }
    }
}

// --------------------------------------------------------------- Photos

private const val COTE_VIGNETTE = 96

/**
 * Photos de la fiche : celles du serveur, et celles qui attendent le reseau
 * (roadmap 3.7).
 *
 * Deux boutons plutot qu'un : une photo du PM lui-meme et une photo de l'acces
 * ne repondent pas a la meme question, et c'est au moment de la prise de vue
 * qu'on sait laquelle on fait.
 */
@Composable
fun BlocPhotos(code: String?, distantes: List<ApiClient.PhotoMeta>, onChange: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enAttente by remember(code) { mutableStateOf(PhotoStore.enAttentePour(context, code)) }
    var cible by remember { mutableStateOf<Pair<File, String>?>(null) }
    var agrandie by remember { mutableStateOf<ApiClient.PhotoMeta?>(null) }
    var occupe by remember { mutableStateOf(false) }

    val capture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val vise = cible
        cible = null
        if (vise != null) {
            val (fichier, kind) = vise
            if (!ok || code == null) {
                fichier.delete()
            } else {
                occupe = true
                scope.launch {
                    val octets = withContext(Dispatchers.IO) {
                        val o = PhotoStore.compresse(fichier)
                        fichier.delete()
                        o
                    }
                    if (octets == null) {
                        Toast.makeText(context, "Photo illisible", Toast.LENGTH_LONG).show()
                    } else {
                        withContext(Dispatchers.IO) { PhotoStore.ajoute(context, code, kind, octets) }
                        enAttente = PhotoStore.enAttentePour(context, code)
                        Toast.makeText(
                            context, "Photo enregistrée (envoi à la synchro)", Toast.LENGTH_SHORT
                        ).show()
                        onChange()
                    }
                    occupe = false
                }
            }
        }
    }

    fun photographie(kind: String) {
        if (code == null) return
        try {
            val (f, uri) = PhotoStore.uriDeCapture(context)
            cible = f to kind
            capture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Appareil photo indisponible", Toast.LENGTH_LONG).show()
        }
    }

    Text("Photos", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Text(
        "Une photo fait les derniers mètres, là où le GPS s'arrête.",
        fontSize = 12.sp, color = Color.Gray
    )
    Spacer(Modifier.height(8.dp))

    if (distantes.isNotEmpty() || enAttente.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            distantes.forEach { p -> VignetteDistante(p, onClick = { agrandie = p }) }
            enAttente.forEach { e ->
                VignetteLocale(PhotoStore.fichierEnAttente(context, e), e.kind, onSupprimer = {
                    PhotoStore.retire(context, e.fichier)
                    enAttente = PhotoStore.enAttentePour(context, code)
                    onChange()
                })
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { photographie("pm") }, enabled = code != null && !occupe) {
            Text("📷 Le PM")
        }
        OutlinedButton(onClick = { photographie("acces") }, enabled = code != null && !occupe) {
            Text("📷 L'accès")
        }
    }

    agrandie?.let { p ->
        DialoguePhoto(p, onDismiss = { agrandie = null }, onSupprimee = {
            agrandie = null
            onChange()
        })
    }
}

/** Charge une photo du serveur : cache d'abord, reseau seulement si besoin. */
@Composable
private fun rememberPhoto(id: Int, coteMax: Int): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, id, coteMax) {
        value = withContext(Dispatchers.IO) {
            val f = PhotoStore.enCache(context, id) ?: run {
                val token = SessionStore.token
                if (token == null) null
                else try {
                    PhotoStore.ecritCache(context, id, ApiClient.downloadPhoto(token, id))
                } catch (_: Exception) {
                    null
                }
            }
            f?.let { PhotoStore.decode(it, coteMax) }
        }
    }
}

@Composable
private fun VignetteDistante(p: ApiClient.PhotoMeta, onClick: () -> Unit) {
    val bmp by rememberPhoto(p.id, 320)
    CadreVignette(bmp, p.kind, onClick = onClick)
}

@Composable
private fun VignetteLocale(fichier: File, kind: String, onSupprimer: () -> Unit) {
    var confirme by remember { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(initialValue = null, fichier.path) {
        value = withContext(Dispatchers.IO) { PhotoStore.decode(fichier, 320) }
    }
    CadreVignette(bmp, kind, onClick = { confirme = true }, enAttente = true)
    if (confirme) {
        AlertDialog(
            onDismissRequest = { confirme = false },
            title = { Text("Photo en attente d'envoi") },
            text = { Text("Elle partira à la prochaine synchronisation. La supprimer ?") },
            confirmButton = {
                TextButton(onClick = { confirme = false; onSupprimer() }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirme = false }) { Text("Garder") } }
        )
    }
}

@Composable
private fun CadreVignette(
    bmp: Bitmap?, kind: String, onClick: () -> Unit, enAttente: Boolean = false
) {
    Box(
        Modifier.size(COTE_VIGNETTE.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(COTE_VIGNETTE.dp)
            )
        }
        val marque = if (enAttente) "⏳" else if (kind == "acces") "🔑" else ""
        if (marque.isNotEmpty()) {
            Text(
                marque, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
            )
        }
    }
}

/** Photo en grand, avec suppression si on en est l'auteur (ou admin). */
@Composable
private fun DialoguePhoto(
    p: ApiClient.PhotoMeta, onDismiss: () -> Unit, onSupprimee: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bmp by rememberPhoto(p.id, 1200)
    var occupe by remember { mutableStateOf(false) }
    val mienne = SessionStore.isAdmin || (p.author != null && p.author == SessionStore.username)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.kind == "acces") "🔑 Accès" else "📷 Le PM") },
        text = {
            Column {
                val image = bmp
                if (image == null) {
                    Text(
                        "Chargement… (ou indisponible hors ligne)",
                        fontSize = 13.sp, color = Color.Gray
                    )
                } else {
                    Image(
                        bitmap = image.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(p.author, p.createdAt?.take(10)).joinToString(" · "),
                    fontSize = 12.sp, color = Color.Gray
                )
            }
        },
        confirmButton = {
            if (mienne) {
                TextButton(enabled = !occupe, onClick = {
                    val token = SessionStore.token ?: return@TextButton
                    occupe = true
                    scope.launch {
                        try {
                            ApiClient.deletePhoto(token, p.id)
                            withContext(Dispatchers.IO) { PhotoStore.oublieCache(context, p.id) }
                            onSupprimee()
                        } catch (e: Exception) {
                            Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
                        } finally {
                            occupe = false
                        }
                    }
                }) { Text("🗑️ Supprimer", color = Orange) }
            } else {
                TextButton(onClick = onDismiss) { Text("Fermer") }
            }
        },
        dismissButton = { if (mienne) TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
