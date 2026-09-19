package com.pmfibre.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fusion des fixes GNSS est le seul calcul du client capable de produire une
 * position fausse ET crédible : un collègue s'y fierait. Elle est donc éprouvée
 * ici sur des séries construites à la main, avec les cas qui arrivent vraiment
 * sur le terrain — un fix aberrant, une convergence lente, un technicien qui
 * marche pendant la mesure.
 */
class GpsFusionTest {

    private fun serie(vararg t: Triple<Double, Double, Double>, depart: Long = 0L) =
        t.mapIndexed { i, (lat, lon, acc) ->
            // un fix par seconde, le premier à t = depart
            GpsFusion.Fix(lat, lon, acc, depart + i * 1000L)
        }

    @Test
    fun `la mediane ignore un fix aberrant la ou la moyenne le suit`() {
        // Neuf fixes serrés autour de 49,0200 puis un fix réfléchi par un mur,
        // à ~110 m au nord. La moyenne se déplacerait de ~11 m ; la médiane, non.
        val fixes = ArrayList<GpsFusion.Fix>()
        for (i in 0 until 9) fixes.add(GpsFusion.Fix(49.0200 + i * 1e-6, 1.1500, 5.0, 6000L + i * 1000L))
        fixes.add(GpsFusion.Fix(49.0210, 1.1500, 5.0, 15000L))

        val r = GpsFusion.fusionne(fixes, 0L)!!
        assertEquals(49.0200, r.lat, 1e-5)
        val moyenne = fixes.sumOf { it.lat } / fixes.size
        assertTrue("la moyenne, elle, serait tiree vers le nord", moyenne > 49.0200 + 5e-5)
    }

    @Test
    fun `les cinq premieres secondes sont jetees`() {
        // Convergence : trois fixes larges au début, trois bons ensuite.
        val fixes = serie(
            Triple(49.0300, 1.2000, 40.0),
            Triple(49.0301, 1.2000, 30.0),
            Triple(49.0302, 1.2000, 20.0),
            Triple(49.0200, 1.1500, 6.0),
            Triple(49.0200, 1.1500, 5.0),
            Triple(49.0200, 1.1500, 5.0)
        ).mapIndexed { i, f -> f.copy(tMs = i * 2000L) }   // un fix toutes les 2 s

        val r = GpsFusion.fusionne(fixes, 0L)!!
        assertEquals("seuls les fixes d'apres 5 s comptent", 3, r.retenus)
        assertEquals(49.0200, r.lat, 1e-6)
    }

    @Test
    fun `une validation anticipee se rabat sur ce qu'on a`() {
        // « Valider maintenant » au bout de 3 s : rien n'a passé la convergence.
        // Une position moyennée sur trois fixes tièdes vaut mieux que rien.
        val fixes = serie(
            Triple(49.0200, 1.1500, 12.0),
            Triple(49.0200, 1.1500, 11.0),
            Triple(49.0200, 1.1500, 12.0)
        )
        val r = GpsFusion.fusionne(fixes, 0L)!!
        assertEquals(3, r.retenus)
        assertEquals(12.0, r.accuracyM, 0.01)
    }

    @Test
    fun `la precision retenue est la mediane, pas le meilleur fix`() {
        val fixes = (0 until 5).map {
            GpsFusion.Fix(49.02, 1.15, doubleArrayOf(4.0, 5.0, 6.0, 6.0, 7.0)[it], 6000L + it * 1000L)
        }
        val r = GpsFusion.fusionne(fixes, 0L)!!
        // Le plafond vaut max(4 x 1,5 ; 4 + 3) = 7 -> les cinq sont retenus.
        assertEquals(5, r.retenus)
        assertEquals("la mediane, pas 4 m", 6.0, r.accuracyM, 0.01)
    }

    @Test
    fun `un fix trop imprecis est ecarte du calcul`() {
        val fixes = listOf(
            GpsFusion.Fix(49.0200, 1.1500, 5.0, 6000L),
            GpsFusion.Fix(49.0200, 1.1500, 5.0, 7000L),
            GpsFusion.Fix(49.0250, 1.1500, 45.0, 8000L)
        )
        val r = GpsFusion.fusionne(fixes, 0L)!!
        assertEquals(2, r.retenus)
        assertEquals(49.0200, r.lat, 1e-6)
    }

    @Test
    fun `la derive mesure l'ecart entre les fixes retenus`() {
        // ~111 m entre les deux latitudes (0,001 degre de latitude).
        val fixes = listOf(
            GpsFusion.Fix(49.0200, 1.1500, 5.0, 6000L),
            GpsFusion.Fix(49.0210, 1.1500, 5.0, 7000L)
        )
        val r = GpsFusion.fusionne(fixes, 0L)!!
        assertTrue("environ 111 m", r.deriveM > 100 && r.deriveM < 125)
        assertTrue(r.deriveM > GpsFusion.DERIVE_ALERTE_M)
    }

    @Test
    fun `sans aucun fix il n'y a pas de position`() {
        assertNull(GpsFusion.fusionne(emptyList(), 0L))
    }

    @Test
    fun `l'arret anticipe exige quantite, precision et stabilite`() {
        val bons = (0 until 10).map { GpsFusion.Fix(49.02, 1.15, 5.0, 6000L + it * 1000L) }
        assertTrue(GpsFusion.peutConclure(GpsFusion.fusionne(bons, 0L)))

        val tropPeu = bons.take(4)
        assertFalse("quatre mesures ne font pas une position stable",
            GpsFusion.peutConclure(GpsFusion.fusionne(tropPeu, 0L)))

        val impreccis = (0 until 10).map { GpsFusion.Fix(49.02, 1.15, 18.0, 6000L + it * 1000L) }
        assertFalse(GpsFusion.peutConclure(GpsFusion.fusionne(impreccis, 0L)))

        // Bonne precision annoncee mais l'utilisateur marche : 10 m de derive.
        val enMarche = (0 until 10).map {
            GpsFusion.Fix(49.02 + it * 1e-5, 1.15, 5.0, 6000L + it * 1000L)
        }
        assertFalse("une bonne precision annoncee ne suffit pas si ca bouge",
            GpsFusion.peutConclure(GpsFusion.fusionne(enMarche, 0L)))
    }

    @Test
    fun `la mediane d'un nombre pair de valeurs est la moyenne des deux centrales`() {
        assertEquals(2.5, GpsFusion.mediane(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
        assertEquals(3.0, GpsFusion.mediane(listOf(5.0, 1.0, 3.0)), 1e-9)
    }
}
