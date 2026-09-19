package com.pmfibre.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// Les quatre couleurs de l'application, autrefois écrites en dur ici et recopiées
// dans `HelpScreen`, `MapScreen` et `themes.xml`. Elles lisent maintenant le thème
// (voir `Theme.kt`) : les noms d'appel restent, les valeurs suivent le mode clair
// ou sombre et le contraste élevé choisis par l'utilisateur.
private val BlueDark: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
private val BluePrimary: Color @Composable get() = MaterialTheme.colorScheme.primary
private val CouleurExacte: Color @Composable get() = LocalCouleursPm.current.exact
private val OrangeWarn: Color @Composable get() = LocalCouleursPm.current.avert

// Au-delà de cette précision GPS (mètres), on avertit l'utilisateur avant d'enregistrer.
private const val POOR_ACCURACY_M = 15.0

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid : charger la config PUIS fixer le user-agent (obligatoire pour les
        // tuiles OSM, sinon le serveur renvoie 403 et on n'a qu'un quadrillage vide).
        org.osmdroid.config.Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles")
        }
        // Les préférences d'affichage sont lues AVANT la première composition :
        // sinon l'application s'ouvrirait une fraction de seconde en clair chez
        // quelqu'un qui a choisi le mode sombre.
        Settings.load(applicationContext)
        setContent {
            PmFibreTheme {
                Surface { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(false) }
    var forcedLogoutMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Dernier filet. Les magasins se protegent deja chacun (Fichiers), mais
        // une exception qui remonterait jusqu'ici laisserait `loaded` a false :
        // l'ecran resterait vide, et il le resterait a chaque lancement suivant
        // puisque la cause serait toujours la. Mieux vaut ouvrir sur une base
        // incomplete, que la synchro et l'ecran Departements savent reconstruire,
        // que de ne pas ouvrir du tout.
        try {
            withContext(Dispatchers.IO) {
                SessionStore.load(context)
                PmRepository.load(context)
                // Etiquettes et indications d'acces : hors ligne comme le reste
                // (roadmap 3.6). Chargees ici pour que la premiere fiche ouverte
                // les ait deja, sans attendre la synchro.
                MetaStore.charge(context)
                PhotoStore.nettoieTemporaires(context)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // l'ecran quitte la composition : ce n'est pas une panne
        } catch (_: Exception) {
        }
        // Session refusée par le serveur (évincée par une 3ᵉ connexion, expirée après
        // 60 jours, compte désactivé…) : le serveur ne distingue pas la cause exacte au
        // moment du refus, donc on ne l'affirme pas — on oublie le jeton et on repasse à
        // l'écran de connexion avec un message neutre.
        val appContext = context.applicationContext
        ApiClient.onSessionExpired = {
            SessionStore.clearToken(appContext)
            // Le nombre d'appareils autorisés est un réglage du serveur : l'annoncer ici,
            // c'est promettre un chiffre que l'app ne connaît pas (il disait 2, il en vaut 5).
            forcedLogoutMsg = "Session fermée (expirée, ou trop d'appareils connectés sur " +
                "ce compte). Reconnecte-toi."
            loggedIn = false
        }
        loggedIn = SessionStore.isLoggedIn
        loaded = true
    }

    if (!loaded) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Chargement des PM…")
        }
    } else if (!loggedIn) {
        LoginScreen(initialMessage = forcedLogoutMsg,
            onLoggedIn = { forcedLogoutMsg = null; loggedIn = true })
    } else {
        MainScreen(onLogout = { loggedIn = false })
    }
}

/** Écran de connexion : « Se connecter » ou « Créer un compte » (prénom + mot de passe). */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit, initialMessage: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var createMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(SessionStore.username ?: "") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(initialMessage) }

    fun submit() {
        busy = true; message = null
        scope.launch {
            try {
                val t = if (createMode)
                    ApiClient.register(username.trim(), password, email.trim().ifBlank { null }, inviteCode.trim())
                else
                    ApiClient.login(username.trim(), password)
                SessionStore.save(context, t.token, t.username, t.role)
                onLoggedIn()
            } catch (e: Exception) {
                message = errorMessage(e)
            } finally { busy = false }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("PM Fibre", fontWeight = FontWeight.Bold, fontSize = 30.sp, color = BlueDark)
        Spacer(Modifier.height(4.dp))
        Text(if (createMode) "Créer un compte" else "Se connecter", color = Color.Gray)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Prénom") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        if (createMode) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it },
                label = { Text("Code d'invitation (fourni par un collègue)") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail (facultatif)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { submit() },
            enabled = !busy && username.isNotBlank() && password.length >= (if (createMode) 4 else 1) &&
                (!createMode || inviteCode.isNotBlank()),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (createMode) "Créer le compte" else "Se connecter") }

        TextButton(onClick = { createMode = !createMode; message = null }) {
            Text(if (createMode) "J'ai déjà un compte → Se connecter"
                 else "Pas de compte ? → Créer un compte")
        }

        Spacer(Modifier.height(8.dp))
        if (busy) CircularProgressIndicator()
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = OrangeWarn, fontSize = 14.sp)
        }
    }
}

/** Message d'erreur lisible à partir d'une exception réseau/API. */
internal fun errorMessage(e: Exception): String = when (e) {
    is ApiClient.ApiException -> e.message ?: "Erreur ${e.status}"
    // DepStore dit déjà ce qui a échoué (empreinte, archive, HTTP) : le répéter
    // « serveur injoignable » ferait chercher du réseau là où il n'y a rien.
    is DepStore.DepException -> e.message ?: "Téléchargement impossible."
    else -> "Serveur injoignable. Vérifie la connexion. (${e.message})"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pm?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showDeps by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var aideContexte by remember { mutableStateOf(false) }
    var syncInfo by remember { mutableStateOf<String?>(null) }
    var majDisponible by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }   // force le recalcul après un (dé)chargement

    // Synchro bidirectionnelle des positions au démarrage.
    LaunchedEffect(revision) {
        val token = SessionStore.token ?: return@LaunchedEffect
        try {
            syncInfo = "Synchro : " + Sync.run(context, token)
        } catch (e: Exception) {
            syncInfo = "⚠️ Serveur injoignable (${ApiClient.baseUrl}). Es-tu sur le même réseau ? — ${errorMessage(e)}"
        }
    }

    // Vérification de mise à jour des données (roadmap 3.4) : au plus une par
    // 24 h, seulement si le réseau est là, jamais bloquante — un échec ne produit
    // rien d'affiché. Elle ne fait qu'allumer un bandeau ; rien ne se télécharge
    // sans que l'utilisateur ouvre l'écran Départements. `reseauAutorise` et non
    // `reseauDisponible` : le réglage « Wi-Fi seulement » vaut aussi ici.
    LaunchedEffect(Unit) {
        if (!DepStore.verificationDue(context) || !DepStore.reseauAutorise(context)) {
            return@LaunchedEffect
        }
        try {
            val distant = DepStore.recupereManifeste(context) ?: return@LaunchedEffect
            // La question posée est « un département installé est-il périmé ? »,
            // et non « le manifeste a-t-il changé ? » : le manifeste est
            // remplacé par cette vérification même (§ F10). La réponse demande
            // de lire les paquets, donc pas sur le fil principal.
            val aFaire = withContext(Dispatchers.IO) {
                DepStore.miseAJourDisponible(context, distant)
            }
            if (aFaire) majDisponible = distant.dataset
        } catch (_: Exception) {
            // Hors ligne, site indisponible : on réessaiera dans 24 h.
        }
    }

    // Les sous-écrans s'affichent PAR-DESSUS l'écran courant, ils ne le remplacent
    // plus (roadmap 4.9). Le `return` anticipé d'avant sortait l'onglet de la
    // composition et emportait tout son état `remember` : ouvrir une fiche depuis
    // « Autour » puis revenir rendait une liste vide et un fix GPS à refaire.
    // Ici l'écran du dessous reste composé, sa liste et son défilement intacts.
    //
    // L'ordre compte : Paramètres est testé en DERNIER, parce que Départements et
    // Administration s'ouvrent depuis lui et doivent se refermer les premiers.
    val current = selected
    val fermeSousEcran: (() -> Unit)? = when {
        current != null -> ({ selected = null })
        showAdd -> ({ showAdd = false })
        showAdmin -> ({ showAdmin = false })
        showHelp -> ({ showHelp = false })
        showDeps -> ({ showDeps = false })
        showSettings -> ({ showSettings = false })
        else -> null
    }

    // Retour système : il n'existait aucun `BackHandler` dans le projet, donc le
    // geste « retour » quittait l'application depuis n'importe où.
    var dernierRetour by remember { mutableLongStateOf(0L) }
    BackHandler {
        when {
            fermeSousEcran != null -> fermeSousEcran()
            tab != 0 -> tab = 0
            else -> {
                // Onglet racine : deux appuis pour quitter (roadmap 6). Sur le
                // terrain, un retour malencontreux coûte un fix GPS et la reprise
                // d'une saisie ; une confirmation de deux secondes est peu cher payé.
                val maintenant = SystemClock.elapsedRealtime()
                if (maintenant - dernierRetour < 2_000) {
                    (context as? Activity)?.finish()
                } else {
                    dernierRetour = maintenant
                    Toast.makeText(context, "Appuyez à nouveau pour quitter",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (aideContexte) {
        AideContextuelle(
            tab = tab,
            onToutVoir = { aideContexte = false; showHelp = true },
            onDismiss = { aideContexte = false }
        )
    }

    Box(Modifier.fillMaxSize()) {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(when (tab) {
                            0 -> "Recherche"
                            1 -> "À proximité"
                            else -> "Carte"
                        })
                    },
                    actions = {
                        // Deux boutons, toujours à la même place : l'aide de
                        // l'écran où l'on est, et les réglages. Ils remplacent
                        // l'onglet « Compte », qui mélangeait profil, fichiers,
                        // gestion des comptes et déconnexion (roadmap 3.8).
                        Text("?", fontSize = 22.sp, color = Color.White,
                            modifier = Modifier
                                .clickable { aideContexte = true }
                                .padding(horizontal = 14.dp, vertical = 4.dp))
                        Box {
                            Text("⚙", fontSize = 22.sp, color = Color.White,
                                modifier = Modifier
                                    .clickable { showSettings = true }
                                    .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp))
                            if (majDisponible != null) {
                                Text("●", fontSize = 11.sp,
                                    color = LocalCouleursPm.current.avert,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Text("🔍", fontSize = 20.sp) },
                        label = { Text("Recherche") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Text("📍", fontSize = 20.sp) },
                        label = { Text("À proximité") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Text("🗺️", fontSize = 20.sp) },
                        label = { Text("Carte") }
                    )
                }
            }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                majDisponible?.let { dataset ->
                    BandeauMiseAJour(
                        dataset = dataset,
                        onOuvrir = { majDisponible = null; showDeps = true },
                        onPlusTard = { majDisponible = null },
                        onIgnorer = { DepStore.ignore(context, dataset); majDisponible = null }
                    )
                }
                // L'app démarre sans données (roadmap 3.2) : le dire, plutôt que
                // laisser croire à une recherche qui ne trouve rien.
                val nbPm = remember(revision) { PmRepository.size }
                if (nbPm == 0) {
                    Text(
                        "Aucun département installé — touche ici, ou ⚙ Paramètres › Départements.",
                        modifier = Modifier.fillMaxWidth()
                            .background(LocalCouleursPm.current.bandeau)
                            .clickable { showDeps = true }
                            .padding(12.dp),
                        fontSize = 14.sp, color = LocalCouleursPm.current.surBandeau
                    )
                }
                when (tab) {
                    0 -> SearchScreen(onSelect = { selected = it }, onAddPm = { showAdd = true })
                    1 -> NearbyScreen(onSelect = { selected = it })
                    else -> MapScreen(onSelect = { selected = it })
                }
            }
        }

        // `Surface` est opaque et, en Material 3, absorbe les appuis : sans elle
        // un clic traverserait le sous-écran jusqu'à la liste restée dessous.
        if (fermeSousEcran != null) {
            Surface(Modifier.fillMaxSize()) {
                when {
                    current != null -> PmDetailScreen(pm = current, onBack = { selected = null })
                    showAdd -> AddPmScreen(
                        onBack = { showAdd = false },
                        onCreated = { pm -> showAdd = false; selected = pm }
                    )
                    showAdmin -> AdminScreen(onBack = { showAdmin = false })
                    showHelp -> HelpScreen(onBack = { showHelp = false })
                    showDeps -> DepScreen(onBack = { showDeps = false }, onChanged = { revision++ })
                    showSettings -> SettingsScreen(
                        onBack = { showSettings = false },
                        onOpenDeps = { showDeps = true },
                        onOpenAdmin = { showAdmin = true },
                        onLogout = { showSettings = false; onLogout() },
                        syncInfo = syncInfo,
                        majDataset = majDisponible
                    )
                }
            }
        }
    }
}

/**
 * Aide contextuelle (roadmap 3.8) : quelques lignes sur l'écran où l'on se
 * trouve, et un lien vers l'aide complète. Quelqu'un qui bute sur la carte ne
 * devrait pas avoir à traverser le chapitre sur les comptes pour sa réponse.
 */
@Composable
private fun AideContextuelle(tab: Int, onToutVoir: () -> Unit, onDismiss: () -> Unit) {
    val (titre, texte) = when (tab) {
        0 -> "Recherche" to
            "Tape au moins deux caractères : référence du PM, commune ou code postal. " +
            "Les accents et la casse n'ont pas d'importance.\n\n" +
            "Le PM que tu cherches n'est pas dans la liste ? Le bouton ➕ permet de " +
            "l'ajouter ; il sera partagé avec les autres."
        1 -> "À proximité" to
            "Les 25 PM les plus proches de toi, recalculés à chaque nouveau point GPS. " +
            "Tout se fait hors ligne.\n\n" +
            "Le filtre « À géolocaliser » ne garde que les PM encore placés au centre " +
            "de leur zone ARCEP : ce sont ceux qu'il reste à relever."
        else -> "Carte" to
            "Seuls les PM de la zone affichée sont dessinés : déplace ou zoome pour en " +
            "voir d'autres.\n\n" +
            "Rond cyan = position relevée sur place. Carré rouge = centre de zone ARCEP, " +
            "donc à préciser. Un chiffre = plusieurs PM au même endroit (un shelter).\n\n" +
            "Le bouton en haut bascule entre le plan et la vue aérienne."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titre) },
        text = { Text(texte, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onToutVoir) { Text("▲ Toute l'aide") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}


/** Onglet 1 : recherche d'une PM par code ou commune. */
@SuppressLint("MissingPermission")
@Composable
fun SearchScreen(onSelect: (Pm) -> Unit, onAddPm: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PmView>>(emptyList()) }
    var lastLoc by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var servingPm by remember { mutableStateOf<ServingPm?>(null) }
    var servingChecked by remember { mutableStateOf(false) }

    // Recherche instantanée (auto-complétion) dès 2 caractères, insensible aux accents.
    LaunchedEffect(query) {
        results = withContext(Dispatchers.IO) {
            if (query.trim().length >= 2) PmRepository.search(query) else emptyList()
        }
    }

    fun locateServing() {
        val client = LocationServices.getFusedLocationProviderClient(context)
        scope.launch {
            try {
                val loc = client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
                ).await()
                if (loc != null) {
                    lastLoc = loc.latitude to loc.longitude
                    servingPm = withContext(Dispatchers.IO) { PmRepository.pmServing(loc.latitude, loc.longitude) }
                    servingChecked = true
                }
            } catch (_: Exception) {
                // silencieux : accès direct facultatif, la recherche manuelle reste disponible
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) locateServing() }

    // Accès direct à "Probable PM de cette zone" dès l'ouverture de l'onglet, avant toute recherche.
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) locateServing() else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (lastLoc != null && servingChecked) {
            ServingPmCard(serving = servingPm, onSelect = onSelect)
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Code PM, commune ou code postal") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onAddPm, modifier = Modifier.fillMaxWidth()) {
            Text("➕ Ajouter un PM absent de l'ARCEP")
        }

        Spacer(Modifier.height(12.dp))

        if (query.trim().length >= 2 && results.isEmpty()) {
            Text("Aucune PM trouvée pour « $query ».")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { v ->
                    PmListCard(
                        title = v.pm.code ?: "PM sans code",
                        subtitle = "${PmRepository.operatorName(v.pm)} · ${v.pm.com ?: ""}",
                        exact = v.exact,
                        meta = MetaStore.meta(v.pm.code),
                        onClick = { onSelect(v.pm) }
                    )
                }
            }
        }
    }
}

/** Onglet 2 : PM autour de ma position. */
@SuppressLint("MissingPermission")
@Composable
fun NearbyScreen(onSelect: (Pm) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Appuie sur le bouton pour trouver les PM proches.") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PmDistance>>(emptyList()) }
    var toLocateOnly by remember { mutableStateOf(false) }
    // Filtre sur les étiquettes (roadmap 3.6) : « ceux qu'on ne trouvera pas
    // tout seul ». C'est la liste qu'on veut avant de partir en tournée.
    var difficilesOnly by remember { mutableStateOf(false) }
    var lastLoc by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var servingPm by remember { mutableStateOf<ServingPm?>(null) }
    var servingChecked by remember { mutableStateOf(false) }

    suspend fun computeResults() {
        val l = lastLoc ?: return
        results = withContext(Dispatchers.IO) {
            when {
                toLocateOnly -> PmRepository.nearestToLocate(l.first, l.second, 25)
                // On ratisse plus large avant de filtrer : les PM signalés sont
                // rares, prendre les 25 plus proches puis filtrer n'en rendrait
                // souvent aucun.
                difficilesOnly -> PmRepository.nearest(l.first, l.second, 300)
                    .filter {
                        val m = MetaStore.meta(it.view.pm.code)
                        Etiquettes.difficile(m.tags) || m.note != null
                    }
                    .take(25)
                else -> PmRepository.nearest(l.first, l.second, 25)
            }
        }
    }

    suspend fun computeServing() {
        val l = lastLoc ?: return
        servingChecked = false
        servingPm = withContext(Dispatchers.IO) { PmRepository.pmServing(l.first, l.second) }
        servingChecked = true
    }

    fun locateAndSearch() {
        loading = true
        status = "Localisation en cours…"
        results = emptyList()
        val client = LocationServices.getFusedLocationProviderClient(context)
        scope.launch {
            try {
                val loc = client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
                ).await()
                if (loc == null) {
                    status = "Position introuvable. Vérifie que la localisation est activée."
                } else {
                    lastLoc = loc.latitude to loc.longitude
                    computeResults()
                    computeServing()
                    status = "Ta position : %.5f, %.5f".format(loc.latitude, loc.longitude)
                }
            } catch (e: Exception) {
                status = "Erreur de localisation : ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) locateAndSearch() else status = "Autorisation de localisation refusée."
    }

    fun onLocateClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) locateAndSearch()
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { onLocateClick() },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "Recherche…" else "📍 PM autour de moi") }

        Spacer(Modifier.height(8.dp))
        Text(status)
        Spacer(Modifier.height(8.dp))

        if (lastLoc != null && servingChecked) {
            ServingPmCard(serving = servingPm, onSelect = onSelect)
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !toLocateOnly && !difficilesOnly,
                onClick = {
                    toLocateOnly = false; difficilesOnly = false
                    scope.launch { computeResults() }
                },
                label = { Text("Tous") }
            )
            FilterChip(
                selected = toLocateOnly,
                onClick = {
                    toLocateOnly = true; difficilesOnly = false
                    scope.launch { computeResults() }
                },
                label = { Text("À géolocaliser") }
            )
            FilterChip(
                selected = difficilesOnly,
                onClick = {
                    difficilesOnly = true; toLocateOnly = false
                    scope.launch { computeResults() }
                },
                label = { Text("🙈 Signalés") }
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                val v = item.view
                PmListCard(
                    title = "${formatDistance(item.meters)} — ${v.pm.code ?: "PM sans code"}",
                    subtitle = "${PmRepository.operatorName(v.pm)} · ${v.pm.com ?: ""}",
                    exact = v.exact,
                    meta = MetaStore.meta(v.pm.code),
                    onClick = { onSelect(v.pm) }
                )
            }
        }
    }
}

/** Carte "PM probable de cette zone" (ARCEP), partagée par Recherche et Autour. */
@Composable
fun ServingPmCard(serving: ServingPm?, onSelect: (Pm) -> Unit) {
    Card(
        onClick = { serving?.let { onSelect(it.pm) } },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (serving != null) CouleurExacte.copy(alpha = 0.12f) else Color.LightGray.copy(alpha = 0.25f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Probable PM de cette zone (ARCEP)",
                fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CouleurExacte
            )
            if (serving != null) {
                val label = if (serving.insideZone) "dessert cet endroit"
                else "zone la plus proche (≈ ${serving.distanceM.toInt()} m)"
                Text(
                    "${serving.pm.code ?: "PM"} — $label",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                Text(
                    "${PmRepository.operatorName(serving.pm)} · ${serving.pm.com ?: ""}",
                    fontSize = 13.sp, color = Color.Gray
                )
            } else {
                Text("Aucune zone ARCEP connue à cet endroit.", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

/** Fiche détaillée d'une PM, avec enregistrement de la position exacte. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun PmDetailScreen(pm: Pm, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var view by remember { mutableStateOf(PmRepository.view(pm)) }
    var capturing by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf<List<ApiClient.Comment>>(emptyList()) }
    var newComment by remember { mutableStateOf("") }
    var commentBusy by remember { mutableStateOf(false) }
    var editingComment by remember { mutableStateOf<ApiClient.Comment?>(null) }
    var deletingComment by remember { mutableStateOf<ApiClient.Comment?>(null) }
    var pendingPoorFix by remember { mutableStateOf<Triple<Double, Double, Double?>?>(null) }
    var serverDetail by remember { mutableStateOf<ApiClient.PmDetail?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var capturePrecise by remember { mutableStateOf(false) }
    val address by rememberAddress(view.lat, view.lon)

    // Étiquettes, indication d'accès, photos (roadmap 3.6, 3.7). Tout est lu
    // dans les dépôts locaux : la fiche s'ouvre complète sans réseau.
    var meta by remember(pm.code) { mutableStateOf(MetaStore.meta(pm.code)) }
    var showTags by remember { mutableStateOf(false) }
    var showAcces by remember { mutableStateOf(false) }
    // Les photos connues sont lues avant tout appel : hors ligne, la fiche
    // rouvre sur celles déjà téléchargées au lieu de n'en montrer aucune (§ F09).
    var photos by remember(pm.code) {
        mutableStateOf(PhotoStore.connues(context, pm.code))
    }
    var rechargePhotos by remember { mutableIntStateOf(0) }

    fun deleteComment(c: ApiClient.Comment) {
        val token = SessionStore.token ?: return
        scope.launch {
            try {
                ApiClient.deleteComment(token, c.id)
                comments = comments.filterNot { it.id == c.id }
            } catch (e: Exception) {
                Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun saveEditedComment(c: ApiClient.Comment, newBody: String) {
        val token = SessionStore.token ?: return
        scope.launch {
            try {
                val u = ApiClient.editComment(token, c.id, newBody)
                comments = comments.map { if (it.id == u.id) u else it }
                editingComment = null
            } catch (e: Exception) {
                Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun refresh() { view = PmRepository.view(pm) }

    // Enregistre la position : serveur d'abord (règle des 10 m appliquée côté serveur),
    // puis local. Si le serveur refuse (< 10 m), on n'écrase pas. Si hors-ligne, on garde en local.
    fun applyPosition(lat: Double, lon: Double, accuracy: Double?, note: String?,
                      manual: Boolean = false, methode: String? = null) {
        val code = pm.code ?: return
        val token = SessionStore.token
        scope.launch {
            if (token == null) {
                PmRepository.saveExact(context, code, lat, lon, note, accuracyM = accuracy,
                    manual = manual, method = methode)
                refresh(); return@launch
            }
            try {
                ApiClient.putPosition(token, code, lat, lon, accuracy, manual, methode)
                PmRepository.saveExact(context, code, lat, lon, note, SessionStore.username, accuracy,
                    synced = true, manual = manual, method = methode)
                refresh()
                Toast.makeText(context, "Géoloc précise enregistrée ✅", Toast.LENGTH_SHORT).show()
            } catch (e: ApiClient.ApiException) {
                // Rejet serveur (ex. position < 10 m de l'actuelle) : ne pas écraser localement.
                Toast.makeText(context, e.message ?: "Refusé par le serveur", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // Hors-ligne : conserver en local (sera partagé à la prochaine capture en ligne).
                // Hors ligne, la capture attend dans le fichier local : elle doit
                // y emporter son mode de saisie, c'est la synchro qui la remontera
                // plus tard et elle n'aura plus rien d'autre pour le savoir (§ F07).
                PmRepository.saveExact(context, code, lat, lon, note, SessionStore.username, accuracy,
                    manual = manual, method = methode)
                refresh()
                Toast.makeText(context, "Enregistré en local (hors-ligne)", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Chargement des commentaires + détail serveur (confirmations) à l'ouverture de la fiche.
    LaunchedEffect(pm.code) {
        val token = SessionStore.token
        val code = pm.code
        if (token != null && code != null) {
            try { comments = ApiClient.fetchComments(token, code) } catch (_: Exception) {}
            try { serverDetail = ApiClient.fetchPmDetail(token, code) } catch (_: Exception) {}
        }
    }

    // Liste des photos, rechargée après chaque prise de vue ou suppression.
    // Les octets, eux, ne descendent qu'à l'affichage d'une vignette.
    LaunchedEffect(pm.code, rechargePhotos) {
        val token = SessionStore.token
        val code = pm.code
        if (token != null && code != null) {
            try {
                val recues = ApiClient.fetchPhotos(token, code)
                photos = recues
                // Gardée : c'est cette liste, et non les octets, qui manquait
                // pour retrouver les photos hors ligne (§ F09). Un échec réseau
                // laisse le catalogue tel quel, donc la fiche telle quelle.
                withContext(Dispatchers.IO) { PhotoStore.memorise(context, code, recues) }
            } catch (_: Exception) {}
        }
    }

    fun confirmPosition() {
        val token = SessionStore.token ?: return
        val code = pm.code ?: return
        confirming = true
        scope.launch {
            try {
                serverDetail = ApiClient.confirmPosition(token, code)
                Toast.makeText(context, "Position confirmée 👍", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
            } finally { confirming = false }
        }
    }

    fun captureCurrent() {
        val code = pm.code ?: return
        capturing = true
        val client = LocationServices.getFusedLocationProviderClient(context)
        scope.launch {
            try {
                val loc = client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
                ).await()
                if (loc != null) {
                    val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                    if (acc != null && acc > POOR_ACCURACY_M) {
                        pendingPoorFix = Triple(loc.latitude, loc.longitude, acc)
                    } else {
                        applyPosition(loc.latitude, loc.longitude, acc, view.note)
                    }
                } else {
                    Toast.makeText(context, "Position introuvable (GPS ?)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                capturing = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) captureCurrent()
        else Toast.makeText(context, "Localisation refusée", Toast.LENGTH_LONG).show()
    }

    fun onCaptureClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) captureCurrent()
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun onPreciseClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) capturePrecise = true
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    if (capturePrecise) {
        CapturePreciseDialog(
            onValider = { lat, lon, acc ->
                capturePrecise = false
                // Pas d'avertissement « GPS imprécis » ici : la jauge l'a montré
                // pendant trente secondes, et la précision retenue est la médiane,
                // déjà moins flatteuse que le meilleur fix.
                applyPosition(lat, lon, acc, view.note, methode = "gps_precis")
            },
            onAnnuler = { capturePrecise = false }
        )
    }

    if (showManual) {
        ManualCoordDialog(
            initialLat = view.lat, initialLon = view.lon, initialNote = view.note,
            onDismiss = { showManual = false },
            onSave = { lat, lon, note ->
                showManual = false
                applyPosition(lat, lon, null, note, manual = true)
            }
        )
    }

    // Étiquettes et accès : on écrit local, puis on tente la remontée. Le
    // succès n'est pas attendu — `Sync` repassera — mais quand le réseau est
    // là, le collègue d'à côté voit l'information tout de suite.
    fun remonteMeta(bloc: suspend (String, String) -> Unit, apres: (String) -> Unit) {
        val token = SessionStore.token ?: return
        val code = pm.code ?: return
        scope.launch {
            try {
                bloc(token, code)
                apres(code)
                withContext(Dispatchers.IO) { MetaStore.ecrit(context) }
            } catch (_: Exception) {
            }
        }
    }

    if (showTags) {
        DialogueEtiquettes(
            initiales = meta.tags,
            onDismiss = { showTags = false },
            onValider = { tags ->
                showTags = false
                val code = pm.code
                if (code != null) {
                    MetaStore.poseTags(context, code, tags)
                    meta = MetaStore.meta(code)
                    remonteMeta({ t, c -> ApiClient.putTags(t, c, tags) }) { MetaStore.tagsEnvoyes(it) }
                }
            }
        )
    }

    if (showAcces) {
        DialogueAcces(
            noteInitiale = meta.note, latInitiale = meta.accesLat, lonInitiale = meta.accesLon,
            onDismiss = { showAcces = false },
            onValider = { note, la, lo ->
                showAcces = false
                val code = pm.code
                if (code != null) {
                    MetaStore.poseAcces(context, code, note, la, lo, SessionStore.username)
                    meta = MetaStore.meta(code)
                    remonteMeta({ t, c -> ApiClient.putAccess(t, c, note, la, lo) }) {
                        MetaStore.accesEnvoye(it)
                    }
                }
            }
        )
    }

    editingComment?.let { c ->
        EditCommentDialog(
            initial = c.body,
            onDismiss = { editingComment = null },
            onSave = { newBody -> saveEditedComment(c, newBody) }
        )
    }

    deletingComment?.let { c ->
        AlertDialog(
            onDismissRequest = { deletingComment = null },
            title = { Text("Supprimer ce commentaire ?") },
            text = { Text("« ${c.body.take(120)}${if (c.body.length > 120) "…" else ""} »\n— ${c.author ?: "?"}") },
            confirmButton = {
                TextButton(onClick = { deleteComment(c); deletingComment = null }) {
                    Text("Supprimer", color = OrangeWarn)
                }
            },
            dismissButton = { TextButton(onClick = { deletingComment = null }) { Text("Annuler") } }
        )
    }

    pendingPoorFix?.let { fix ->
        val (la, lo, acc) = fix
        AlertDialog(
            onDismissRequest = { pendingPoorFix = null },
            title = { Text("Précision GPS faible") },
            text = {
                Text("Précision ±${acc?.roundToInt()} m — le PM est peut-être en sous-sol ou masqué. " +
                    "Enregistrer quand même cette position ?")
            },
            confirmButton = {
                TextButton(onClick = { applyPosition(la, lo, acc, view.note); pendingPoorFix = null }) {
                    Text("Enregistrer")
                }
            },
            dismissButton = { TextButton(onClick = { pendingPoorFix = null }) { Text("Annuler") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pm.code ?: "PM") },
                navigationIcon = {
                    Text(
                        "←  ", color = Color.White, fontSize = 22.sp,
                        modifier = Modifier.clickable { onBack() }.padding(start = 12.dp, end = 4.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BlueDark, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            PrecisionBadge(view)
            // Deux sources pour le même fait : le paquet installé (le PM a
            // disparu à la dernière mise à jour) et le serveur (l'import ARCEP
            // l'a retiré, le paquet du téléphone n'est pas encore à jour). La
            // première marche hors ligne, la seconde prévient plus tôt (§ F12).
            if (pm.retire || serverDetail?.retiredAt != null) {
                Spacer(Modifier.height(8.dp))
                BandeauRetire(serverDetail?.retiredAt)
            }
            Spacer(Modifier.height(12.dp))

            // En tête de fiche, avant les références : c'est ce qu'on lit en
            // arrivant sur place, pas ce qu'on consulte après coup.
            BlocAcces(pm.code, meta) { showAcces = true }
            Spacer(Modifier.height(8.dp))
            BlocEtiquettes(pm.code, meta.tags) { showTags = true }
            Spacer(Modifier.height(12.dp))

            SelectionContainer {
                Column {
                    InfoRow("Référence", pm.code ?: "—")
                    InfoRow("Opérateur", PmRepository.operatorName(pm))
                    InfoRow("Code OI", pm.oi ?: "—")
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    InfoRow("Commune", pm.com ?: "—")
                    InfoRow("Département", pm.dep ?: "—")
                    val fileAddress = serverDetail?.address
                    if (fileAddress != null) {
                        InfoRow("Adresse", fileAddress)
                    } else {
                        InfoRow(
                            "Adresse (indicative)",
                            when (address) {
                                "…" -> "Recherche…"
                                null -> "Non disponible (hors ligne)"
                                else -> address!!
                            }
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    InfoRow("Coordonnées GPS", "${view.lat}, ${view.lon}", mono = true)
                    view.accuracyM?.let { InfoRow("Précision GPS", "±${it.roundToInt()} m") }
                    InfoRow("État", pm.etat ?: "—")
                    InfoRow("Date de début", pm.date ?: "—")
                    InfoRow(
                        "Logements",
                        if (pm.lgt != null && pm.tot != null) "${pm.lgt} raccordables / ${pm.tot}"
                        else (pm.lgt?.toString() ?: "—")
                    )
                    if (view.note != null) InfoRow("Note", view.note!!)
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString("${view.lat}, ${view.lon}")) }
            ) { Text("📋 Copier les coordonnées") }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { openItinerary(context, view.lat, view.lon, pm.code) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("🗺️  Y aller", fontSize = 18.sp) }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onCaptureClick() },
                enabled = !capturing && pm.code != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (capturing) "Localisation…"
                    else if (view.exact) "📍 Mettre à jour (ma position actuelle)"
                    else "📍 Enregistrer la position exacte (ici)"
                )
            }

            // Deux boutons explicites, pas un appui long : le choix entre « vite » et
            // « bien » est délibéré, et celui qui a trente secondes doit le voir.
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onPreciseClick() },
                enabled = !capturing && !capturePrecise && pm.code != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("🎯 Capture précise (" + CAPTURE_PRECISE_S + " s, immobile)") }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showManual = true }) { Text("✏️ Saisir / note") }
                // Suppression d'une position partagée : réservée à l'admin (avec confirmation).
                if (view.exact && SessionStore.isAdmin) {
                    OutlinedButton(onClick = { confirmErase = true }) { Text("🗑️ Effacer") }
                }
            }

            if (confirmErase) {
                AlertDialog(
                    onDismissRequest = { confirmErase = false },
                    title = { Text("Effacer cette position ?") },
                    text = {
                        Text("La position partagée de ${pm.code ?: "ce PM"} sera supprimée pour TOUS " +
                            "les utilisateurs (confirmations comprises). L'historique est conservé. " +
                            "Cette action est réservée aux admins.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmErase = false
                            val token = SessionStore.token
                            val code = pm.code
                            if (token != null && code != null) {
                                scope.launch {
                                    try {
                                        ApiClient.deletePosition(token, code)
                                        PmRepository.deleteExact(context, code)
                                        refresh()
                                        serverDetail = try { ApiClient.fetchPmDetail(token, code) } catch (e: Exception) { null }
                                        Toast.makeText(context, "Position supprimée (pour tous)", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }) { Text("Effacer", color = OrangeWarn) }
                    },
                    dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Annuler") } }
                )
            }

            // ---- Confirmations (serveur) ----
            serverDetail?.let { d ->
                if (d.positionStatus == "exacte") {
                    Spacer(Modifier.height(16.dp))
                    Text("✔️ Confirmée par ${d.confirmations} personne(s)",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CouleurExacte)
                    Spacer(Modifier.height(4.dp))
                    if (d.confirmedByMe) {
                        Text("Tu as confirmé cette position.", fontSize = 12.sp, color = Color.Gray)
                    } else {
                        OutlinedButton(onClick = { confirmPosition() }, enabled = !confirming) {
                            Text("👍 Je confirme (je suis devant)")
                        }
                    }
                }
            }

            // ---- Photos de terrain ----
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            BlocPhotos(pm.code, photos) { rechargePhotos++ }

            // ---- Commentaires partagés (serveur) ----
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Commentaires", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            if (comments.isEmpty()) {
                Text("Aucun commentaire.", fontSize = 13.sp, color = Color.Gray)
            } else {
                comments.forEach { c ->
                    val canEdit = SessionStore.isAdmin || c.author == SessionStore.username
                    CommentRow(
                        comment = c,
                        canEdit = canEdit,
                        onEdit = { editingComment = c },
                        onDelete = { deletingComment = c }
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newComment,
                onValueChange = { newComment = it },
                label = { Text("Ajouter un commentaire") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !commentBusy && newComment.isNotBlank() && pm.code != null,
                onClick = {
                    val token = SessionStore.token
                    val code = pm.code
                    if (token != null && code != null) {
                        commentBusy = true
                        scope.launch {
                            try {
                                val c = ApiClient.addComment(token, code, newComment.trim())
                                comments = comments + c
                                newComment = ""
                            } catch (e: Exception) {
                                Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
                            } finally { commentBusy = false }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Publier le commentaire") }
        }
    }
}

/** Écran d'ajout d'un PM absent de l'ARCEP (création terrain). */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun AddPmScreen(onBack: () -> Unit, onCreated: (Pm) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ref by remember { mutableStateOf("") }
    var com by remember { mutableStateOf("") }
    var op by remember { mutableStateOf("") }
    var dep by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var acc by remember { mutableStateOf<Double?>(null) }
    var busy by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    fun capture() {
        capturing = true
        val client = LocationServices.getFusedLocationProviderClient(context)
        scope.launch {
            try {
                val loc = client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
                ).await()
                if (loc != null) {
                    lat = loc.latitude; lon = loc.longitude
                    acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                } else Toast.makeText(context, "Position introuvable (GPS ?)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
            } finally { capturing = false }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) capture() else Toast.makeText(context, "Localisation refusée", Toast.LENGTH_LONG).show() }

    fun onCaptureClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) capture() else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun submit() {
        val token = SessionStore.token ?: return
        val la = lat; val lo = lon
        if (com.isBlank() || la == null || lo == null) return
        busy = true
        scope.launch {
            try {
                val pm = ApiClient.createPm(token, ref.ifBlank { null }, op.ifBlank { null },
                    com.trim(), dep.ifBlank { null }, la, lo, acc)
                PmRepository.addLocalPm(context, pm)
                pm.code?.let { PmRepository.saveExact(context, it, la, lo, null, SessionStore.username, acc, synced = true) }
                Toast.makeText(context, "PM ajouté ✅", Toast.LENGTH_SHORT).show()
                onCreated(pm)
            } catch (e: Exception) {
                Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajouter un PM") },
                navigationIcon = {
                    Text("←  ", color = Color.White, fontSize = 22.sp,
                        modifier = Modifier.clickable { onBack() }.padding(start = 12.dp, end = 4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BlueDark, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("Pour un PM récent absent de l'ARCEP. Place-toi devant et capture sa position.",
                fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(com, { com = it }, label = { Text("Commune *") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(op, { op = it }, label = { Text("Opérateur (facultatif)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(ref, { ref = it }, label = { Text("Référence (si connue)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("Département :", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("14", "27", "50", "61", "76", "78", "72").forEach { d ->
                    FilterChip(selected = dep == d, onClick = { dep = if (dep == d) "" else d },
                        label = { Text(d) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onCaptureClick() }, enabled = !capturing, modifier = Modifier.fillMaxWidth()) {
                Text(if (capturing) "Localisation…" else if (lat != null) "📍 Reprendre la position" else "📍 Capturer la position (ici)")
            }
            if (lat != null) {
                Spacer(Modifier.height(4.dp))
                Text("Position : %.5f, %.5f".format(lat, lon) + (acc?.let { " (±${it.roundToInt()} m)" } ?: ""),
                    fontSize = 13.sp, color = CouleurExacte)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !busy && com.isNotBlank() && lat != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Créer le PM") }
        }
    }
}

/**
 * « Retiré du référentiel ARCEP » (§ F12).
 *
 * La fiche reste ouverte et complète : ce qui a été relevé sur place a coûté un
 * déplacement, et un PM sorti du référentiel trimestriel est souvent encore sur
 * le terrain. Mais il faut le dire, sans quoi le technicien croirait la fiche
 * courante et s'étonnerait qu'elle ne soit plus dans la recherche de ses
 * collègues.
 */
@Composable
fun BandeauRetire(dateIso: String?) {
    val quand = dateIso?.take(10)?.let { iso ->
        try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(iso)
            d?.let { " le " + SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(it) }
        } catch (e: Exception) { null }
    } ?: ""
    Surface(color = OrangeWarn, shape = MaterialTheme.shapes.small) {
        Text(
            "⚠️ Retiré du référentiel ARCEP$quand — fiche conservée pour vos relevés",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun PrecisionBadge(view: PmView) {
    val color = if (view.exact) CouleurExacte else OrangeWarn
    val label = if (view.exact) {
        val d = view.savedTs?.let { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it)) }
        val who = view.author?.let { " par $it" } ?: ""
        "✅ Géoloc précise" + (d?.let { " (le $it$who)" } ?: "")
    } else {
        "≈ Position approximative — à confirmer sur place"
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun ManualCoordDialog(
    initialLat: Double, initialLon: Double, initialNote: String?,
    onDismiss: () -> Unit, onSave: (Double, Double, String?) -> Unit
) {
    var lat by remember { mutableStateOf(initialLat.toString()) }
    var lon by remember { mutableStateOf(initialLon.toString()) }
    var note by remember { mutableStateOf(initialNote ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saisir coordonnées / note") },
        text = {
            Column {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Note (facultatif)") })
                error?.let { Text(it, color = OrangeWarn, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val la = lat.replace(',', '.').toDoubleOrNull()
                val lo = lon.replace(',', '.').toDoubleOrNull()
                if (la == null || lo == null) error = "Coordonnées invalides"
                else onSave(la, lo, note.ifBlank { null })
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Text(
            value, fontSize = 16.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
fun PmListCard(
    title: String, subtitle: String, exact: Boolean,
    meta: PmMeta = PmMeta(), onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(subtitle, fontSize = 13.sp, color = Color.Gray)
                // Les étiquettes se lisent avant d'ouvrir la fiche : savoir dans
                // la liste qu'un PM est « non visible de la route » change la
                // façon de préparer la tournée (roadmap 3.6).
                val marques = buildString {
                    if (meta.note != null || meta.aUnPointAcces) append("🔑 ")
                    meta.tags.take(4).forEach { append(Etiquettes.icone(it)).append(' ') }
                }.trim()
                if (marques.isNotEmpty()) {
                    Text(marques, fontSize = 13.sp)
                }
            }
            Text(
                if (exact) "✅" else "≈",
                fontSize = 18.sp,
                color = if (exact) CouleurExacte else OrangeWarn
            )
        }
    }
}

/** Un commentaire avec auteur, date, et actions Modifier/Supprimer si autorisé. */
@Composable
fun CommentRow(
    comment: ApiClient.Comment, canEdit: Boolean,
    onEdit: () -> Unit, onDelete: () -> Unit
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(comment.body, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val edited = comment.updatedAt != comment.createdAt
            Text(
                "${comment.author ?: "—"} · ${formatIso(comment.createdAt)}" + if (edited) " (modifié)" else "",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f)
            )
            if (canEdit) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Modifier", fontSize = 12.sp)
                }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Supprimer", fontSize = 12.sp, color = OrangeWarn)
                }
            }
        }
    }
}

@Composable
fun EditCommentDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier le commentaire") },
        text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Édition du profil : e-mail (facultatif) et changement de mot de passe. */
@Composable
fun ProfileDialog(onDismiss: () -> Unit, onSave: (String?, String?, String?) -> Unit) {
    var email by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mon profil") },
        text = {
            Column {
                Text("Ajouter / mettre à jour l'e-mail (facultatif) :", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(
                    email, { email = it }, label = { Text("E-mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Changer le mot de passe (laisser vide sinon) :", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(
                    current, { current = it }, label = { Text("Mot de passe actuel") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    newPass, { newPass = it }, label = { Text("Nouveau mot de passe") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = OrangeWarn, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    newPass.isNotBlank() && newPass.length < 4 -> error = "Nouveau mot de passe trop court (min 4)"
                    newPass.isNotBlank() && current.isBlank() -> error = "Saisis ton mot de passe actuel"
                    email.isBlank() && newPass.isBlank() -> error = "Rien à modifier"
                    else -> onSave(email.ifBlank { null }, current.ifBlank { null }, newPass.ifBlank { null })
                }
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Formate un horodatage ISO serveur (UTC) vers "dd/MM/yyyy HH:mm" (heure locale). */
internal fun formatIso(iso: String): String = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    val d = parser.parse(iso.substringBefore('.'))
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(d!!)
} catch (e: Exception) { iso }

/** Géocodage inversé : coordonnées -> adresse (indicative, nécessite du réseau). */
@Composable
fun rememberAddress(lat: Double, lon: Double): State<String?> {
    val context = LocalContext.current
    return produceState<String?>(initialValue = "…", lat, lon) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!android.location.Geocoder.isPresent()) return@withContext null
                @Suppress("DEPRECATION")
                android.location.Geocoder(context, Locale.FRANCE)
                    .getFromLocation(lat, lon, 1)?.firstOrNull()?.getAddressLine(0)
            } catch (e: Exception) { null }
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m"
    else "%.1f km".format(meters / 1000)
