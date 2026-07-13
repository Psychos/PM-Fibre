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
        val author: String?, val updatedAt: String?
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
        val address: String?
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
    /** Récupère toutes les positions exactes du périmètre (pour synchro locale). */
    suspend fun fetchAllPositions(token: String): List<ServerPmPosition> = withContext(Dispatchers.IO) {
        // limit=10000 (= plafond serveur) : la purge locale de mergeServerPositions exige
        // une liste COMPLÈTE — une liste tronquée effacerait des positions valides.
        val arr = getArray("/pm?has_position=true&limit=10000", token)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.isNull("lat") && !o.isNull("lon")) {
                    add(ServerPmPosition(
                        o.getString("code"), o.getDouble("lat"), o.getDouble("lon"),
                        optStr(o, "author"), optStr(o, "updated_at")
                    ))
                }
            }
        }
    }

    /** Publie/actualise la position exacte d'un PM. `manual` = saisie clavier (contrôle de zone strict). */
    suspend fun putPosition(token: String, code: String, lat: Double, lon: Double, accuracyM: Double?,
                            manual: Boolean = false):
        ServerPosition = withContext(Dispatchers.IO) {
        val body = JSONObject().put("lat", lat).put("lon", lon).put("manual", manual)
        if (accuracyM != null) body.put("accuracy_m", accuracyM)
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
        return PmDetail(
            positionStatus = o.optString("position_status", "inconnue"),
            lat = pos?.optDouble("lat"), lon = pos?.optDouble("lon"),
            accuracyM = pos?.let { if (it.isNull("accuracy_m")) null else it.optDouble("accuracy_m") },
            author = pos?.let { optStr(it, "author") },
            updatedAt = pos?.let { optStr(it, "updated_at") },
            confirmations = o.optInt("confirmations", 0),
            confirmedByMe = o.optBoolean("confirmed_by_me", false),
            address = optStr(o, "address")
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
            op = optStr(o, "op"), userAdded = true
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
