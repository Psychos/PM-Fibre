package com.pmfibre.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DepBlueDark = Color(0xFF0D47A1)
private val DepGris = Color(0xFF666666)

/**
 * Écran Départements (roadmap 3.1) : choisir les données ARCEP à garder sur
 * l'appareil.
 *
 * Deux règles tenues de la conception :
 *
 * - **Le plafond vient du manifeste** (`max_deps`), pas d'une constante de l'app.
 * - **Rien n'est grisé quand le plafond est atteint.** Une case désactivée ne dit
 *   pas pourquoi ; « 6/6 — décochez un département pour en ajouter un » le dit.
 *   Les cases restent cliquables et c'est le message qui explique le refus.
 *
 * Pas de case à cocher par région : l'Occitanie en compte treize, un clic
 * cocherait treize paquets. La région ne sert qu'à rendre 103 lignes lisibles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var manifeste by remember { mutableStateOf(DepStore.manifesteLocal(context)) }
    var installes by remember { mutableStateOf(DepStore.installes(context).toSet()) }
    var chargement by remember { mutableStateOf(manifeste == null) }
    var enCours by remember { mutableStateOf<String?>(null) }   // code en cours d'installation
    var message by remember { mutableStateOf<String?>(null) }
    var aDecharger by remember { mutableStateOf<DepStore.DepInfo?>(null) }

    LaunchedEffect(Unit) {
        chargement = true
        try {
            DepStore.recupereManifeste(context, force = true)?.let { manifeste = it }
            DepStore.marqueVerifiee(context)
        } catch (e: Exception) {
            // Hors ligne : on continue sur le manifeste en cache, s'il y en a un.
            if (manifeste == null) message = "Liste indisponible (hors ligne ?) — ${errorMessage(e)}"
        }
        chargement = false
    }

    val m = manifeste
    val plafond = m?.maxDeps ?: 6
    val tropVieux = m != null && DepStore.versionApp(context) < m.minAppVersion

    fun recharge() {
        installes = DepStore.installes(context).toSet()
        PmRepository.reload(context)
        // Le périmètre a changé : le prochain différentiel ne vaudrait rien.
        Sync.reinitialise(context)
        onChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Départements") },
                navigationIcon = {
                    Text("←  ", color = Color.White, fontSize = 22.sp,
                        modifier = Modifier.clickable { onBack() }.padding(start = 12.dp, end = 4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DepBlueDark, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    "${installes.size}/$plafond département(s) installé(s)",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, color = DepBlueDark
                )
                if (installes.size >= plafond) {
                    Text("Décochez un département pour en ajouter un.",
                        fontSize = 13.sp, color = DepGris)
                }
                m?.let {
                    Text("Données ${it.dataset}", fontSize = 12.sp, color = DepGris)
                }
                if (tropVieux) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "⚠️ Ces données demandent une version plus récente de l'application. " +
                            "Mets l'app à jour avant d'installer un département.",
                        fontSize = 13.sp, color = Color(0xFFB71C1C)
                    )
                }
                message?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = DepBlueDark)
                }
            }
            HorizontalDivider()

            if (chargement && m == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            if (m == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Aucune liste de départements disponible. Reviens quand tu auras du réseau.",
                        fontSize = 14.sp, color = DepGris)
                }
                return@Column
            }

            // Regroupement visuel par région, dans l'ordre du manifeste.
            val parRegion = m.deps.groupBy { it.region }.toSortedMap()
            LazyColumn(Modifier.fillMaxSize()) {
                for ((region, deps) in parRegion) {
                    item(key = "r-$region") {
                        Text(
                            region.uppercase(),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DepGris,
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFFF0F0F0))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    items(deps, key = { it.code }) { d ->
                        val installe = d.code in installes
                        val occupe = enCours != null
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !occupe) {
                                when {
                                    installe -> aDecharger = d
                                    tropVieux ->
                                        message = "Mets d'abord l'application à jour."
                                    installes.size >= plafond ->
                                        message = "${installes.size}/$plafond — décochez un " +
                                            "département pour en ajouter un."
                                    else -> {
                                        message = null
                                        enCours = d.code
                                        scope.launch {
                                            try {
                                                val diff = withContext(Dispatchers.IO) {
                                                    DepStore.installe(context, d)
                                                }
                                                withContext(Dispatchers.IO) { recharge() }
                                                message = "${d.nom} installé (${d.pm} PM)" +
                                                    resume(diff)
                                            } catch (e: Exception) {
                                                message = "Échec : ${errorMessage(e)}"
                                            }
                                            enCours = null
                                        }
                                    }
                                }
                            }.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = installe, onCheckedChange = null, enabled = !occupe)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text("${d.code} — ${d.nom}", fontSize = 15.sp)
                                Text(
                                    "${d.pm} PM · ${d.exact} position(s) exacte(s) · " +
                                        "${d.size / 1024} Ko",
                                    fontSize = 12.sp, color = DepGris
                                )
                            }
                            if (enCours == d.code) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                        }
                        HorizontalDivider(color = Color(0xFFEEEEEE))
                    }
                }
                item(key = "pied") {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Données ARCEP (ZAPM), licence ouverte. Elles sont téléchargées " +
                                "à la demande : l'application ne les embarque plus.",
                            fontSize = 12.sp, color = DepGris
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Confirmation de déchargement : dire ce qui part ET ce qui reste (roadmap 3.3).
    aDecharger?.let { d ->
        AlertDialog(
            onDismissRequest = { aDecharger = null },
            title = { Text("Décharger ${d.nom} ?") },
            text = {
                Text(
                    "Les ${d.pm} PM ARCEP de ce département seront supprimés de l'appareil.\n\n" +
                        "Tes positions enregistrées, les PM que tu as ajoutés et les commentaires " +
                        "sont conservés : ils reviendront si tu réinstalles le département."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    aDecharger = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            DepStore.decharge(context, d.code)
                            recharge()   // relit les paquets : à ne pas faire sur le fil principal
                        }
                        message = "${d.nom} déchargé."
                    }
                }) { Text("Décharger") }
            },
            dismissButton = {
                TextButton(onClick = { aDecharger = null }) { Text("Annuler") }
            }
        )
    }
}

/** « 12 PM ajoutés, 3 retirés » — vide à la première installation (roadmap 3.4). */
private fun resume(d: DepStore.Diff): String = when {
    d.ajoutes == 0 && d.retires == 0 -> ""
    else -> " · ${d.ajoutes} PM ajouté(s), ${d.retires} retiré(s)"
}

/** Bandeau de mise à jour, posé en haut de l'écran principal (roadmap 3.4). */
@Composable
fun BandeauMiseAJour(dataset: String, onOuvrir: () -> Unit, onPlusTard: () -> Unit,
                     onIgnorer: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFFFF8E1)).padding(12.dp)
    ) {
        Text("Nouvelles données ARCEP disponibles ($dataset).",
            fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onIgnorer) { Text("Ignorer cette version") }
            TextButton(onClick = onPlusTard) { Text("Plus tard") }
            TextButton(onClick = onOuvrir) { Text("Mettre à jour") }
        }
    }
}
