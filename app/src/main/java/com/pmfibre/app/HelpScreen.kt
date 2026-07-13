package com.pmfibre.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HelpBlueDark = Color(0xFF0D47A1)

@Composable
private fun HelpTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = HelpBlueDark)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun HelpBody(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 20.sp)
}

/** ❓ Page d'aide : légendes et fonctions de l'application. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aide") },
                navigationIcon = {
                    Text("←  ", color = Color.White, fontSize = 22.sp,
                        modifier = Modifier.clickable { onBack() }.padding(start = 12.dp, end = 4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HelpBlueDark, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("PM Fibre", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = HelpBlueDark)
            HelpBody(
                "Base collaborative des Points de Mutualisation de Normandie : chacun enregistre " +
                    "les positions exactes sur le terrain, tout le monde en profite."
            )

            HelpTitle("🔣 Légendes")
            HelpBody(
                "✅  Géoloc précise : position enregistrée sur le terrain par un utilisateur " +
                    "(le nom et la date sont affichés sur la fiche).\n\n" +
                    "≈  Position approximative : centre estimé de la zone du PM, issu des données " +
                    "publiques. Sert uniquement à s'orienter la première fois — à préciser sur place.\n\n" +
                    "👍  Confirmée par N personne(s) : d'autres utilisateurs ont vérifié la position " +
                    "sur le terrain. Plus il y a de confirmations, plus elle est fiable.\n\n" +
                    "🔵  Sur la carte : le point bleu, c'est toi.\n\n" +
                    "👑 admin · 🔷 interne · 🔶 externe · ⛔ compte désactivé (gestion des comptes)."
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            HelpTitle("🔍 Recherche")
            HelpBody(
                "Tape au moins 2 caractères : les résultats s'affichent instantanément.\n" +
                    "• Par code PM : « PMU-27-227 », « FI-76… »\n" +
                    "• Par commune : « evreux » (accents et majuscules ignorés)\n" +
                    "• Par code postal : « 27000 »"
            )

            HelpTitle("📍 Autour")
            HelpBody(
                "Liste les 25 PM les plus proches de ta position, avec la distance. " +
                    "Le filtre « À géolocaliser » ne montre que ceux SANS position exacte : " +
                    "parfait pour compléter la base en tournée."
            )

            HelpTitle("🗺️ Carte")
            HelpBody(
                "Affiche les PM autour de toi sur fond OpenStreetMap. Touche un marqueur puis " +
                    "sa bulle pour ouvrir la fiche. Le fond de carte nécessite du réseau " +
                    "(les zones déjà vues restent en cache)."
            )

            HelpTitle("📄 Fiche d'un PM")
            HelpBody(
                "Toutes les infos : référence, opérateur, commune, adresse (si connue), état, " +
                    "logements, coordonnées.\n\n" +
                    "• 🗺️ Y aller : ouvre ton appli GPS vers le PM.\n" +
                    "• 📍 Enregistrer la position exacte : à faire DEVANT le PM ; ta position GPS " +
                    "devient la référence partagée. Si le GPS est imprécis (>15 m), l'app te " +
                    "prévient (PM en sous-sol, etc.).\n" +
                    "• ✏️ Saisir / note : coordonnées manuelles ou note libre.\n" +
                    "• 👍 Je confirme : tu es devant le PM et la position est bonne ? Confirme-la.\n" +
                    "• 💬 Commentaires : infos utiles aux collègues (accès, emplacement précis, " +
                    "état de l'armoire…). Tu peux modifier/supprimer les tiens.\n\n" +
                    "Règle des 10 m : une nouvelle position à moins de 10 m de l'actuelle est " +
                    "refusée (elle est déjà précise) ; au-delà, elle remplace l'ancienne et tout " +
                    "est historisé."
            )

            HelpTitle("➕ Ajouter un PM absent")
            HelpBody(
                "Les données publiques (ARCEP) ont ~3 mois de retard : un PM tout neuf peut " +
                    "manquer. Onglet Recherche → « Ajouter un PM absent » : commune + position " +
                    "GPS suffisent, la référence est facultative. Il apparaît ensuite chez tout le monde."
            )

            HelpTitle("🔄 Synchronisation")
            HelpBody(
                "Automatique à chaque ouverture : tes captures partent au serveur, celles des " +
                    "collègues arrivent. Bouton « Synchroniser maintenant » dans l'onglet Compte.\n\n" +
                    "Hors réseau : la recherche, les fiches et les positions déjà connues restent " +
                    "disponibles ; tes captures sont gardées et envoyées au retour du réseau."
            )

            HelpTitle("👤 Compte")
            HelpBody(
                "Mes contributions (positions · confirmations · commentaires), 🏆 Hall of Fame, " +
                    "⚙️ Mon profil (changer le mot de passe, ajouter un e-mail), et la copie de " +
                    "secours par fichier (rarement utile : le serveur partage déjà tout)."
            )

            HelpTitle("🆘 Un souci ?")
            HelpBody(
                "« Serveur injoignable » : vérifie ta connexion données/Wi-Fi. Tout le reste de " +
                    "l'app fonctionne hors-ligne.\n" +
                    "Mot de passe oublié : demande à un admin de supprimer ton compte, puis " +
                    "recrée-le avec le code d'invitation."
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
