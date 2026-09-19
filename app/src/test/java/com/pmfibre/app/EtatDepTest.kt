package com.pmfibre.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ce qui est installé, face à ce que le manifeste décrit (§ F10).
 *
 * L'application lisait le millésime « installé » dans le manifeste téléchargé.
 * Or aller voir s'il y a du neuf remplace ce fichier : la vérification suivante
 * comparait le manifeste à lui-même et annonçait « à jour » alors qu'aucun
 * département n'avait bougé.
 */
class EtatDepTest {

    private fun info(code: String = "27", sha: String = "a1b2", pm: Int = 1200) =
        DepStore.DepInfo(
            code = code, nom = "Eure", region = "Normandie", pm = pm, exact = 41, zones = 1200,
            size = 82_000, sha256 = sha, url = "deps/$code.tgz"
        )

    private fun pose(sha: String?, dataset: String? = "2026T2", pm: Int = 1200) =
        DepStore.PaquetInstalle("27", dataset, sha, pm, 1_758_000_000_000L)

    @Test
    fun `meme empreinte, department a jour`() {
        assertEquals(DepStore.EtatDep.A_JOUR, etatDep(pose("a1b2"), info(sha = "a1b2")))
    }

    @Test
    fun `empreinte differente, department a mettre a jour`() {
        assertEquals(
            DepStore.EtatDep.A_METTRE_A_JOUR,
            etatDep(pose("a1b2"), info(sha = "ffff"))
        )
    }

    @Test
    fun `un millesime nouveau qui ne change rien a ce department ne le derange pas`() {
        // L'empreinte porte sur l'archive : un trimestre sans modification dans
        // l'Eure donne la même, et il n'y a rien à retélécharger. Comparer les
        // millésimes aurait fait redescendre les 103 paquets pour rien.
        assertEquals(
            DepStore.EtatDep.A_JOUR,
            etatDep(pose("a1b2", dataset = "2026T2"), info(sha = "A1B2"))
        )
    }

    @Test
    fun `un department non installe est absent`() {
        assertEquals(DepStore.EtatDep.ABSENT, etatDep(null, info()))
    }

    @Test
    fun `un paquet ancien se juge au nombre de PM`() {
        // Posé par une version qui ne notait rien : un écart de comptage prouve
        // que le référentiel a bougé depuis.
        assertEquals(
            DepStore.EtatDep.A_METTRE_A_JOUR,
            etatDep(pose(sha = null, dataset = null, pm = 1150), info(pm = 1200))
        )
    }

    @Test
    fun `un paquet ancien au meme comptage reste inconnu`() {
        // Le même nombre de PM ne prouve rien : on ne l'affirme donc pas à jour,
        // et le bandeau d'accueil — qui annonce des données nouvelles — se tait.
        assertEquals(
            DepStore.EtatDep.INCONNU,
            etatDep(pose(sha = null, dataset = null, pm = 1200), info(pm = 1200))
        )
    }

    @Test
    fun `un department sorti du manifeste n'est pas propose au telechargement`() {
        assertEquals(DepStore.EtatDep.INCONNU, etatDep(pose("a1b2"), null))
    }

    @Test
    fun `aucun department installe, aucun millesime`() {
        assertNull(millesimeDe(emptyList()))
    }

    @Test
    fun `des paquets de millesimes differents sont tous nommes`() {
        // On n'installe pas tout le même jour : élire un millésime en cacherait
        // un autre, et c'est précisément ce genre de raccourci qui a fait F10.
        val millesime = millesimeDe(listOf(
            pose("a", dataset = "2026T2"), pose("b", dataset = "2026T1"),
            pose("c", dataset = "2026T2")
        ))

        assertEquals("2026T1, 2026T2", millesime)
    }

    @Test
    fun `un paquet sans millesime est compte a part`() {
        val millesime = millesimeDe(listOf(
            pose("a", dataset = "2026T2"), pose(null, dataset = null)
        ))

        assertEquals("2026T2 (+ 1 inconnu(s))", millesime)
    }

    @Test
    fun `tous les paquets sans millesime`() {
        assertEquals("inconnu", millesimeDe(listOf(pose(null, null), pose(null, null))))
    }

    @Test
    fun `la fiche d'installation garde de quoi se comparer plus tard`() {
        val o = JSONObject(ficheInstallation("2026T2", info(sha = "a1b2"), 1_758_000_000_000L))

        assertEquals("2026T2", o.getString("dataset"))
        assertEquals("a1b2", o.getString("sha256"))
        assertEquals(1200, o.getInt("pm"))
        assertEquals(1_758_000_000_000L, o.getLong("installe_le"))
    }
}
