package com.pmfibre.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les PM que le référentiel ARCEP retire (§ F12).
 *
 * Le refresh trimestriel régénère les paquets, et l'application reconstruit ses
 * fiches à partir du nouveau. Un PM retiré disparaissait donc de la liste — mais
 * sa position, relevée sur place, restait dans `saved_positions.json` : le
 * relevé existait et plus personne ne pouvait l'atteindre.
 */
class FichesRetireesTest {

    private fun fiche(code: String, com: String = "Évreux") = Pm(
        code = code, oi = "FI", com = com, dep = "EURE", etat = "deploye",
        date = "2021-03-04", lgt = 320, tot = 340, lat = 49.0203, lon = 1.1501,
        precise = true, depCode = "27"
    )

    @Test
    fun `seules les fiches qui portent quelque chose sont gardees`() {
        val anciennes = listOf(fiche("FI-27229-0001"), fiche("FI-27229-0002"),
            fiche("FI-27229-0003"))

        val gardees = fichesRetireesAConserver(anciennes, setOf("FI-27229-0002"))

        assertEquals(listOf("FI-27229-0002"), gardees.map { it.code })
        assertTrue("la fiche gardée se dit retirée", gardees[0].retire)
    }

    @Test
    fun `une fiche gardee conserve ce que le dernier paquet en disait`() {
        // Réduire la fiche à son code et à ses coordonnées perdrait la commune
        // et l'opérateur, qui sont ce qu'on lit pour reconnaître le PM.
        val gardees = fichesRetireesAConserver(
            listOf(fiche("FI-27229-0001", com = "Saint-Marcel")), setOf("FI-27229-0001"))

        val p = gardees.single()
        assertEquals("Saint-Marcel", p.com)
        assertEquals("FI", p.oi)
        assertEquals("27", p.depCode)
        assertEquals(49.0203, p.lat, 1e-9)
        assertEquals(320, p.lgt)
    }

    @Test
    fun `aucune contribution, aucune fiche gardee`() {
        val gardees = fichesRetireesAConserver(listOf(fiche("FI-27229-0001")), emptySet())

        assertTrue("un PM qui n'a rien laissé n'encombre ni la recherche ni la carte",
            gardees.isEmpty())
    }

    @Test
    fun `une fiche retiree se relit a l'identique`() {
        val p = fichesRetireesAConserver(listOf(fiche("FI-27229-0001")),
            setOf("FI-27229-0001")).single()

        val relue = pmDepuisJson(JSONObject(p.enJson().toString()))

        assertEquals(p, relue)
        assertTrue(relue.retire)
    }

    @Test
    fun `une fiche de paquet n'est pas retiree`() {
        // Le même lecteur sert aux `pm.json` des paquets : sans clé `retire`,
        // la fiche est une fiche ordinaire.
        val o = JSONObject("""{"code":"FI-27229-0007","com":"Vernon","dep":"EURE",
            "etat":"deploye","lat":49.09,"lon":1.48,"lgt":12,"p":1}""")

        val p = pmDepuisJson(o, "27")

        assertFalse(p.retire)
        assertTrue(p.precise)
        assertEquals("27", p.depCode)
        assertEquals("Vernon", p.com)
        assertNull(p.op)
    }

    @Test
    fun `un PM ajoute sur le terrain se relit avec sa marque`() {
        val ajoute = Pm(code = "USR-27-001", oi = null, com = "Gaillon", dep = "EURE",
            etat = null, date = null, lgt = null, tot = null, lat = 49.16, lon = 1.34,
            precise = true, op = "Orange", userAdded = true, depCode = "27")

        val relu = pmDepuisJson(JSONObject(ajoute.enJson().toString()))

        assertEquals(ajoute, relu)
        assertTrue(relu.userAdded)
        assertFalse(relu.retire)
    }
}
