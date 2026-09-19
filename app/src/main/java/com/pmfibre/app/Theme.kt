package com.pmfibre.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Couleurs métier, celles qui ne rentrent dans aucun rôle Material : le cyan et le
 * rouge d'une position, l'orange d'un avertissement, le bleu de « ma position ».
 *
 * Elles étaient jusqu'ici codées en dur dans cinq fichiers (`BluePrimary`,
 * `HelpBlueDark`, `PmExactColor`, `PmApproxColor`, `themes.xml`) : impossible
 * d'ajouter un thème sans les rattraper une à une (roadmap 3.8).
 *
 * Rappel qui vaut pour toute évolution : **la couleur ne porte jamais seule
 * l'information**. Environ 8 % des hommes confondent le vert et le rouge, et
 * c'est la population du terrain ; la forme (rond / carré) et le symbole (✅ / ≈)
 * disent la même chose que la teinte.
 */
data class CouleursPm(
    /**
     * Position relevée sur place.
     *
     * Cyan, et non plus vert : sur le fond IGN, la végétation occupe la moitié de
     * l'écran en zone rurale — c'est-à-dire là où l'on cherche un PM — et un point
     * vert y disparaît. Le cyan n'existe quasiment pas dans un paysage : ni la
     * végétation, ni les toits, ni les routes, ni les terres labourées. Il reste
     * franchement distinct du bleu de « ma position », qui tire vers l'indigo.
     */
    val exact: Color,
    /** Centre de zone ARCEP, à préciser. */
    val approx: Color,
    /** Précision douteuse, dérive, quota. */
    val avert: Color,
    /** Point bleu « ma position » — jamais utilisé pour un PM. */
    val moi: Color,
    /** Fond des bandeaux d'information (mise à jour, aucun département). */
    val bandeau: Color,
    val surBandeau: Color
)

private val CouleursClaires = CouleursPm(
    exact = Color(0xFF00B8D4), approx = Color(0xFFD32F2F), avert = Color(0xFFE65100),
    moi = Color(0xFF1565C0), bandeau = Color(0xFFE3F2FD), surBandeau = Color(0xFF0D47A1)
)

private val CouleursSombres = CouleursPm(
    exact = Color(0xFF00E5FF), approx = Color(0xFFEF5350), avert = Color(0xFFFFA726),
    moi = Color(0xFF64B5F6), bandeau = Color(0xFF12314F), surBandeau = Color(0xFFBBDEFB)
)

/**
 * Contraste élevé : saturation maximale et fonds tranchés. Pensé pour l'écran lu
 * à bout de bras en plein soleil, sur un chantier — pas seulement pour la basse
 * vision.
 */
private val CouleursClairesContraste = CouleursClaires.copy(
    exact = Color(0xFF0097A7), approx = Color(0xFFB71C1C), avert = Color(0xFFBF360C),
    moi = Color(0xFF0D47A1), bandeau = Color(0xFFFFFFFF), surBandeau = Color(0xFF000000)
)

private val CouleursSombresContraste = CouleursSombres.copy(
    exact = Color(0xFF84FFFF), approx = Color(0xFFFF8A80), avert = Color(0xFFFFCC80),
    // Bleu plus soutenu que dans le thème sombre ordinaire : face au cyan pâle
    // ci-dessus, le 90CAF9 d'origine n'était plus qu'un autre bleu pâle.
    moi = Color(0xFF448AFF), bandeau = Color(0xFF000000), surBandeau = Color(0xFFFFFFFF)
)

val LocalCouleursPm = staticCompositionLocalOf { CouleursClaires }

private val SchemaClair = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0D47A1),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF00695C)
)

private val SchemaSombre = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF0D47A1),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF4DB6AC)
)

private val SchemaClairContraste = SchemaClair.copy(
    primary = Color(0xFF00337A), background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black, onSurfaceVariant = Color(0xFF1A1A1A),
    outline = Color(0xFF3A3A3A)
)

private val SchemaSombreContraste = SchemaSombre.copy(
    primary = Color(0xFFBBDEFB), background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White, onSurfaceVariant = Color(0xFFE6E6E6),
    outline = Color(0xFFBDBDBD)
)

/**
 * Thème de l'application. Il lit [Settings], donc un changement de réglage
 * redessine l'écran immédiatement, sans redémarrage.
 *
 * L'échelle du texte passe par la densité et non par la typographie : la plupart
 * des textes de l'app donnent leur taille en dur (`fontSize = 14.sp`) et une
 * typographie Material ne les toucherait pas. En agissant sur `fontScale`, tout
 * grandit, y compris ces textes-là.
 */
@Composable
fun PmFibreTheme(content: @Composable () -> Unit) {
    val sombre = when (Settings.theme) {
        ThemeChoix.AUTO -> isSystemInDarkTheme()
        ThemeChoix.CLAIR -> false
        ThemeChoix.SOMBRE -> true
    }
    val contraste = Settings.contrasteEleve
    val schema = when {
        sombre && contraste -> SchemaSombreContraste
        sombre -> SchemaSombre
        contraste -> SchemaClairContraste
        else -> SchemaClair
    }
    val couleurs = when {
        sombre && contraste -> CouleursSombresContraste
        sombre -> CouleursSombres
        contraste -> CouleursClairesContraste
        else -> CouleursClaires
    }
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalCouleursPm provides couleurs,
        LocalDensity provides Density(d.density, d.fontScale * Settings.echelleTexte)
    ) {
        MaterialTheme(colorScheme = schema, content = content)
    }
}
