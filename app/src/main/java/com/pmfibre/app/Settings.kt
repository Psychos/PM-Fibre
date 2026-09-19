package com.pmfibre.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Thème demandé par l'utilisateur. AUTO suit le réglage du téléphone. */
enum class ThemeChoix { AUTO, CLAIR, SOMBRE }

/** Fond de carte ouvert par défaut. */
enum class FondCarte { PLAN, ORTHO }

/**
 * Préférences d'affichage et de comportement (roadmap 3.8), calqué sur
 * [SessionStore] : un objet, des `SharedPreferences`, et un état Compose pour que
 * l'écran se redessine à l'instant où l'on bascule un réglage.
 *
 * Tout est relu depuis le disque au démarrage par [load] ; rien n'est exposé sans
 * avoir été chargé, un réglage absent retombe sur la valeur par défaut. Les
 * réglages ne partent jamais au serveur : ils décrivent ce téléphone, pas le
 * compte — le même utilisateur peut vouloir le mode sombre sur sa tablette et pas
 * sur son téléphone.
 */
object Settings {
    private const val PREFS = "pmfibre_settings"

    var theme by mutableStateOf(ThemeChoix.AUTO)
        private set

    /** Contraste élevé : utile en plein soleil autant que pour une basse vision. */
    var contrasteEleve by mutableStateOf(false)
        private set

    /** Facteur appliqué à TOUTES les tailles de texte (0,85 à 1,45). */
    var echelleTexte by mutableFloatStateOf(1.0f)
        private set

    /** Libellés des PM à côté des points sur la carte. */
    var carteLibelles by mutableStateOf(true)
        private set

    /** Points plus gros : gants, soleil, écran sale. */
    var cartePointsGros by mutableStateOf(false)
        private set

    var carteFond by mutableStateOf(FondCarte.PLAN)
        private set

    /** Ne vérifier la mise à jour des données que sur Wi-Fi (forfaits comptés). */
    var majWifiSeulement by mutableStateOf(false)
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        theme = runCatching { ThemeChoix.valueOf(p.getString("theme", "AUTO")!!) }
            .getOrDefault(ThemeChoix.AUTO)
        contrasteEleve = p.getBoolean("contraste", false)
        echelleTexte = p.getFloat("echelle_texte", 1.0f).coerceIn(0.85f, 1.45f)
        carteLibelles = p.getBoolean("carte_libelles", true)
        cartePointsGros = p.getBoolean("carte_points_gros", false)
        carteFond = runCatching { FondCarte.valueOf(p.getString("carte_fond", "PLAN")!!) }
            .getOrDefault(FondCarte.PLAN)
        majWifiSeulement = p.getBoolean("maj_wifi", false)
    }

    fun setTheme(context: Context, v: ThemeChoix) {
        theme = v; prefs(context).edit().putString("theme", v.name).apply()
    }

    fun setContraste(context: Context, v: Boolean) {
        contrasteEleve = v; prefs(context).edit().putBoolean("contraste", v).apply()
    }

    fun setEchelleTexte(context: Context, v: Float) {
        val c = v.coerceIn(0.85f, 1.45f)
        echelleTexte = c; prefs(context).edit().putFloat("echelle_texte", c).apply()
    }

    fun setCarteLibelles(context: Context, v: Boolean) {
        carteLibelles = v; prefs(context).edit().putBoolean("carte_libelles", v).apply()
    }

    fun setCartePointsGros(context: Context, v: Boolean) {
        cartePointsGros = v; prefs(context).edit().putBoolean("carte_points_gros", v).apply()
    }

    fun setCarteFond(context: Context, v: FondCarte) {
        carteFond = v; prefs(context).edit().putString("carte_fond", v.name).apply()
    }

    fun setMajWifiSeulement(context: Context, v: Boolean) {
        majWifiSeulement = v; prefs(context).edit().putBoolean("maj_wifi", v).apply()
    }
}
