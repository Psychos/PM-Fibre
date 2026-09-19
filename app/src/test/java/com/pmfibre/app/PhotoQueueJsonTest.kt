package com.pmfibre.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La file d'attente des photos, et le sort d'une photo refusée (§ F08).
 *
 * Une photo de terrain n'existe qu'une fois : elle est prise devant le PM, et
 * on n'y retourne pas. Le serveur la refusait — quota du PM atteint, format —
 * et l'application effaçait le fichier, sans un mot. Elle la garde désormais,
 * hors de la file d'envoi, avec le motif du refus.
 */
class PhotoQueueJsonTest {

    private fun entree(
        nom: String = "1758000000000_42.jpg", refus: String? = null, refusTs: Long = 0L
    ) = PhotoStore.EnAttente(nom, "270040101", "acces", 1758000000000L, refus, refusTs)

    @Test
    fun `une photo a envoyer se relit a l'identique`() {
        val e = entree()
        val relue = enAttenteDepuisJson(JSONObject(e.enJson().toString()))

        assertEquals(e, relue)
        assertFalse(relue.refusee)
        assertNull(relue.refus)
    }

    @Test
    fun `le motif du refus survit a la fermeture de l'application`() {
        // Le refus est écrit dans le fichier, pas seulement gardé en mémoire :
        // sinon la photo repartirait au prochain démarrage et se ferait
        // refuser de nouveau, indéfiniment et sur le forfait de l'utilisateur.
        val e = entree(refus = "Ce PM a déjà 6 photos — supprime-en une d'abord",
            refusTs = 1758000123000L)

        val relue = enAttenteDepuisJson(JSONObject(e.enJson().toString()))

        assertEquals(e, relue)
        assertTrue(relue.refusee)
        assertEquals("Ce PM a déjà 6 photos — supprime-en une d'abord", relue.refus)
        assertEquals(1758000123000L, relue.refusTs)
    }

    @Test
    fun `une file ecrite par une version anterieure reste lisible`() {
        val ancien = JSONObject(
            """{"f":"1757000000000_7.jpg","code":"270040101","kind":"pm","ts":1757000000000}""")

        val relue = enAttenteDepuisJson(ancien)

        assertEquals("1757000000000_7.jpg", relue.fichier)
        assertEquals("pm", relue.kind)
        assertFalse("sans marque de refus, la photo est simplement à envoyer", relue.refusee)
        assertEquals(0L, relue.refusTs)
    }

    @Test
    fun `une photo refusee sort de la file d'envoi et y revient sur demande`() {
        val liste = listOf(entree("a.jpg"), entree("b.jpg"))

        val apres = appliqueRefus(liste, "b.jpg", "Format non reconnu", 1758000999000L)
        assertEquals(listOf("a.jpg"), apres.filterNot { it.refusee }.map { it.fichier })
        assertEquals(listOf("b.jpg"), apres.filter { it.refusee }.map { it.fichier })
        assertEquals("a.jpg", apres[0].fichier)   // l'ordre de la file est gardé

        val relancee = appliqueRefus(apres, "b.jpg", null, 0L)
        assertFalse(relancee[1].refusee)
        assertNull(relancee[1].refus)
        assertEquals("le refus levé ne laisse pas de date derrière lui", 0L, relancee[1].refusTs)
        assertEquals(liste, relancee)
    }

    @Test
    fun `refuser une photo n'en refuse pas une autre`() {
        val liste = listOf(entree("a.jpg"), entree("b.jpg"), entree("c.jpg"))

        val apres = appliqueRefus(liste, "b.jpg", "PM inconnu", 1L)

        assertEquals(3, apres.size)
        assertFalse(apres[0].refusee)
        assertFalse(apres[2].refusee)
    }

    @Test
    fun `le format reste compact quand il n'y a pas de refus`() {
        // Une file peut compter des dizaines d'entrées, relues à chaque synchro.
        val o = entree().enJson()

        assertFalse(o.has("refus"))
        assertFalse(o.has("refusTs"))
    }
}
