package com.pmfibre.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Le lecteur tar de [DepStore] est le seul analyseur binaire écrit à la main du
 * projet, et il s'applique à un fichier venu du réseau. Il est donc éprouvé ici
 * sur une archive réellement produite par `data/build_packages.py` (977,
 * Saint-Barthélemy, le plus petit département) et sur une archive piégée.
 */
class DepStoreTarTest {

    private fun ressource(nom: String): File {
        val url = javaClass.classLoader!!.getResource(nom)
            ?: throw AssertionError("ressource de test absente : $nom")
        return File(url.toURI())
    }

    @Test
    fun `lit les deux membres d'un paquet reel`() {
        val membres = DepStore.lireTarGz(ressource("paquet_reel.tgz"))

        assertEquals(setOf("pm.json", "zones.json"), membres.keys)
        val pm = String(membres["pm.json"]!!, Charsets.UTF_8)
        val zones = String(membres["zones.json"]!!, Charsets.UTF_8)
        // Les tailles sont lues dans l'en-tête tar : une erreur d'un octet se voit
        // au bout du contenu, pas au début.
        assertTrue("pm.json doit être un tableau JSON complet", pm.startsWith("[") && pm.endsWith("]"))
        assertTrue("zones.json doit être un objet JSON complet", zones.startsWith("{") && zones.endsWith("}"))
        // 14 PM dans le 977 d'après le manifeste ; on les compte sans parser
        // (org.json n'est pas disponible en test JVM).
        assertEquals(14, pm.split("\"code\":").size - 1)
    }

    @Test
    fun `ignore tout membre qui n'est pas pm_json ou zones_json`() {
        val membres = DepStore.lireTarGz(ressource("paquet_piege.tgz"))

        // L'archive contient aussi "../../evil.json" et "sous/dossier/autre.json".
        // Rien ne sort du lecteur sous un nom qui désignerait un chemin : c'est ce
        // qui rend la traversée de répertoire impossible en aval.
        assertEquals(setOf("pm.json", "zones.json"), membres.keys)
        assertFalse(membres.keys.any { it.contains("/") || it.contains("..") })
        // Les membres utiles restent lus correctement malgré les intrus
        // intercalés : le saut de bloc est aligné sur 512 octets.
        assertTrue(String(membres["pm.json"]!!).contains("X-1"))
        assertTrue(String(membres["zones.json"]!!).startsWith("{\"X-1\""))
    }
}
