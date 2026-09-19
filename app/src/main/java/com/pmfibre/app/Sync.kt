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

        val suppr = if (res.deleted.isNotEmpty()) " · ${res.deleted.size} retirée(s)" else ""
        val envoi = if (uploaded > 0) " · $uploaded envoyée(s)" else ""
        return "$merged position(s) partagée(s)$suppr$envoi"
    }
}
