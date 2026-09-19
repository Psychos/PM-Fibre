package com.pmfibre.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Étiquettes et indication d'accès d'un PM, côté téléphone (roadmap 3.6, 3.7).
 *
 * Tout passe d'abord par ici, y compris quand le réseau est là : la roadmap
 * demande la pose **hors ligne avec synchro différée**, et un PM au fond d'une
 * zone sans réseau est précisément celui qu'on a besoin d'étiqueter. L'écran
 * n'attend donc jamais le serveur — il écrit local, et la synchro rattrape.
 *
 * Deux drapeaux « sale » par fiche, un par famille : sans eux, la première
 * synchro après une pose hors ligne écraserait la pose avec l'état du serveur,
 * qui ne la connaît pas encore. Ils tiennent aussi la règle de résolution : tant
 * qu'une modification locale n'est pas remontée, elle prime sur ce qui descend.
 */
data class PmMeta(
    val tags: List<String> = emptyList(),
    val note: String? = null,
    val accesLat: Double? = null,
    val accesLon: Double? = null,
    val auteurAcces: String? = null,
    val tagsSales: Boolean = false,
    val accesSale: Boolean = false,
) {
    val vide: Boolean
        get() = tags.isEmpty() && note == null && accesLat == null && !tagsSales && !accesSale
    val aUnPointAcces: Boolean get() = accesLat != null && accesLon != null
}

object MetaStore {

    private const val FICHIER = "pm_meta.json"
    private const val PREFS = "pmfibre_sync"
    private const val K_SINCE = "meta_since"
    private const val K_PERIMETRE = "meta_deps"

    private val metas = HashMap<String, PmMeta>()

    @Volatile private var charge = false

    fun charge(context: Context) {
        metas.clear()
        val etat = Fichiers.lit(context.filesDir, FICHIER) { texte ->
            val lues = HashMap<String, PmMeta>()
            val o = JSONObject(texte)
            for (code in o.keys()) {
                val e = o.getJSONObject(code)
                val tags = ArrayList<String>()
                e.optJSONArray("tags")?.let { a ->
                    for (i in 0 until a.length()) tags.add(a.getString(i))
                }
                lues[code] = PmMeta(
                    tags = tags,
                    note = e.optString("note", "").ifEmpty { null },
                    accesLat = if (e.has("lat")) e.getDouble("lat") else null,
                    accesLon = if (e.has("lon")) e.getDouble("lon") else null,
                    auteurAcces = e.optString("auteur", "").ifEmpty { null },
                    tagsSales = e.optInt("td", 0) == 1,
                    accesSale = e.optInt("ad", 0) == 1,
                )
            }
            metas.clear()
            metas.putAll(lues)
        }
        if (etat == Fichiers.Etat.SECOURS || etat == Fichiers.Etat.PERDU) {
            // Le commentaire d'avant disait « la synchro suivante redescend tout
            // ce que le serveur connaît » : c'était faux. Le curseur, lui,
            // survivait au fichier perdu, et la synchro suivante ne redescendait
            // que le différentiel — donc rien. Les étiquettes et les accès
            // disparaissaient du téléphone en restant présents sur le serveur.
            reinitialise(context)
        }
        charge = true
    }

    private fun persiste(context: Context) {
        val o = JSONObject()
        for ((code, m) in metas) {
            if (m.vide) continue
            o.put(code, JSONObject().apply {
                if (m.tags.isNotEmpty()) put("tags", JSONArray(m.tags))
                m.note?.let { put("note", it) }
                m.accesLat?.let { put("lat", it) }
                m.accesLon?.let { put("lon", it) }
                m.auteurAcces?.let { put("auteur", it) }
                if (m.tagsSales) put("td", 1)
                if (m.accesSale) put("ad", 1)
            })
        }
        Fichiers.ecrit(context.filesDir, FICHIER, o.toString())
    }

    fun meta(code: String?): PmMeta = metas[code] ?: PmMeta()

    fun tags(code: String?): List<String> = meta(code).tags

    /** Nombre de fiches portant au moins une étiquette ou une indication d'accès. */
    val renseignes: Int get() = metas.count { !it.value.vide }

    // ---- Modifications locales ----

    fun poseTags(context: Context, code: String, tags: Collection<String>) {
        val m = meta(code)
        metas[code] = m.copy(tags = Etiquettes.ordonne(tags), tagsSales = true)
        persiste(context)
    }

    fun poseAcces(context: Context, code: String, note: String?,
                  lat: Double?, lon: Double?, auteur: String?) {
        val m = meta(code)
        metas[code] = m.copy(
            note = note?.trim()?.ifEmpty { null },
            accesLat = lat, accesLon = lon, auteurAcces = auteur, accesSale = true)
        persiste(context)
    }

    // ---- Application de ce qui descend du serveur ----

    /**
     * Étiquettes d'un PM telles que le serveur les connaît.
     *
     * Ignorées si une pose locale attend d'être remontée : le serveur n'a pas
     * encore vu cette pose, son état est donc plus vieux, pas plus juste.
     */
    fun serveurTags(code: String, tags: Collection<String>) {
        val m = meta(code)
        if (m.tagsSales) return
        metas[code] = m.copy(tags = Etiquettes.ordonne(tags))
    }

    fun serveurAcces(code: String, note: String?, lat: Double?, lon: Double?, auteur: String?) {
        val m = meta(code)
        if (m.accesSale) return
        metas[code] = m.copy(note = note, accesLat = lat, accesLon = lon, auteurAcces = auteur)
    }

    fun serveurAccesEfface(code: String) {
        val m = meta(code)
        if (m.accesSale) return
        metas[code] = m.copy(note = null, accesLat = null, accesLon = null, auteurAcces = null)
    }

    fun serveurTagRetire(code: String, tag: String) {
        val m = meta(code)
        if (m.tagsSales || tag !in m.tags) return
        metas[code] = m.copy(tags = m.tags - tag)
    }

    fun ecrit(context: Context) = persiste(context)

    // ---- File d'attente de remontée ----

    /** Fiches dont les étiquettes attendent d'être envoyées. */
    fun tagsEnAttente(): List<Pair<String, List<String>>> =
        metas.filter { it.value.tagsSales }.map { it.key to it.value.tags }

    /** Fiches dont l'indication d'accès attend d'être envoyée. */
    fun accesEnAttente(): List<Pair<String, PmMeta>> =
        metas.filter { it.value.accesSale }.map { it.key to it.value }

    fun tagsEnvoyes(code: String) {
        metas[code]?.let { metas[code] = it.copy(tagsSales = false) }
    }

    fun accesEnvoye(code: String) {
        metas[code]?.let { metas[code] = it.copy(accesSale = false) }
    }

    // ---- Curseur de synchro (même règle que les positions) ----

    fun curseur(context: Context, perimetre: String): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(K_PERIMETRE, "") != perimetre) return null
        return p.getString(K_SINCE, null)
    }

    fun memoriseCurseur(context: Context, perimetre: String, valeur: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_PERIMETRE, perimetre).putString(K_SINCE, valeur).apply()
    }

    fun reinitialise(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_SINCE).remove(K_PERIMETRE).apply()
    }

    /** Vrai une fois `charge()` passé : l'écran ne lit rien avant. */
    fun pret(): Boolean = charge
}
