package com.pmfibre.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

private val BlueDark = Color(0xFF0D47A1)
private val BluePrimary = Color(0xFF1565C0)
private val GreenOk = Color(0xFF2E7D32)
private val OrangeWarn = Color(0xFFE65100)

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
        setContent {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = BluePrimary)) {
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
        withContext(Dispatchers.IO) {
            SessionStore.load(context)
            PmRepository.load(context)
        }
        // Session refusée par le serveur (connexion sur un 3ᵉ appareil, compte
        // désactivé…) : on oublie le jeton et on repasse à l'écran de connexion.
        val appContext = context.applicationContext
        ApiClient.onSessionExpired = {
            SessionStore.clearToken(appContext)
            forcedLogoutMsg = "Session fermée : ce compte a été utilisé sur un autre appareil " +
                "(2 appareils max par compte). Reconnecte-toi."
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
private fun errorMessage(e: Exception): String = when (e) {
    is ApiClient.ApiException -> e.message ?: "Erreur ${e.status}"
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
    var syncInfo by remember { mutableStateOf<String?>(null) }

    // Synchro bidirectionnelle des positions au démarrage.
    LaunchedEffect(Unit) {
        val token = SessionStore.token ?: return@LaunchedEffect
        try {
            syncInfo = "Synchro : " + Sync.run(context, token)
        } catch (e: Exception) {
            syncInfo = "⚠️ Serveur injoignable (${ApiClient.baseUrl}). Es-tu sur le même réseau ? — ${errorMessage(e)}"
        }
    }

    val current = selected
    if (current != null) {
        PmDetailScreen(pm = current, onBack = { selected = null })
        return
    }
    if (showAdd) {
        AddPmScreen(onBack = { showAdd = false }, onCreated = { pm -> showAdd = false; selected = pm })
        return
    }
    if (showAdmin) {
        AdminScreen(onBack = { showAdmin = false })
        return
    }
    if (showHelp) {
        HelpScreen(onBack = { showHelp = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PM Fibre") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BlueDark,
                    titleContentColor = Color.White
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
                    label = { Text("Autour") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("🗺️", fontSize = 20.sp) },
                    label = { Text("Carte") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Text("ℹ️", fontSize = 20.sp) },
                    label = { Text("Compte") }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (tab) {
                0 -> SearchScreen(onSelect = { selected = it }, onAddPm = { showAdd = true })
                1 -> NearbyScreen(onSelect = { selected = it })
                2 -> MapScreen(onSelect = { selected = it })
                else -> InfoScreen(syncInfo = syncInfo, onLogout = onLogout,
                    onOpenAdmin = { showAdmin = true }, onOpenHelp = { showHelp = true })
            }
        }
    }
}

/** Onglet 1 : recherche d'une PM par code ou commune. */
@Composable
fun SearchScreen(onSelect: (Pm) -> Unit, onAddPm: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PmView>>(emptyList()) }

    // Recherche instantanée (auto-complétion) dès 2 caractères, insensible aux accents.
    LaunchedEffect(query) {
        results = withContext(Dispatchers.IO) {
            if (query.trim().length >= 2) PmRepository.search(query) else emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
    var lastLoc by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    suspend fun computeResults() {
        val l = lastLoc ?: return
        results = withContext(Dispatchers.IO) {
            if (toLocateOnly) PmRepository.nearestToLocate(l.first, l.second, 25)
            else PmRepository.nearest(l.first, l.second, 25)
        }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !toLocateOnly,
                onClick = { toLocateOnly = false; scope.launch { computeResults() } },
                label = { Text("Tous") }
            )
            FilterChip(
                selected = toLocateOnly,
                onClick = { toLocateOnly = true; scope.launch { computeResults() } },
                label = { Text("À géolocaliser") }
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
                    onClick = { onSelect(v.pm) }
                )
            }
        }
    }
}

/** Onglet 3 : informations + export/import des positions enregistrées. */
@Composable
fun InfoScreen(syncInfo: String?, onLogout: () -> Unit, onOpenAdmin: () -> Unit, onOpenHelp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedCount by remember { mutableIntStateOf(PmRepository.savedCount) }
    var message by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf<ApiClient.Stats?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var showHallOfFame by remember { mutableStateOf(false) }

    if (showHallOfFame) HallOfFameDialog(onDismiss = { showHallOfFame = false })

    LaunchedEffect(Unit) {
        val token = SessionStore.token ?: return@LaunchedEffect
        try { stats = ApiClient.fetchMyStats(token) } catch (_: Exception) {}
    }

    if (showProfile) {
        ProfileDialog(
            onDismiss = { showProfile = false },
            onSave = { email, current, newPass ->
                val token = SessionStore.token
                if (token != null) {
                    scope.launch {
                        try {
                            ApiClient.updateProfile(token, email, current, newPass)
                            showProfile = false
                            Toast.makeText(context, "Profil mis à jour ✅", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, errorMessage(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(PmRepository.exportJson().toByteArray())
                }
                message = "Base exportée ($savedCount position(s))."
            } catch (e: Exception) {
                message = "Échec de l'export : ${e.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val n = PmRepository.importJson(context, text)
                savedCount = PmRepository.savedCount
                message = "$n position(s) importée(s)."
            } catch (e: Exception) {
                message = "Échec de l'import : ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("Données", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("Source : ARCEP — ZAPM 2026 T1 (open data)")
        Text("Nombre de PM : ${PmRepository.size}")
        Text("Positions exactes enregistrées : $savedCount", fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(20.dp))
        Text("Compte", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Connecté : ${SessionStore.username ?: "—"}" + if (SessionStore.isAdmin) " (admin)" else "", fontSize = 14.sp)
        stats?.let {
            Text("Mes contributions : ${it.positions} position(s) · ${it.confirmations} confirmation(s) · ${it.comments} commentaire(s)",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BluePrimary)
        }
        syncInfo?.let { Text(it, fontSize = 13.sp, color = Color.Gray) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showProfile = true }) { Text("⚙️ Mon profil") }
            OutlinedButton(onClick = {
                SessionStore.clear(context)
                onLogout()
            }) { Text("🔓 Se déconnecter") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showHallOfFame = true }) { Text("🏆 Hall of Fame") }
            OutlinedButton(onClick = onOpenHelp) { Text("❓ Aide") }
            if (SessionStore.isAdmin) {
                OutlinedButton(onClick = onOpenAdmin) { Text("👥 Comptes") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            val token = SessionStore.token
            if (token != null) {
                scope.launch {
                    message = "Synchronisation…"
                    try {
                        val r = Sync.run(context, token)
                        savedCount = PmRepository.savedCount
                        stats = try { ApiClient.fetchMyStats(token) } catch (_: Exception) { stats }
                        message = "Synchronisé : $r"
                    } catch (e: Exception) {
                        message = "Échec synchro : ${errorMessage(e)}"
                    }
                }
            }
        }) { Text("🔄 Synchroniser maintenant") }

        Spacer(Modifier.height(20.dp))
        Text("Copie de secours (fichier)", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "En temps normal, tout est déjà partagé automatiquement via le serveur. " +
                "Ces boutons servent uniquement à sauvegarder les positions de ce téléphone " +
                "dans un fichier, ou à récupérer un fichier exporté ailleurs.",
            fontSize = 13.sp, color = Color.Gray
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val name = "pm_positions_${SimpleDateFormat("yyyyMMdd", Locale.FRANCE).format(Date())}.json"
                    exportLauncher.launch(name)
                }
            ) { Text("📤 Exporter un fichier") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }
            ) { Text("📥 Importer un fichier") }
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message)
        }

        Spacer(Modifier.height(20.dp))
        Text("À propos des positions", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "• ✅ Géoloc précise : position enregistrée sur le terrain par un utilisateur.\n" +
                "• ≈ Approximative : estimation (centre de zone) pour t'orienter la première fois.\n\n" +
                "Pour enregistrer une position : ouvre la fiche du PM quand tu es devant, " +
                "puis « 📍 Enregistrer la position exacte ».",
            fontSize = 14.sp
        )
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
    val address by rememberAddress(view.lat, view.lon)

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
    fun applyPosition(lat: Double, lon: Double, accuracy: Double?, note: String?, manual: Boolean = false) {
        val code = pm.code ?: return
        val token = SessionStore.token
        scope.launch {
            if (token == null) {
                PmRepository.saveExact(context, code, lat, lon, note, accuracyM = accuracy)
                refresh(); return@launch
            }
            try {
                ApiClient.putPosition(token, code, lat, lon, accuracy, manual)
                PmRepository.saveExact(context, code, lat, lon, note, SessionStore.username, accuracy, synced = true)
                refresh()
                Toast.makeText(context, "Géoloc précise enregistrée ✅", Toast.LENGTH_SHORT).show()
            } catch (e: ApiClient.ApiException) {
                // Rejet serveur (ex. position < 10 m de l'actuelle) : ne pas écraser localement.
                Toast.makeText(context, e.message ?: "Refusé par le serveur", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // Hors-ligne : conserver en local (sera partagé à la prochaine capture en ligne).
                PmRepository.saveExact(context, code, lat, lon, note, SessionStore.username, accuracy)
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
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GreenOk)
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
                listOf("14", "27", "50", "61", "76").forEach { d ->
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
                    fontSize = 13.sp, color = GreenOk)
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

@Composable
fun PrecisionBadge(view: PmView) {
    val color = if (view.exact) GreenOk else OrangeWarn
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
fun PmListCard(title: String, subtitle: String, exact: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
            Text(
                if (exact) "✅" else "≈",
                fontSize = 18.sp,
                color = if (exact) GreenOk else OrangeWarn
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
