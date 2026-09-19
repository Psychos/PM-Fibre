package com.pmfibre.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le catalogue des photos connues (§ F09).
 *
 * Les octets des photos étaient gardés sur le téléphone, mais la liste des
 * identifiants venait d'un appel réseau et ne survivait pas à la fermeture de
 * l'application. Rouvrir une fiche hors ligne ne savait donc plus quoi chercher
 * dans le cache : les photos étaient là et restaient invisibles.
 */
class PhotoCatalogueTest {

    private fun photo(id: Int, code: String = "FI-27229-0001", kind: String = "pm") =
        ApiClient.PhotoMeta(
            id = id, code = code, kind = kind, bytes = 187_000, width = 1600, height = 1200,
            author = "olivier", createdAt = "2026-09-18T09:12:00"
        )

    @Test
    fun `une photo connue se relit a l'identique apres fermeture`() {
        val cat = mapOf("FI-27229-0001" to listOf(photo(12), photo(13, kind = "acces")))

        val relu = catalogueDepuisJson(catalogueEnJson(cat))

        assertEquals(cat, relu)
    }

    @Test
    fun `une photo sans metadonnees facultatives se relit sans les inventer`() {
        val nue = ApiClient.PhotoMeta(
            id = 7, code = "FI-27229-0001", kind = "pm",
            bytes = null, width = null, height = null, author = null, createdAt = null
        )

        val relue = catalogueDepuisJson(
            catalogueEnJson(mapOf("FI-27229-0001" to listOf(nue)))
        )["FI-27229-0001"]!!.single()

        assertEquals(nue, relue)
        assertNull(relue.author)
        assertNull(relue.bytes)
    }

    @Test
    fun `l'ordre de consultation survit a la relecture`() {
        // C'est lui qui désigne le PM qui sortira du catalogue une fois plein :
        // il est une donnée, pas une commodité d'affichage.
        val cat = linkedMapOf(
            "A" to listOf(photo(1, "A")), "B" to listOf(photo(2, "B")),
            "C" to listOf(photo(3, "C"))
        )

        val relu = catalogueDepuisJson(catalogueEnJson(cat))

        assertEquals(listOf("A", "B", "C"), relu.keys.toList())
    }

    @Test
    fun `une photo effacee chez le serveur libere ses octets`() {
        val avant = mapOf("A" to listOf(photo(1, "A"), photo(2, "A")))

        val maj = majCatalogue(avant, "A", listOf(photo(1, "A")), maxPm = 10)

        assertEquals(listOf(1), maj.catalogue["A"]!!.map { it.id })
        assertEquals("plus rien ne mène aux octets de la 2", listOf(2), maj.aOublier)
    }

    @Test
    fun `un PM dont le serveur ne rend plus aucune photo sort du catalogue`() {
        val avant = mapOf("A" to listOf(photo(1, "A")), "B" to listOf(photo(2, "B")))

        val maj = majCatalogue(avant, "A", emptyList(), maxPm = 10)

        assertEquals(listOf("B"), maj.catalogue.keys.toList())
        assertEquals(listOf(1), maj.aOublier)
    }

    @Test
    fun `le catalogue plein evince le PM le plus anciennement consulte`() {
        val avant = linkedMapOf(
            "A" to listOf(photo(1, "A"), photo(2, "A")), "B" to listOf(photo(3, "B"))
        )

        val maj = majCatalogue(avant, "C", listOf(photo(4, "C")), maxPm = 2)

        assertEquals(listOf("B", "C"), maj.catalogue.keys.toList())
        assertEquals("les octets du PM évincé partent avec lui", listOf(1, 2), maj.aOublier)
    }

    @Test
    fun `rouvrir un PM le met a l'abri de l'eviction`() {
        val avant = linkedMapOf("A" to listOf(photo(1, "A")), "B" to listOf(photo(2, "B")))

        val maj = majCatalogue(avant, "A", listOf(photo(1, "A")), maxPm = 2)

        assertEquals(listOf("B", "A"), maj.catalogue.keys.toList())
        assertTrue("rien n'est sorti, rien n'est à oublier", maj.aOublier.isEmpty())
    }

    @Test
    fun `chaque photo retrouve le code de son PM`() {
        // Le code n'est écrit qu'une fois, en tête de la liste : une photo relue
        // sans son code ne saurait plus quelle fiche elle illustre.
        val relu = catalogueDepuisJson(
            catalogueEnJson(mapOf("FI-14118-000B" to listOf(photo(9, "FI-14118-000B"))))
        )

        assertEquals("FI-14118-000B", relu["FI-14118-000B"]!!.single().code)
    }
}
