package com.pmfibre.app

import android.content.Context

/** Persistance simple du jeton de session et de l'identité (SharedPreferences). */
object SessionStore {
    private const val PREFS = "pmfibre_session"
    private const val K_TOKEN = "token"
    private const val K_USERNAME = "username"
    private const val K_ROLE = "role"

    @Volatile var token: String? = null
        private set
    @Volatile var username: String? = null
        private set
    @Volatile var role: String? = null
        private set

    val isLoggedIn: Boolean get() = !token.isNullOrEmpty()
    val isAdmin: Boolean get() = role == "admin"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        token = p.getString(K_TOKEN, null)
        username = p.getString(K_USERNAME, null)
        role = p.getString(K_ROLE, null)
    }

    fun save(context: Context, token: String, username: String, role: String) {
        this.token = token; this.username = username; this.role = role
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_TOKEN, token).putString(K_USERNAME, username).putString(K_ROLE, role)
            .apply()
    }

    /** Oublie le jeton mais garde le prénom (pré-rempli au prochain login forcé). */
    fun clearToken(context: Context) {
        token = null; role = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_TOKEN).remove(K_ROLE).apply()
    }

    fun clear(context: Context) {
        token = null; username = null; role = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
