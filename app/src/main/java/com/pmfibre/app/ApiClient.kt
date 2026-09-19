package com.pmfibre.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client HTTP de l'API PM Fibre (FastAPI sur PsyOne).
 *
 * - En LAN (dev) : http://192.168.1.98:8080 (nécessite network_security_config cleartext).
 * - En prod (via Cloudflare Tunnel) : https://api.mapm.online.
 * Change BASE_URL pour basculer.
 */
object ApiClient {

    // Production via Cloudflare Tunnel (accessible partout, 4G comprise).
    // Pour un test en LAN direct, remettre "http://192.168.1.98:8080".
    var baseUrl: String = "https://api.mapm.online"

    // ---- DTOs ----
    data class TokenResult(val token: String, val username: String, val role: String)
    data class ServerPosition(
        val lat: Double, val lon: Double, val accuracyM: Double?,
        val author: String?, val updatedAt: String?
    )
    data class ServerPmPosition(
        val code: String, val lat: Double, val lon: Double,
        val author: String?, val updatedAt: String?,
        // Le serveur descend la précision et le mode de saisie ; on les ignorait,
        // et une position redescendue perdait donc ce qui dit ce qu'elle vaut.
        val accuracyM: Double? = null, val method: String? = null
    )
    /** Une synchro menée à son terme : `nextSince` n'est à mémoriser qu'après
     *  application locale, sinon un échec en cours de route ferait sauter un
     *  différentiel qui ne reviendra jamais. */
    data class SyncResult(
        val positions: List<ServerPmPosition>,
        val deleted: List<String>,
        val deps: List<String>,
        val nextSince: String,
        val full: Boolean
    )
    data class Comment(
        val id: Int, val body: String, val author: String?,
        val createdAt: String, val updatedAt: String
    )
    data class Stats(val positions: Int, val comments: Int, val confirmations: Int)
    data class LeaderboardEntry(
        val username: String, val positions: Int, val confirmations: Int, val comments: Int
    )
    data class AdminUser(
        val id: Int, val username: String, val email: String?, val role: String,
        val userType: String, val active: Boolean, val createdAt: String?, val lastLogin: String?
    )
    data class InvitationCodes(val interne: String, val externe: String)
    data class PmDetail(
        val positionStatus: String,
        val lat: Double?, val lon: Double?, val accuracyM: Double?,
        val author: String?, val updatedAt: String?,
        val confirmations: Int, val confirmedByMe: Boolean,
        val address: String?,
        // Date de retrait du referentiel ARCEP, si le PM n'y est plus (F12) :
        // la base le sait depuis l'import versionne, et la fiche le dit
        // desormais -- l'app garde la position relevee sur place, elle doit
        // pouvoir signaler que le PM ne fait plus partie du referentiel.
        val retiredAt: String? = null,
        // Etiquettes, acces et photos viennent avec la fiche : ils sont
        // affiches des l'ouverture, en tete, et trois appels de plus pour un
        // ecran qui s'ouvre en tournee ne se justifiaient pas (roadmap 3.7).
        val tags: List<String> = emptyList(),
        val accessNote: String? = null,
        val accessLat: Double? = null,
        val accessLon: Double? = null,
        val accessAuthor: String? = null,
        val photos: List<PhotoMeta> = emptyList()
    )

    class ApiException(val status: Int, message: String) : Exception(message)

    /** Appelé quand le serveur répond 401 à une requête authentifiée (peut venir d'un thread IO). */
    @Volatile var onSessionExpired: (() -> Unit)? = null

    /** Lit une chaîne éventuellement absente/null d'un JSONObject. */
    private fun optStr(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key)

    // ---- Auth (prénom + mot de passe + code d'équipe à l'inscription) ----
    suspend fun register(username: String, password: String, email: String?, invitationCode: String): TokenResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("username", username).put("password", password)
                .put("invitation_code", invitationCode)
            if (!email.isNullOrBlank()) body.put("email", email)
            val o = postJson("/auth/register", body, token = null)
            TokenResult(o.getString("token"), o.getString("username"), o.optString("role", "user"))
        }

    suspend fun login(username: String, password: String): TokenResult = withContext(Dispatchers.IO) {
        val o = postJson("/auth/login", JSONObject().put("username", username).put("password", password), token = null)
        TokenResult(o.getString("token"), o.getString("username"), o.optString("role", "user"))
    }

    // ---- Positions ----
    /** Nombre de pages maximal d'une synchro : 200 x 2000 positions.
     *  Garde-fou contre un serveur qui ne rendrait jamais `complete`. */
    private const val MAX_PAGES_SYNC = 200

    /**
     * Synchro des positions partagées, page par page, jusqu'à `complete`.
     *
     * Remplace l'ancien `/pm?has_position=true&limit=10000` : celui-ci demandait
     * exactement le plafond du serveur, si bien qu'une liste tronquée revenait
     * sous la même forme qu'une liste complète. La purge locale prenait alors
     * les positions manquantes pour des suppressions et effaçait du travail de
     * terrain. Ici une page inachevée le dit (`complete = false`), et tant que
     * la boucle n'a pas abouti rien n'est appliqué.
     *
     * @param since curseur du dernier appel réussi, ou null pour un inventaire complet
     * @param deps  départements demandés ; vide = France entière
     */
    suspend fun syncPositions(token: String, since: String?, deps: List<String> = emptyList()):
        SyncResult = withContext(Dispatchers.IO) {
        val positions = ArrayList<ServerPmPosition>()
        val deleted = ArrayList<String>()
        var curseur = since
        var pages = 0
        while (true) {
            val q = StringBuilder("/sync/positions?")
            if (!curseur.isNullOrEmpty()) q.append("since=").append(enc(curseur!!)).append('&')
            if (deps.isNotEmpty()) q.append("dep=").append(enc(deps.joinToString(","))).append('&')
            val o = requestJson("GET", q.toString().trimEnd('&', '?'), null, token)

            val arr = o.getJSONArray("positions")
            for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                if (x.isNull("lat") || x.isNull("lon")) continue
                positions.add(ServerPmPosition(
                    x.getString("code"), x.getDouble("lat"), x.getDouble("lon"),
                    optStr(x, "author"), optStr(x, "updated_at"),
                    if (x.isNull("accuracy_m")) null else x.optDouble("accuracy_m"),
                    optStr(x, "method")
                ))
            }
            val sup = o.getJSONArray("deleted")
            for (i in 0 until sup.length()) deleted.add(sup.getString(i))

            curseur = o.getString("next_since")
            if (o.optBoolean("complete", true)) break
            if (++pages >= MAX_PAGES_SYNC) {
                // Ne rien appliquer : une synchro partielle prise pour complète
                // est précisément le défaut qu'on corrige ici.
                throw ApiException(0, "Synchro interrompue (trop de pages) — réessaie plus tard.")
            }
        }
        SyncResult(positions, deleted, deps, curseur!!, full = since.isNullOrEmpty())
    }

    /** Publie/actualise la position exacte d'un PM. `manual` = saisie clavier (contrôle de zone strict). */
    /**
     * `method` décrit COMMENT la position a été obtenue (`gps_precis` pour une
     * capture moyennée sur trente secondes, `null` pour un fix unique). Le champ
     * est facultatif côté serveur : une version de l'app plus ancienne, ou un
     * serveur pas encore à jour, continuent de fonctionner sans lui.
     */
    suspend fun putPosition(token: String, code: String, lat: Double, lon: Double, accuracyM: Double?,
                            manual: Boolean = false, method: String? = null):
        ServerPosition = withContext(Dispatchers.IO) {
        val body = JSONObject().put("lat", lat).put("lon", lon).put("manual", manual)
        if (accuracyM != null) body.put("accuracy_m", accuracyM)
        if (method != null) body.put("method", method)
        val o = requestJson("PUT", "/pm/${enc(code)}/position", body, token)
        ServerPosition(
            o.getDouble("lat"), o.getDouble("lon"),
            if (o.isNull("accuracy_m")) null else o.optDouble("accuracy_m"),
            optStr(o, "author"), optStr(o, "updated_at")
        )
    }

    /** Supprime la position partagée d'un PM (admin uniquement, contrôlé serveur). */
    suspend fun deletePosition(token: String, code: String) = withContext(Dispatchers.IO) {
        requestJson("DELETE", "/pm/${enc(code)}/position", null, token)
        Unit
    }

    // ---- Commentaires ----
    suspend fun fetchComments(token: String, code: String): List<Comment> = withContext(Dispatchers.IO) {
        val arr = getArray("/pm/${enc(code)}/comments", token)
        buildList {
            for (i in 0 until arr.length()) add(parseComment(arr.getJSONObject(i)))
        }
    }

    suspend fun addComment(token: String, code: String, body: String): Comment = withContext(Dispatchers.IO) {
        val o = requestJson("POST", "/pm/${enc(code)}/comments", JSONObject().put("body", body), token)
        parseComment(o)
    }

    /** Édite un commentaire (autorisé si auteur ou admin, contrôlé côté serveur). */
    suspend fun editComment(token: String, id: Int, body: String): Comment = withContext(Dispatchers.IO) {
        parseComment(requestJson("PUT", "/comments/$id", JSONObject().put("body", body), token))
    }

    /** Supprime un commentaire (autorisé si auteur ou admin). */
    suspend fun deleteComment(token: String, id: Int) = withContext(Dispatchers.IO) {
        requestJson("DELETE", "/comments/$id", null, token)
        Unit
    }

    // ---- Profil / stats ----
    suspend fun updateProfile(token: String, email: String?, currentPassword: String?, newPassword: String?) =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
            if (!email.isNullOrBlank()) body.put("email", email)
            if (!newPassword.isNullOrBlank()) {
                body.put("current_password", currentPassword ?: "")
                body.put("new_password", newPassword)
            }
            requestJson("PUT", "/auth/profile", body, token)
            Unit
        }

    suspend fun fetchMyStats(token: String): Stats = withContext(Dispatchers.IO) {
        val o = requestJson("GET", "/auth/me/stats", null, token)
        Stats(o.optInt("positions_count", 0), o.optInt("comments_count", 0), o.optInt("confirmations_count", 0))
    }

    // ---- Hall of fame ----
    suspend fun fetchLeaderboard(token: String): List<LeaderboardEntry> = withContext(Dispatchers.IO) {
        val arr = getArray("/stats/leaderboard", token)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(LeaderboardEntry(
                    o.getString("username"), o.optInt("positions_count", 0),
                    o.optInt("confirmations_count", 0), o.optInt("comments_count", 0)
                ))
            }
        }
    }

    // ---- Administration des comptes ----
    suspend fun fetchUsers(token: String): List<AdminUser> = withContext(Dispatchers.IO) {
        val arr = getArray("/admin/users", token)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(AdminUser(
                    o.getInt("id"), o.getString("username"), optStr(o, "email"),
                    o.optString("role", "user"), o.optString("user_type", "interne"),
                    o.optBoolean("active", true),
                    optStr(o, "created_at"), optStr(o, "last_login")
                ))
            }
        }
    }

    suspend fun fetchUserStats(token: String, id: Int): Stats = withContext(Dispatchers.IO) {
        val o = requestJson("GET", "/admin/users/$id/stats", null, token)
        Stats(o.optInt("positions_count", 0), o.optInt("comments_count", 0), o.optInt("confirmations_count", 0))
    }

    suspend fun setUserRole(token: String, id: Int, role: String) = withContext(Dispatchers.IO) {
        requestJson("PUT", "/admin/users/$id/role", JSONObject().put("role", role), token)
        Unit
    }

    suspend fun setUserActive(token: String, id: Int, active: Boolean) = withContext(Dispatchers.IO) {
        requestJson("POST", "/admin/users/$id/" + (if (active) "activate" else "deactivate"), JSONObject(), token)
        Unit
    }

    suspend fun deleteUser(token: String, id: Int) = withContext(Dispatchers.IO) {
        requestJson("DELETE", "/admin/users/$id", null, token)
        Unit
    }

    suspend fun resetUserPassword(token: String, id: Int): String = withContext(Dispatchers.IO) {
        val o = requestJson("POST", "/admin/users/$id/reset-password", JSONObject(), token)
        o.getString("temporary_password")
    }

    suspend fun fetchInvitationCodes(token: String): InvitationCodes = withContext(Dispatchers.IO) {
        val o = requestJson("GET", "/admin/invitation-codes", null, token)
        InvitationCodes(o.optString("code_interne", ""), o.optString("code_externe", ""))
    }

    suspend fun setInvitationCodes(token: String, interne: String, externe: String): InvitationCodes =
        withContext(Dispatchers.IO) {
            val o = requestJson("PUT", "/admin/invitation-codes",
                JSONObject().put("code_interne", interne).put("code_externe", externe), token)
            InvitationCodes(o.optString("code_interne", ""), o.optString("code_externe", ""))
        }

    // ---- Détail serveur d'une fiche (confirmations, auteur/précision de la position) ----
    private fun parsePmDetail(o: JSONObject): PmDetail {
        val pos = if (o.isNull("position")) null else o.getJSONObject("position")
        val acces = if (o.isNull("access")) null else o.optJSONObject("access")
        return PmDetail(
            positionStatus = o.optString("position_status", "inconnue"),
            lat = pos?.optDouble("lat"), lon = pos?.optDouble("lon"),
            accuracyM = pos?.let { if (it.isNull("accuracy_m")) null else it.optDouble("accuracy_m") },
            author = pos?.let { optStr(it, "author") },
            updatedAt = pos?.let { optStr(it, "updated_at") },
            confirmations = o.optInt("confirmations", 0),
            confirmedByMe = o.optBoolean("confirmed_by_me", false),
            address = optStr(o, "address"),
            retiredAt = optStr(o, "retired_at"),
            tags = o.optJSONArray("tags")?.let { a ->
                buildList { for (i in 0 until a.length()) add(a.getString(i)) }
            } ?: emptyList(),
            accessNote = acces?.let { optStr(it, "note") },
            accessLat = acces?.let { if (it.isNull("lat")) null else it.getDouble("lat") },
            accessLon = acces?.let { if (it.isNull("lon")) null else it.getDouble("lon") },
            accessAuthor = acces?.let { optStr(it, "author") },
            photos = o.optJSONArray("photos")?.let { a ->
                buildList { for (i in 0 until a.length()) add(parsePhoto(a.getJSONObject(i))) }
            } ?: emptyList()
        )
    }

    suspend fun fetchPmDetail(token: String, code: String): PmDetail = withContext(Dispatchers.IO) {
        parsePmDetail(requestJson("GET", "/pm/${enc(code)}", null, token))
    }

    suspend fun confirmPosition(token: String, code: String): PmDetail = withContext(Dispatchers.IO) {
        parsePmDetail(requestJson("POST", "/pm/${enc(code)}/confirm", JSONObject(), token))
    }

    // ---- PM ajoutés par les utilisateurs (hors ARCEP) ----
    private fun parseAddedPm(o: JSONObject): Pm? {
        val pos = if (o.isNull("position")) null else o.getJSONObject("position")
        val lat = pos?.optDouble("lat") ?: return null
        val lon = pos?.optDouble("lon") ?: return null
        return Pm(
            code = o.optString("code"),
            oi = optStr(o, "oi"),
            com = optStr(o, "com"),
            dep = optStr(o, "dep"),
            etat = optStr(o, "etat"),
            date = optStr(o, "date_pm"),
            lgt = if (o.isNull("lgt")) null else o.optInt("lgt"),
            tot = if (o.isNull("tot")) null else o.optInt("tot"),
            lat = lat, lon = lon, precise = false,
            op = optStr(o, "op"), userAdded = true,
            depCode = optStr(o, "dep_code")
        )
    }

    suspend fun createPm(
        token: String, code: String?, op: String?, com: String, depCode: String?,
        lat: Double, lon: Double, accuracyM: Double?
    ): Pm = withContext(Dispatchers.IO) {
        val body = JSONObject().put("com", com).put("lat", lat).put("lon", lon)
        if (!code.isNullOrBlank()) body.put("code", code)
        if (!op.isNullOrBlank()) body.put("op", op)
        if (!depCode.isNullOrBlank()) body.put("dep_code", depCode)
        if (accuracyM != null) body.put("accuracy_m", accuracyM)
        parseAddedPm(requestJson("POST", "/pm", body, token))
            ?: throw ApiException(500, "Réponse de création invalide")
    }

    suspend fun fetchAddedPms(token: String): List<Pm> = withContext(Dispatchers.IO) {
        val arr = getArray("/pm-added", token)
        buildList { for (i in 0 until arr.length()) parseAddedPm(arr.getJSONObject(i))?.let { add(it) } }
    }

    private fun parseComment(o: JSONObject) = Comment(
        o.getInt("id"), o.getString("body"), optStr(o, "author"),
        o.optString("created_at", ""), o.optString("updated_at", "")
    )

    // ---- Étiquettes et indication d'accès (roadmap 3.6, 3.7) ----

    /** L'ensemble complet des étiquettes du PM, pas un ajout : le serveur en
     *  déduit lui-même les poses et les retraits. */
    suspend fun putTags(token: String, code: String, tags: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("tags", JSONArray(tags))
            val (status, text) = rawRequest("PUT", "/pm/${enc(code)}/tags", body.toString(), token)
            if (status !in 200..299) throw ApiException(status, extractError(text, status))
            val arr = JSONArray(text)
            buildList { for (i in 0 until arr.length()) add(arr.getJSONObject(i).getString("tag")) }
        }

    suspend fun putAccess(token: String, code: String, note: String?, lat: Double?, lon: Double?) =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
            body.put("note", note ?: JSONObject.NULL)
            body.put("lat", lat ?: JSONObject.NULL)
            body.put("lon", lon ?: JSONObject.NULL)
            requestJson("PUT", "/pm/${enc(code)}/access", body, token)
            Unit
        }

    data class MetaTag(val code: String, val tag: String)
    data class MetaAccess(
        val code: String, val note: String?, val lat: Double?, val lon: Double?, val author: String?
    )
    /** Étiquettes et accès d'une synchro menée à son terme. `deletedTags`
     *  contient des couples « code|étiquette », comme les rend le serveur. */
    data class SyncMetaResult(
        val tags: List<MetaTag>,
        val access: List<MetaAccess>,
        val deletedTags: List<String>,
        val deletedAccess: List<String>,
        val nextSince: String,
        val full: Boolean
    )

    /**
     * Synchro des étiquettes et des indications d'accès, page par page.
     *
     * Même contrat que `syncPositions` : rien n'est appliqué tant que la boucle
     * n'a pas abouti, et le curseur ne vaut que pour le périmètre demandé.
     */
    suspend fun syncMeta(token: String, since: String?, deps: List<String> = emptyList()):
        SyncMetaResult = withContext(Dispatchers.IO) {
        val tags = ArrayList<MetaTag>()
        val access = ArrayList<MetaAccess>()
        val delTags = ArrayList<String>()
        val delAccess = ArrayList<String>()
        var curseur = since
        var pages = 0
        while (true) {
            val q = StringBuilder("/sync/meta?")
            if (!curseur.isNullOrEmpty()) q.append("since=").append(enc(curseur!!)).append('&')
            if (deps.isNotEmpty()) q.append("dep=").append(enc(deps.joinToString(","))).append('&')
            val o = requestJson("GET", q.toString().trimEnd('&', '?'), null, token)

            val at = o.getJSONArray("tags")
            for (i in 0 until at.length()) {
                val e = at.getJSONObject(i)
                tags.add(MetaTag(e.getString("code"), e.getString("tag")))
            }
            val aa = o.getJSONArray("access")
            for (i in 0 until aa.length()) {
                val e = aa.getJSONObject(i)
                access.add(MetaAccess(
                    e.getString("code"), optStr(e, "note"),
                    if (e.isNull("lat")) null else e.getDouble("lat"),
                    if (e.isNull("lon")) null else e.getDouble("lon"),
                    optStr(e, "author")))
            }
            o.optJSONArray("deleted_tags")?.let { a ->
                for (i in 0 until a.length()) delTags.add(a.getString(i))
            }
            o.optJSONArray("deleted_access")?.let { a ->
                for (i in 0 until a.length()) delAccess.add(a.getString(i))
            }

            curseur = o.getString("next_since")
            if (o.optBoolean("complete", true)) break
            if (++pages >= MAX_PAGES_SYNC) throw ApiException(500, "Synchro interminable")
        }
        SyncMetaResult(tags, access, delTags, delAccess, curseur!!, since.isNullOrEmpty())
    }

    // ---- Photos (roadmap 3.7) ----

    data class PhotoMeta(
        val id: Int, val code: String, val kind: String,
        val bytes: Int?, val width: Int?, val height: Int?,
        val author: String?, val createdAt: String?
    )

    private fun parsePhoto(o: JSONObject) = PhotoMeta(
        o.getInt("id"), o.optString("code", ""), o.optString("kind", "pm"),
        if (o.isNull("bytes")) null else o.optInt("bytes"),
        if (o.isNull("width")) null else o.optInt("width"),
        if (o.isNull("height")) null else o.optInt("height"),
        optStr(o, "author"), optStr(o, "created_at")
    )

    suspend fun fetchPhotos(token: String, code: String): List<PhotoMeta> =
        withContext(Dispatchers.IO) {
            val arr = getArray("/pm/${enc(code)}/photos", token)
            buildList { for (i in 0 until arr.length()) add(parsePhoto(arr.getJSONObject(i))) }
        }

    /** Dépose une photo déjà compressée par `PhotoStore`. */
    suspend fun uploadPhoto(token: String, code: String, kind: String, octets: ByteArray): PhotoMeta =
        withContext(Dispatchers.IO) {
            val (status, text) = multipart("/pm/${enc(code)}/photos", token, kind, octets)
            if (status !in 200..299) throw ApiException(status, extractError(text, status))
            parsePhoto(JSONObject(text))
        }

    suspend fun downloadPhoto(token: String, id: Int): ByteArray = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl/photos/$id")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = conn.responseCode
            if (status == 401) onSessionExpired?.invoke()
            if (status !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                throw ApiException(status, extractError(err, status))
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun deletePhoto(token: String, id: Int) = withContext(Dispatchers.IO) {
        val (status, text) = rawRequest("DELETE", "/photos/$id", null, token)
        if (status !in 200..299) throw ApiException(status, extractError(text, status))
        Unit
    }

    /**
     * Envoi multipart, écrit à la main.
     *
     * Le corps est construit en mémoire : la photo fait ~200 Ko après passage
     * par `PhotoStore`, et connaître sa longueur d'avance évite le `chunked`,
     * que certains proxies mobiles traitent mal.
     */
    private fun multipart(path: String, token: String, kind: String, octets: ByteArray): Pair<Int, String> {
        val limite = "----pmfibre" + System.currentTimeMillis()
        val saut = "\r\n"
        val entete = (
            "--$limite$saut" +
                "Content-Disposition: form-data; name=\"kind\"$saut$saut$kind$saut" +
                "--$limite$saut" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"$saut" +
                "Content-Type: image/jpeg$saut$saut"
            ).toByteArray(Charsets.UTF_8)
        val pied = "$saut--$limite--$saut".toByteArray(Charsets.UTF_8)

        val url = URL(baseUrl + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000          // 200 Ko sur une 4G de campagne
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
            setFixedLengthStreamingMode(entete.size + octets.size + pied.size)
        }
        try {
            conn.outputStream.use { flux ->
                flux.write(entete); flux.write(octets); flux.write(pied)
            }
            val status = conn.responseCode
            if (status == 401) onSessionExpired?.invoke()
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            return status to (stream?.bufferedReader()?.use(BufferedReader::readText) ?: "")
        } finally {
            conn.disconnect()
        }
    }

    // ---- Bas niveau HTTP ----
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun postJson(path: String, body: JSONObject, token: String?): JSONObject =
        requestJson("POST", path, body, token)

    private fun getArray(path: String, token: String): JSONArray {
        val (status, text) = rawRequest("GET", path, null, token)
        if (status !in 200..299) throw ApiException(status, extractError(text, status))
        return JSONArray(text)
    }

    private fun requestJson(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val (status, text) = rawRequest(method, path, body?.toString(), token)
        if (status !in 200..299) throw ApiException(status, extractError(text, status))
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun rawRequest(method: String, path: String, jsonBody: String?, token: String?): Pair<Int, String> {
        val url = URL(baseUrl + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (jsonBody != null) conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            // Jeton refusé alors qu'on en présentait un : session évincée (2 appareils max),
            // expirée ou compte désactivé → prévenir l'app pour repasser au login.
            if (status == 401 && token != null) onSessionExpired?.invoke()
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    /** Extrait le message d'erreur FastAPI ({"detail": "..."}) ou renvoie un message générique. */
    private fun extractError(text: String, status: Int): String = try {
        JSONObject(text).optString("detail", "Erreur $status")
    } catch (e: Exception) {
        "Erreur $status"
    }
}
