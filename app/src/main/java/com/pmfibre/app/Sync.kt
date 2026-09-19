package com.pmfibre.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Synchronisation bidirectionnelle des positions avec le serveur. */
object Sync {
    private const val PREFS = "pmfibre_sync"
    private const val K_SINCE = "positions_since"
    private const val K_PERIMETRE = "positions_deps"

    /**
     * Curseur de la dernière synchro aboutie, **pour ce périmètre**. null =
     * inventaire complet au prochain appel.
     *
     * Le périmètre est mémorisé avec le curseur parce qu'un curseur ne vaut que
     * pour lui : après l'installation d'un département, un différentiel « depuis
     * hier » ne rendrait rien du nouveau département, dont les positions sont
     * toutes antérieures. On repart donc de zéro dès que la liste change.
     */
    private fun curseur(context: Context, perimetre: String): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(K_PERIMETRE, "") != perimetre) return null
        return p.getString(K_SINCE, null)
    }

    private fun memoriseCurseur(context: Context, perimetre: String, valeur: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_PERIMETRE, perimetre).putString(K_SINCE, valeur).apply()
    }

    /** Repart d'un inventaire complet au prochain appel (base locale reconstruite…). */
    fun reinitialise(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_SINCE).remove(K_PERIMETRE).apply()
        MetaStore.reinitialise(context)
    }

    /**
     * 1) Remonte les positions locales « à moi » (captures propres / migrées d'une ancienne
     *    version) vers la base partagée. La règle des 10 m côté serveur évite les doublons.
     * 2) Redescend ce qui a changé depuis la dernière synchro et le fusionne localement.
     * Renvoie un court résumé lisible.
     *
     * Le curseur n'est mémorisé qu'une fois la fusion écrite : une synchro
     * interrompue est refaite à l'identique, jamais sautée.
     */
    suspend fun run(context: Context, token: String): String {
        val me = SessionStore.username ?: ""
        var uploaded = 0
        for ((code, p) in PmRepository.locallyOwnedPositions(me)) {
            try {
                ApiClient.putPosition(token, code, p.lat, p.lon, p.accuracyM)
                uploaded++
            } catch (_: Exception) {
                // 409 (< 10 m, déjà présente) ou hors-ligne : on ignore.
            }
        }
        // PM ajoutés par des collègues (hors ARCEP) -> fusion dans la base locale.
        try {
            val added = ApiClient.fetchAddedPms(token)
            withContext(Dispatchers.IO) { PmRepository.mergeAddedPms(context, added) }
        } catch (_: Exception) {}

        // Périmètre = départements installés. Vide (aucun paquet) -> le serveur
        // ne filtre pas : la synchro reste nationale tant qu'on ne lui demande rien.
        val deps = PmRepository.departements
        val perimetre = deps.joinToString(",")
        val res = ApiClient.syncPositions(token, curseur(context, perimetre), deps)
        val merged = withContext(Dispatchers.IO) { PmRepository.mergeServerPositions(context, res) }
        memoriseCurseur(context, perimetre, res.nextSince)

        val metas = syncMeta(context, token, deps, perimetre)
        val photos = envoiePhotos(context, token)

        val suppr = if (res.deleted.isNotEmpty()) " · ${res.deleted.size} retirée(s)" else ""
        val envoi = if (uploaded > 0) " · $uploaded envoyée(s)" else ""
        return "$merged position(s) partagée(s)$suppr$envoi$metas$photos"
    }

    /**
     * Étiquettes et indications d'accès (roadmap 3.6, 3.7).
     *
     * Les poses locales partent d'abord : la règle est que le local prime tant
     * qu'il n'est pas remonté, et redescendre avant d'envoyer inverserait cet
     * ordre le temps d'un appel, avec le risque d'écraser une pose faite hors
     * ligne la veille.
     *
     * L'ensemble est enveloppé : c'est un complément, pas le cœur de la synchro.
     * Un serveur d'une version antérieure, qui ne connaît pas `/sync/meta`, ne
     * doit pas faire échouer la synchro des positions.
     */
    private suspend fun syncMeta(context: Context, token: String,
                                 deps: List<String>, perimetre: String): String {
        var envoyes = 0
        try {
            for ((code, tags) in MetaStore.tagsEnAttente()) {
                try {
                    ApiClient.putTags(token, code, tags)
                    MetaStore.tagsEnvoyes(code)
                    envoyes++
                } catch (e: ApiClient.ApiException) {
                    // 404 (PM inconnu du serveur) ou 422 (étiquette qu'il ne
                    // connaît pas) : réessayer indéfiniment ne changerait rien,
                    // on cesse de la marquer en attente. Toute autre erreur —
                    // réseau, 500 — laisse le drapeau pour la prochaine fois.
                    if (e.status == 404 || e.status == 422) MetaStore.tagsEnvoyes(code)
                } catch (_: Exception) {
                }
            }
            for ((code, m) in MetaStore.accesEnAttente()) {
                try {
                    ApiClient.putAccess(token, code, m.note, m.accesLat, m.accesLon)
                    MetaStore.accesEnvoye(code)
                    envoyes++
                } catch (e: ApiClient.ApiException) {
                    if (e.status == 404 || e.status == 422) MetaStore.accesEnvoye(code)
                } catch (_: Exception) {
                }
            }

            val res = ApiClient.syncMeta(token, MetaStore.curseur(context, perimetre), deps)
            for (t in res.tags) MetaStore.serveurTags(t.code, MetaStore.meta(t.code).tags + t.tag)
            for (a in res.access) MetaStore.serveurAcces(a.code, a.note, a.lat, a.lon, a.author)
            for (d in res.deletedTags) {
                val i = d.lastIndexOf('|')
                if (i > 0) MetaStore.serveurTagRetire(d.substring(0, i), d.substring(i + 1))
            }
            for (c in res.deletedAccess) MetaStore.serveurAccesEfface(c)
            withContext(Dispatchers.IO) { MetaStore.ecrit(context) }
            MetaStore.memoriseCurseur(context, perimetre, res.nextSince)
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { MetaStore.ecrit(context) }
            return if (envoyes > 0) " · $envoyes étiquette(s)/accès envoyé(s)" else ""
        }
        return if (envoyes > 0) " · $envoyes étiquette(s)/accès envoyé(s)" else ""
    }

    /**
     * Vide la file d'attente des photos (roadmap 3.7).
     *
     * Une photo refusée pour de bon — PM inconnu, format rejeté, quota atteint —
     * quitte la file : la garder ferait retenter le même envoi à chaque
     * ouverture de l'application, indéfiniment et sur le forfait de
     * l'utilisateur. Une panne de réseau, elle, la laisse en place.
     */
    private suspend fun envoiePhotos(context: Context, token: String): String {
        var envoyees = 0
        for (e in PhotoStore.enAttente(context)) {
            val f = PhotoStore.fichierEnAttente(context, e)
            if (!f.exists()) { PhotoStore.retire(context, e.fichier); continue }
            try {
                val octets = withContext(Dispatchers.IO) { f.readBytes() }
                val meta = ApiClient.uploadPhoto(token, e.code, e.kind, octets)
                // Le fichier local devient le cache de la photo distante : elle
                // vient d'être envoyée, la retélécharger serait absurde.
                withContext(Dispatchers.IO) { PhotoStore.ecritCache(context, meta.id, octets) }
                PhotoStore.retire(context, e.fichier)
                envoyees++
            } catch (ex: ApiClient.ApiException) {
                if (ex.status in 400..499 && ex.status != 401 && ex.status != 429) {
                    PhotoStore.retire(context, e.fichier)
                }
            } catch (_: Exception) {
            }
        }
        return if (envoyees > 0) " · $envoyees photo(s) envoyée(s)" else ""
    }
}
