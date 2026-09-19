package com.pmfibre.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le format du fichier des positions enregistrées.
 *
 * Ce fichier est le seul endroit où vit un relevé pas encore remonté, et il
 * survit aux mises à jour de l'application : un champ écrit d'un côté et oublié
 * de l'autre ne se voit pas à la lecture du code, il se voit sur le terrain,
 * une fois la capture perdue ou dégradée.
 *
 * Le mode de saisie (`manual`, `method`) n'était pas retenu du tout : une
 * saisie au clavier faite hors ligne remontait en « GPS », et le serveur lui
 * appliquait la tolérance de zone de 500 m au lieu des 100 m prévus pour une
 * saisie manuelle (§ F07).
 */
class SavedPosJsonTest {

    @Test
    fun `une saisie manuelle hors ligne garde son mode`() {
        val p = SavedPos(49.0203, 1.1501, 1758000000000L, "sous la haie",
            author = "olivier", accuracyM = null, synced = false,
            manual = true, method = "manuel")

        val relue = savedPosDepuisJson(JSONObject(p.enJson().toString()))

        assertTrue("la saisie manuelle doit rester manuelle", relue.manual)
        assertEquals("manuel", relue.method)
        assertFalse(relue.synced)
        assertEquals(p, relue)
    }

    @Test
    fun `un releve GPS affine garde sa methode et sa precision`() {
        val p = SavedPos(49.0203, 1.1501, 1758000000000L, null,
            author = "amine", accuracyM = 4.5, synced = true,
            manual = false, method = "gps_precis")

        val relue = savedPosDepuisJson(JSONObject(p.enJson().toString()))

        assertEquals(p, relue)
        assertEquals(4.5, relue.accuracyM!!, 1e-9)
        assertEquals("gps_precis", relue.method)
    }

    @Test
    fun `un fichier ecrit par une version anterieure reste lisible`() {
        // Exactement ce que l'application d'avant écrivait : ni `m` ni `meth`.
        val ancien = JSONObject("""{"lat":49.02,"lon":1.15,"ts":1700000000000,"acc":8.0,"s":1}""")

        val relue = savedPosDepuisJson(ancien)

        assertEquals(49.02, relue.lat, 1e-9)
        assertTrue(relue.synced)
        assertFalse("faute de mieux, on la tient pour un relevé GPS", relue.manual)
        assertNull(relue.method)
        assertNull(relue.note)
        assertNull(relue.author)
    }

    @Test
    fun `le mode ne se transmet pas d'une capture a la suivante`() {
        // Une saisie manuelle puis, sur le même PM, un relevé GPS : la seconde
        // ne doit pas hériter du `manual` de la première, sans quoi le contrôle
        // de zone strict s'appliquerait à une capture qui ne le demande pas.
        val manuelle = SavedPos(49.02, 1.15, 1L, null, manual = true, method = "manuel")
        val gps = SavedPos(49.03, 1.16, 2L, null, accuracyM = 6.0)

        assertTrue(savedPosDepuisJson(JSONObject(manuelle.enJson().toString())).manual)
        assertFalse(savedPosDepuisJson(JSONObject(gps.enJson().toString())).manual)
    }

    @Test
    fun `le format reste compact quand il n'y a rien a dire`() {
        // Les clés facultatives absentes : un fichier de plusieurs centaines
        // d'entrées est relu à chaque démarrage.
        val nue = SavedPos(49.0, 1.0, 5L, null)
        val o = nue.enJson()

        assertFalse(o.has("note"))
        assertFalse(o.has("author"))
        assertFalse(o.has("acc"))
        assertFalse(o.has("s"))
        assertFalse(o.has("m"))
        assertFalse(o.has("meth"))
    }
}
