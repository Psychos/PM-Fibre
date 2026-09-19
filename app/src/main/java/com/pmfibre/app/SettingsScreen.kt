package com.pmfibre.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Les rubriques des paramètres (roadmap 3.8) : des sous-écrans, pas une longue page. */
private enum class Rubrique { RACINE, AFFICHAGE, CARTE, PROFIL, DONNEES, MAJ, APROPOS }

/**
 * ⚙️ Paramètres. Remplace le fourre-tout qu'était l'onglet Compte, où voisinaient
 * le profil, l'export de fichiers, le Hall of Fame, la gestion des comptes et la
 * déconnexion — sans hiérarchie, dans l'ordre où les fonctions étaient arrivées.
 *
 * Chaque rubrique tient sur un écran : on cherche « le mot de passe » ou « vider
 * le cache », pas « la troisième section en partant du bas ».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDeps: () -> Unit,
    onOpenAdmin: () -> Unit,
    onLogout: () -> Unit,
    syncInfo: String?,
    majDataset: String?
) {
    var rubrique by remember { mutableStateOf(Rubrique.RACINE) }

    // Retour : d'abord remonter à la liste des rubriques, ensuite seulement fermer
    // les paramètres. Enregistré après celui de `MainScreen`, il a la priorité.
    BackHandler(enabled = rubrique != Rubrique.RACINE) { rubrique = Rubrique.RACINE }

    val titre = when (rubrique) {
        Rubrique.RACINE -> "Paramètres"
        Rubrique.AFFICHAGE -> "Affichage"
        Rubrique.CARTE -> "Carte"
        Rubrique.PROFIL -> "Profil"
        Rubrique.DONNEES -> "Données"
        Rubrique.MAJ -> "Mises à jour"
        Rubrique.APROPOS -> "À propos"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titre) },
                navigationIcon = {
                    Text("←  ", fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clickable {
                                if (rubrique == Rubrique.RACINE) onBack() else rubrique = Rubrique.RACINE
                            }
                            .padding(start = 12.dp, end = 4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            when (rubrique) {
                Rubrique.RACINE -> RubriqueRacine(
                    onOpenDeps = onOpenDeps,
                    onOpenAdmin = onOpenAdmin,
                    majDataset = majDataset,
                    onChoisir = { rubrique = it }
                )
                Rubrique.AFFICHAGE -> RubriqueAffichage()
                Rubrique.CARTE -> RubriqueCarte()
                Rubrique.PROFIL -> RubriqueProfil(syncInfo = syncInfo, onLogout = onLogout)
                Rubrique.DONNEES -> RubriqueDonnees()
                Rubrique.MAJ -> RubriqueMaj()
                Rubrique.APROPOS -> RubriqueApropos()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- briques d'UI

@Composable
private fun LigneRubrique(titre: String, sous: String, badge: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(titre, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (badge) {
                Spacer(Modifier.height(0.dp))
                Text("  ●", fontSize = 17.sp, color = LocalCouleursPm.current.avert)
            }
        }
        Text(sous, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun TitreSection(t: String) {
    Spacer(Modifier.height(16.dp))
    Text(t, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LigneInterrupteur(titre: String, sous: String, valeur: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!valeur) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.fillMaxWidth(0.8f)) {
            Text(titre, fontSize = 16.sp)
            Text(sous, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.fillMaxWidth(0.1f))
        Switch(checked = valeur, onCheckedChange = onChange)
    }
}

@Composable
private fun LigneChoix(titre: String, choisi: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = choisi, onClick = onClick)
        Text(titre, fontSize = 16.sp)
    }
}

@Composable
private fun Paragraphe(t: String) {
    Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

// ------------------------------------------------------------------- rubriques

@Composable
private fun RubriqueRacine(
    onOpenDeps: () -> Unit,
    onOpenAdmin: () -> Unit,
    majDataset: String?,
    onChoisir: (Rubrique) -> Unit
) {
    val context = LocalContext.current
    val deps = remember { DepStore.installes(context) }
    val max = remember { DepStore.manifesteLocal(context)?.maxDeps ?: 6 }

    LigneRubrique(
        "📦 Départements",
        if (deps.isEmpty()) "Aucun installé — commence ici"
        else "${deps.size}/$max · " + deps.joinToString(", "),
        onClick = onOpenDeps
    )
    LigneRubrique("🎨 Affichage", "Thème, contraste, taille du texte") { onChoisir(Rubrique.AFFICHAGE) }
    LigneRubrique("🗺️ Carte", "Fond de carte, libellés, cache hors-ligne") { onChoisir(Rubrique.CARTE) }
    LigneRubrique("👤 Profil", "E-mail, mot de passe, contributions, déconnexion") { onChoisir(Rubrique.PROFIL) }
    LigneRubrique("💾 Données", "Import/export, resynchronisation") { onChoisir(Rubrique.DONNEES) }
    LigneRubrique(
        "🔄 Mises à jour",
        if (majDataset != null) "Nouvelle version des données : $majDataset" else "Vérifier les données ARCEP",
        badge = majDataset != null
    ) { onChoisir(Rubrique.MAJ) }
    if (SessionStore.isAdmin) {
        LigneRubrique("👥 Administration", "Comptes, codes d'invitation", onClick = onOpenAdmin)
    }
    LigneRubrique("ℹ️ À propos", "Version, sources, code source") { onChoisir(Rubrique.APROPOS) }
}

@Composable
private fun RubriqueAffichage() {
    val context = LocalContext.current

    TitreSection("Thème")
    LigneChoix("Automatique (suit le téléphone)", Settings.theme == ThemeChoix.AUTO) {
        Settings.setTheme(context, ThemeChoix.AUTO)
    }
    LigneChoix("Clair", Settings.theme == ThemeChoix.CLAIR) { Settings.setTheme(context, ThemeChoix.CLAIR) }
    LigneChoix("Sombre", Settings.theme == ThemeChoix.SOMBRE) { Settings.setTheme(context, ThemeChoix.SOMBRE) }

    TitreSection("Lisibilité")
    LigneInterrupteur(
        "Contraste élevé",
        "Couleurs tranchées, fonds unis. Pensé pour l'écran lu en plein soleil.",
        Settings.contrasteEleve
    ) { Settings.setContraste(context, it) }

    Spacer(Modifier.height(8.dp))
    Text("Taille du texte : ${(Settings.echelleTexte * 100).toInt()} %",
        fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
    Slider(
        value = Settings.echelleTexte,
        onValueChange = { Settings.setEchelleTexte(context, it) },
        valueRange = 0.85f..1.45f,
        steps = 3,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Paragraphe(
        "Le réglage s'applique à toute l'application, immédiatement. Il s'ajoute à " +
            "celui du téléphone : si Android est déjà en gros caractères, 100 % suffit."
    )

    TitreSection("Repères de position")
    Paragraphe(
        "✅ rond cyan = position relevée sur place · ≈ carré rouge = centre de zone " +
            "ARCEP. La forme dit la même chose que la couleur : environ 8 % des hommes " +
            "confondent le rouge et le vert, et c'est la population du terrain."
    )
}

@Composable
private fun RubriqueCarte() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tailleCache by remember { mutableStateOf<Long?>(null) }
    var confirmVider by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(revision) {
        tailleCache = withContext(Dispatchers.IO) { tailleDossier(dossierTuiles()) }
    }

    TitreSection("Fond de carte par défaut")
    LigneChoix("Plan (OpenStreetMap)", Settings.carteFond == FondCarte.PLAN) {
        Settings.setCarteFond(context, FondCarte.PLAN)
    }
    LigneChoix("Vue aérienne (ortho IGN)", Settings.carteFond == FondCarte.ORTHO) {
        Settings.setCarteFond(context, FondCarte.ORTHO)
    }
    Paragraphe(
        "La vue aérienne (BD ORTHO, 20 cm/pixel) montre la haie, le recoin et le " +
            "chemin d'accès. La bascule reste disponible en haut de la carte."
    )

    TitreSection("Points")
    LigneInterrupteur("Libellés des PM", "Affiche la référence à côté du point, une fois zoomé.",
        Settings.carteLibelles) { Settings.setCarteLibelles(context, it) }
    LigneInterrupteur("Points plus gros", "Plus faciles à viser avec des gants ou au soleil.",
        Settings.cartePointsGros) { Settings.setCartePointsGros(context, it) }

    TitreSection("Cache hors-ligne")
    Paragraphe(
        "Les tuiles déjà affichées sont gardées et restent visibles sans réseau. " +
            "Le vider libère de la place ; la carte redeviendra grise hors couverture " +
            "tant que les zones n'auront pas été réaffichées."
    )
    Text(
        "Taille actuelle : " + (tailleCache?.let { formatOctets(it) } ?: "calcul…"),
        fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    OutlinedButton(
        onClick = { confirmVider = true },
        modifier = Modifier.padding(horizontal = 16.dp)
    ) { Text("🧹 Vider le cache des tuiles") }

    if (confirmVider) {
        AlertDialog(
            onDismissRequest = { confirmVider = false },
            title = { Text("Vider le cache ?") },
            text = { Text("Les fonds de carte seront retéléchargés au besoin. Aucune " +
                "position, aucun commentaire n'est concerné.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmVider = false
                    scope.launch {
                        withContext(Dispatchers.IO) { videDossier(dossierTuiles()) }
                        revision++
                        Toast.makeText(context, "Cache vidé", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Vider") }
            },
            dismissButton = { TextButton(onClick = { confirmVider = false }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun RubriqueProfil(syncInfo: String?, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<ApiClient.Stats?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var showHallOfFame by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val token = SessionStore.token ?: return@LaunchedEffect
        try { stats = ApiClient.fetchMyStats(token) } catch (_: Exception) {}
    }

    if (showHallOfFame) HallOfFameDialog(onDismiss = { showHallOfFame = false })
    if (showProfile) {
        ProfileDialog(
            onDismiss = { showProfile = false },
            onSave = { email, current, newPass ->
                val token = SessionStore.token
                if (token != null) scope.launch {
                    try {
                        ApiClient.updateProfile(token, email, current, newPass)
                        showProfile = false
                        Toast.makeText(context, "Profil mis à jour ✅", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    TitreSection("Compte")
    Text(
        (SessionStore.username ?: "—") + if (SessionStore.isAdmin) "  👑 admin" else "",
        fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    stats?.let {
        Text(
            "${it.positions} position(s) · ${it.confirmations} confirmation(s) · " +
                "${it.comments} commentaire(s)",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
    syncInfo?.let { Paragraphe(it) }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showProfile = true }) { Text("✏️ Modifier") }
        OutlinedButton(onClick = { showHallOfFame = true }) { Text("🏆 Hall of Fame") }
    }
    Paragraphe("E-mail et mot de passe. L'e-mail est facultatif : il ne sert qu'à te " +
        "joindre si ton compte pose question.")

    // La déconnexion est en bas et en rouge : c'est l'action qu'on ne veut pas
    // déclencher en cherchant autre chose. Une session de moins, c'est aussi une
    // place libérée sur les cinq du compte.
    TitreSection("Fin de session")
    OutlinedButton(
        onClick = { confirmLogout = true },
        modifier = Modifier.padding(horizontal = 16.dp)
    ) { Text("🔓 Se déconnecter", color = LocalCouleursPm.current.approx) }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Se déconnecter ?") },
            text = { Text("Les départements installés et les positions déjà enregistrées " +
                "restent sur l'appareil. Il faudra retaper le mot de passe.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    SessionStore.clear(context)
                    onLogout()
                }) { Text("Se déconnecter") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun RubriqueDonnees() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var savedCount by remember { mutableIntStateOf(PmRepository.savedCount) }
    var occupe by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            message = try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(PmRepository.exportJson().toByteArray())
                }
                "Base exportée ($savedCount position(s))."
            } catch (e: Exception) { "Échec de l'export : ${e.message}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            message = try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val n = PmRepository.importJson(context, text)
                savedCount = PmRepository.savedCount
                "$n position(s) importée(s)."
            } catch (e: Exception) { "Échec de l'import : ${e.message}" }
        }
    }

    TitreSection("Synchronisation")
    Text("Positions enregistrées sur cet appareil : $savedCount",
        fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            val token = SessionStore.token ?: return@OutlinedButton
            occupe = true
            scope.launch {
                message = "Synchronisation…"
                message = try {
                    val r = Sync.run(context, token)
                    savedCount = PmRepository.savedCount
                    "Synchronisé : $r"
                } catch (e: Exception) { "Échec : ${errorMessage(e)}" }
                occupe = false
            }
        },
        enabled = !occupe,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) { Text("🔄 Synchroniser maintenant") }
    Paragraphe("Elle se fait déjà seule à chaque ouverture. Ce bouton sert quand on " +
        "vient de retrouver du réseau et qu'on veut le vérifier tout de suite.")

    TitreSection("Copie de secours (fichier)")
    Paragraphe(
        "En temps normal tout est partagé par le serveur. Ces boutons servent à " +
            "sauvegarder les positions de ce téléphone dans un fichier, ou à récupérer " +
            "un fichier exporté ailleurs."
    )
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val nom = "pm_positions_${SimpleDateFormat("yyyyMMdd", Locale.FRANCE).format(Date())}.json"
            exportLauncher.launch(nom)
        }) { Text("📤 Exporter") }
        OutlinedButton(onClick = {
            importLauncher.launch(arrayOf("application/json", "text/*"))
        }) { Text("📥 Importer") }
    }
    TitreSection("Photos")
    var tailleCache by remember { mutableStateOf(PhotoStore.tailleCache(context)) }
    var enAttente by remember { mutableStateOf(PhotoStore.enAttente(context).size) }
    Text(
        "Photos gardées sur ce téléphone : " + (tailleCache / 1024) + " Ko" +
            if (enAttente > 0) " · $enAttente en attente d'envoi" else "",
        fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp)
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            PhotoStore.videCache(context)
            tailleCache = PhotoStore.tailleCache(context)
        },
        modifier = Modifier.padding(horizontal = 16.dp)
    ) { Text("🧹 Vider le cache des photos") }
    Paragraphe(
        "Ce cache n'est qu'une copie : les photos restent sur le serveur et " +
            "redescendent à l'ouverture d'une fiche. Celles qui attendent l'envoi " +
            "ne sont pas touchées."
    )

    if (message.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun RubriqueMaj() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var etat by remember { mutableStateOf<String?>(null) }
    var occupe by remember { mutableStateOf(false) }
    val installe = remember { DepStore.manifesteLocal(context) }

    TitreSection("Données ARCEP")
    Text("Millésime installé : " + (installe?.dataset ?: "aucun"),
        fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp))
    Text("Dernière vérification : " + formatQuand(DepStore.derniereVerification(context)),
        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            occupe = true
            scope.launch {
                etat = try {
                    val distant = DepStore.recupereManifeste(context, force = true)
                    DepStore.marqueVerifiee(context)
                    when {
                        distant == null -> "Pas de réponse du serveur."
                        DepStore.miseAJourDisponible(context, distant, installe?.dataset) ->
                            "Nouvelle version disponible : ${distant.dataset} — va dans 📦 Départements."
                        else -> "Les données sont à jour (${distant.dataset})."
                    }
                } catch (e: Exception) { "Échec : ${errorMessage(e)}" }
                occupe = false
            }
        },
        enabled = !occupe,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) { Text("🔄 Vérifier maintenant") }
    etat?.let { Paragraphe(it) }

    LigneInterrupteur(
        "Wi-Fi seulement",
        "Ne vérifie les données que sur Wi-Fi. La vérification pèse quelques kilo-octets ; " +
            "le téléchargement d'un département, environ 80 Ko.",
        Settings.majWifiSeulement
    ) { Settings.setMajWifiSeulement(context, it) }

    TitreSection("Application")
    Paragraphe("La mise à jour de l'application elle-même se récupère sur le site : " +
        "l'APK n'est pas distribué par un magasin.")
    OutlinedButton(
        onClick = { openUrl(context, "https://mapm.online") },
        modifier = Modifier.padding(horizontal = 16.dp)
    ) { Text("🌐 Ouvrir mapm.online") }
}

@Composable
private fun RubriqueApropos() {
    val context = LocalContext.current
    val manifeste = remember { DepStore.manifesteLocal(context) }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
    }

    TitreSection("PM Fibre")
    Text("Version $version", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
    Text("PM chargés : ${PmRepository.size}", fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 16.dp))
    Text("Données : ARCEP — " + (manifeste?.dataset ?: "aucune") + " (open data)",
        fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))

    TitreSection("Sources et licences")
    Paragraphe(
        "Zones de mutualisation : ARCEP, open data (Licence Ouverte).\n" +
            "Fond de plan : © contributeurs OpenStreetMap (ODbL).\n" +
            "Vue aérienne : IGN — BD ORTHO, Géoplateforme.\n\n" +
            "Les positions précises n'existent nulle part publiquement : elles sont " +
            "relevées par les utilisateurs et partagées entre eux. Elles n'appartiennent " +
            "à personne d'autre qu'à ceux qui les saisissent."
    )

    TitreSection("Contact")
    Paragraphe("Bugs et suggestions : fibre27@free.fr")
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { openUrl(context, "https://github.com/Psychos/PM-Fibre") }) {
            Text("💻 Code source")
        }
        OutlinedButton(onClick = { openUrl(context, "https://mapm.online") }) { Text("🌐 Site") }
    }
}

// ------------------------------------------------------------------- utilitaires

private fun dossierTuiles(): File? = Configuration.getInstance().osmdroidTileCache

private fun tailleDossier(f: File?): Long {
    if (f == null || !f.exists()) return 0L
    if (f.isFile) return f.length()
    return f.listFiles()?.sumOf { tailleDossier(it) } ?: 0L
}

/** Vide le contenu sans supprimer le dossier : osmdroid le rouvre tel quel. */
private fun videDossier(f: File?) {
    if (f == null || !f.isDirectory) return
    f.listFiles()?.forEach { if (it.isDirectory) { videDossier(it); it.delete() } else it.delete() }
}

private fun formatOctets(o: Long): String = when {
    o >= 1_048_576 -> String.format(Locale.FRANCE, "%.1f Mo", o / 1_048_576.0)
    o >= 1024 -> (o / 1024).toString() + " Ko"
    else -> o.toString() + " o"
}

private fun formatQuand(ms: Long): String {
    if (ms <= 0L) return "jamais"
    val ecart = System.currentTimeMillis() - ms
    return when {
        ecart < 60_000 -> "à l'instant"
        ecart < 3_600_000 -> "il y a ${ecart / 60_000} min"
        ecart < 86_400_000 -> "il y a ${ecart / 3_600_000} h"
        else -> SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(Date(ms))
    }
}
