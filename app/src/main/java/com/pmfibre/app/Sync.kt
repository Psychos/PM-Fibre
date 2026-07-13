package com.pmfibre.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Synchronisation bidirectionnelle des positions avec le serveur. */
object Sync {
    /**
     * 1) Remonte les positions locales « à moi » (captures propres / migrées d'une ancienne
     *    version) vers la base partagée. La règle des 10 m côté serveur évite les doublons.
     * 2) Redescend toutes les positions partagées et les fusionne localement.
     * Renvoie un court résumé lisible.
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

        val list = ApiClient.fetchAllPositions(token)
        val merged = withContext(Dispatchers.IO) { PmRepository.mergeServerPositions(context, list) }
        return "$merged position(s) partagée(s)" + if (uploaded > 0) " · $uploaded envoyée(s)" else ""
    }
}
