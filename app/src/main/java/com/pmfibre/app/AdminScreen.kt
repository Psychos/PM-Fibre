package com.pmfibre.app

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.launch

private val AdmBlueDark = Color(0xFF0D47A1)
private val AdmGreen = Color(0xFF2E7D32)
private val AdmOrange = Color(0xFFE65100)

/** 🏆 Hall of fame : classement des contributeurs. */
@Composable
fun HallOfFameDialog(onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf<List<ApiClient.LeaderboardEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val token = SessionStore.token ?: return@LaunchedEffect
        try { entries = ApiClient.fetchLeaderboard(token) }
        catch (e: Exception) { error = "Classement indisponible (hors-ligne ?)" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🏆 Hall of Fame") },
        text = {
            when {
                error != null -> Text(error!!)
                entries == null -> Text("Chargement…")
                entries!!.isEmpty() -> Text("Aucun contributeur pour l'instant.")
                else -> Column {
                    Text("Positions · Confirmations · Commentaires", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    entries!!.forEachIndexed { i, e ->
                        val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "  ${i + 1}." }
                        val me = e.username == SessionStore.username
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(medal, fontSize = 16.sp)
                            Spacer(Modifier.height(0.dp))
                            Text(
                                "  ${e.username}" + if (me) " (moi)" else "",
                                fontWeight = if (me) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${e.positions} · ${e.confirmations} · ${e.comments}",
                                fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

/** 👥 Gestion des comptes (admin) : liste, stats, rôle, activation, suppression. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<ApiClient.AdminUser>>(emptyList()) }
    var selected by remember { mutableStateOf<ApiClient.AdminUser?>(null) }
    var selectedStats by remember { mutableStateOf<ApiClient.Stats?>(null) }
    var confirmDelete by remember { mutableStateOf<ApiClient.AdminUser?>(null) }
    var showCodes by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    if (showCodes) InvitationCodesDialog(onDismiss = { showCodes = false })

    fun reload() {
        val token = SessionStore.token ?: return
        scope.launch {
            try { users = ApiClient.fetchUsers(token); status = null }
            catch (e: Exception) { status = "Chargement impossible : ${e.message}" }
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Charge les stats du compte sélectionné
    LaunchedEffect(selected?.id) {
        selectedStats = null
        val token = SessionStore.token ?: return@LaunchedEffect
        selected?.let {
            try { selectedStats = ApiClient.fetchUserStats(token, it.id) } catch (_: Exception) {}
        }
    }

    // Boîte de dialogue de détail / actions sur un compte
    selected?.let { u ->
        val isMe = u.username == SessionStore.username
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${u.username}" + if (u.role == "admin") " 👑" else "") },
            text = {
                Column {
                    Text("Rôle : ${u.role}  ·  ${if (u.userType == "externe") "🔶 externe" else "🔷 interne"}" +
                        if (!u.active) "  ·  ⛔ désactivé" else "")
                    u.email?.let { Text("E-mail : $it") }
                    u.createdAt?.let { Text("Inscrit le : ${formatIso(it)}") }
                    u.lastLogin?.let { Text("Dernière connexion : ${formatIso(it)}") }
                    Spacer(Modifier.height(8.dp))
                    when (val s = selectedStats) {
                        null -> Text("Stats : chargement…", color = Color.Gray, fontSize = 13.sp)
                        else -> Text(
                            "Contributions : ${s.positions} position(s) · ${s.confirmations} confirmation(s) · ${s.comments} commentaire(s)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (isMe) {
                        Spacer(Modifier.height(8.dp))
                        Text("C'est ton compte : actions désactivées.", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                if (!isMe) {
                    Column {
                        TextButton(onClick = {
                            val token = SessionStore.token ?: return@TextButton
                            val newRole = if (u.role == "admin") "user" else "admin"
                            scope.launch {
                                try {
                                    ApiClient.setUserRole(token, u.id, newRole)
                                    Toast.makeText(context, "Rôle changé : $newRole", Toast.LENGTH_SHORT).show()
                                    selected = null; reload()
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Erreur", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text(if (u.role == "admin") "⬇️ Retirer admin" else "👑 Passer admin") }
                        TextButton(onClick = {
                            val token = SessionStore.token ?: return@TextButton
                            scope.launch {
                                try {
                                    ApiClient.setUserActive(token, u.id, !u.active)
                                    Toast.makeText(context, if (u.active) "Compte désactivé" else "Compte réactivé", Toast.LENGTH_SHORT).show()
                                    selected = null; reload()
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Erreur", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text(if (u.active) "⛔ Désactiver" else "✅ Réactiver") }
                        TextButton(onClick = { confirmDelete = u; selected = null }) {
                            Text("🗑️ Supprimer", color = AdmOrange)
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Fermer") } }
        )
    }

    // Confirmation de suppression
    confirmDelete?.let { u ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer ${u.username} ?") },
            text = { Text("Le compte sera définitivement supprimé. Ses positions et commentaires restent en base (attribués à son prénom).") },
            confirmButton = {
                TextButton(onClick = {
                    val token = SessionStore.token ?: return@TextButton
                    scope.launch {
                        try {
                            ApiClient.deleteUser(token, u.id)
                            Toast.makeText(context, "Compte supprimé", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Erreur", Toast.LENGTH_LONG).show()
                        }
                        confirmDelete = null; reload()
                    }
                }) { Text("Supprimer", color = AdmOrange) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestion des comptes") },
                navigationIcon = {
                    Text("←  ", color = Color.White, fontSize = 22.sp,
                        modifier = Modifier.clickable { onBack() }.padding(start = 12.dp, end = 4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AdmBlueDark, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            status?.let { Text(it, color = AdmOrange); Spacer(Modifier.height(8.dp)) }
            Text("${users.size} compte(s) — touche un compte pour voir ses stats et agir.",
                fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(users) { u ->
                    Card(onClick = { selected = u }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    u.username + (if (u.role == "admin") " 👑" else "") +
                                        (if (u.userType == "externe") " 🔶" else "") + (if (!u.active) " ⛔" else ""),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    (if (u.userType == "externe") "Externe · " else "") +
                                        (u.lastLogin?.let { "Vu le ${formatIso(it)}" } ?: "Jamais connecté"),
                                    fontSize = 12.sp, color = Color.Gray
                                )
                            }
                            Text("›", fontSize = 20.sp, color = Color.Gray)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showCodes = true }, modifier = Modifier.fillMaxWidth()) {
                Text("🎟️ Codes d'invitation")
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { reload() }, modifier = Modifier.fillMaxWidth()) { Text("🔄 Actualiser") }
        }
    }
}

/** 🎟️ Consultation / édition des deux codes d'invitation (interne / externe). */
@Composable
fun InvitationCodesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var interne by remember { mutableStateOf("") }
    var externe by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val token = SessionStore.token ?: return@LaunchedEffect
        try {
            val c = ApiClient.fetchInvitationCodes(token)
            interne = c.interne; externe = c.externe; loaded = true
        } catch (e: Exception) { error = "Chargement impossible : ${e.message}" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎟️ Codes d'invitation") },
        text = {
            Column {
                Text(
                    "Requis pour créer un compte. Le code utilisé détermine le type : " +
                        "🔷 interne (collègues) ou 🔶 externe (invités). " +
                        "Modifiable à tout moment ; les comptes déjà créés ne sont pas affectés.",
                    fontSize = 12.sp, color = Color.Gray
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = interne, onValueChange = { interne = it },
                    label = { Text("🔷 Code interne") }, singleLine = true,
                    enabled = loaded && !busy, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = externe, onValueChange = { externe = it },
                    label = { Text("🔶 Code externe") }, singleLine = true,
                    enabled = loaded && !busy, modifier = Modifier.fillMaxWidth()
                )
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = AdmOrange, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = loaded && !busy && interne.isNotBlank() && externe.isNotBlank(),
                onClick = {
                    val token = SessionStore.token ?: return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            ApiClient.setInvitationCodes(token, interne.trim(), externe.trim())
                            Toast.makeText(context, "Codes mis à jour ✅", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.message ?: "Erreur"
                        } finally { busy = false }
                    }
                }
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
