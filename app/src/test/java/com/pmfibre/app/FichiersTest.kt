package com.pmfibre.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [Fichiers] est le filet qui empêche un JSON tronqué d'empêcher l'application
 * de démarrer. Un filet qu'on n'a pas vu retenir quelque chose n'est pas un
 * filet : chaque cas de panne est donc reproduit ici pour de vrai, sur des
 * fichiers réels, en corrompant le contenu comme le ferait une coupure de
 * courant au milieu d'une écriture.
 *
 * Aucune API Android n'est en jeu — c'est délibéré, et c'est ce qui rend ce
 * code éprouvable sans émulateur.
 */
class FichiersTest {

    private fun dossier(): File = Files.createTempDirectory("fichiers").toFile()

    /** Lecture d'un contenu ; `null` si rien de lisible. */
    private fun relit(dir: File, nom: String): Pair<Fichiers.Etat, String?> {
        var vu: String? = null
        val etat = Fichiers.lit(dir, nom) { t ->
            // Grossier, mais c'est exactement ce que fait un vrai analyseur JSON
            // devant un fichier coupé en plein milieu : il refuse.
            if (!t.startsWith("{") || !t.endsWith("}")) throw IllegalStateException("JSON incomplet")
            vu = t
        }
        return etat to vu
    }

    @Test
    fun `ecrit puis relit le contenu`() {
        val d = dossier()
        assertTrue(Fichiers.ecrit(d, "x.json", """{"a":1}"""))
        val (etat, vu) = relit(d, "x.json")
        assertEquals(Fichiers.Etat.COURANT, etat)
        assertEquals("""{"a":1}""", vu)
    }

    @Test
    fun `fichier absent n'est pas une erreur`() {
        val (etat, vu) = relit(dossier(), "jamais_ecrit.json")
        assertEquals(Fichiers.Etat.ABSENT, etat)
        assertNull(vu)
    }

    @Test
    fun `aucun fichier temporaire ne survit a une ecriture reussie`() {
        val d = dossier()
        Fichiers.ecrit(d, "x.json", """{"a":1}""")
        assertFalse(File(d, "x.json.tmp").exists())
    }

    /**
     * Le cœur du sujet : la deuxième écriture a réussi, puis le fichier courant
     * a été détruit. La version précédente doit revenir.
     */
    @Test
    fun `un fichier tronque retombe sur la copie de secours`() {
        val d = dossier()
        Fichiers.ecrit(d, "x.json", """{"v":1}""")
        Fichiers.ecrit(d, "x.json", """{"v":2}""")

        // Coupure de courant pendant une troisième écriture, version naïve :
        // le fichier existe, il est à moitié écrit.
        File(d, "x.json").writeText("""{"v":3,"incom""")

        val (etat, vu) = relit(d, "x.json")
        assertEquals(Fichiers.Etat.SECOURS, etat)
        assertEquals("""{"v":1}""", vu)   // la copie de secours = l'avant-dernière
    }

    @Test
    fun `un fichier corrompu est mis de cote et non efface`() {
        val d = dossier()
        Fichiers.ecrit(d, "x.json", """{"v":1}""")
        File(d, "x.json").writeText("tronque")
        relit(d, "x.json")
        assertTrue(File(d, "x.json.corrompu").exists())
        assertEquals("tronque", File(d, "x.json.corrompu").readText())
    }

    @Test
    fun `tout illisible est signale comme perdu`() {
        val d = dossier()
        Fichiers.ecrit(d, "x.json", "pas du json non plus")
        Fichiers.ecrit(d, "x.json", "tronque")
        val (etat, vu) = relit(d, "x.json")
        assertEquals(Fichiers.Etat.PERDU, etat)
        assertNull(vu)
    }

    /**
     * `applique` peut être appelé deux fois — une fois par fichier essayé. Un
     * appelant qui publierait son état au fil de l'eau garderait donc les
     * miettes du fichier cassé mêlées à la copie de secours. Le contrat est
     * documenté ; il est vérifié ici.
     */
    @Test
    fun `applique est rejoue sur la copie de secours`() {
        val d = dossier()
        Fichiers.ecrit(d, "x.json", """{"vieux":0}""")
        Fichiers.ecrit(d, "x.json", """{"bon":1}""")
        File(d, "x.json").writeText("""{"casse""")

        val essais = ArrayList<String>()
        Fichiers.lit(d, "x.json") { t ->
            essais.add(t)
            if (!t.endsWith("}")) throw IllegalStateException("tronque")
        }
        assertEquals(2, essais.size)
        assertEquals("""{"casse""", essais[0])
        assertEquals("""{"vieux":0}""", essais[1])
    }

    /**
     * Une écriture impossible (ici : le nom visé est un dossier) doit échouer
     * franchement et laisser l'ancien contenu en place, sans rien effacer.
     */
    @Test
    fun `une ecriture impossible preserve l'ancien contenu`() {
        val d = dossier()
        Fichiers.ecrit(d, "x.json", """{"v":1}""")
        // On rend l'ecriture provisoire impossible : le nom de travail est
        // occupe par un dossier, que `FileOutputStream` ne peut pas ouvrir.
        File(d, "x.json.tmp").mkdir()

        assertFalse(Fichiers.ecrit(d, "x.json", """{"v":2}"""))
        // L'ancien contenu n'a pas bouge : il n'a jamais ete decale en secours,
        // puisqu'on n'est jamais arrive jusque-la.
        assertEquals("""{"v":1}""", File(d, "x.json").readText())
        val (etat, vu) = relit(d, "x.json")
        assertEquals(Fichiers.Etat.COURANT, etat)
        assertEquals("""{"v":1}""", vu)
    }
}
