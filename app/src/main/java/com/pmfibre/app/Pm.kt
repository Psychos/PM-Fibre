package com.pmfibre.app

/** Un Point de Mutualisation (PM) fibre, données ARCEP (ZAPM 2026 T2). */
data class Pm(
    val code: String?,      // référence ARCEP, ex. "FI-91477-000Y"
    val oi: String?,        // code opérateur d'infrastructure, ex. "FI"
    val com: String?,       // commune
    val dep: String?,       // département (nom, tel que livré par l'ARCEP)
    val etat: String?,      // état du PM, ex. "deploye"
    val date: String?,      // date de début
    val lgt: Int?,          // logements raccordables au PM
    val tot: Int?,          // logements total dans la zone du PM
    val lat: Double,        // latitude (WGS84)
    val lon: Double,        // longitude (WGS84)
    val precise: Boolean,   // true = point exact, false = centre de zone (estimé)
    val op: String? = null, // nom opérateur direct (PM ajoutés terrain, sans code OI)
    val userAdded: Boolean = false, // true = PM ajouté par un utilisateur (hors ARCEP)
    // Code du département, déduit du paquet dont vient la fiche (« 14 », « 2A »,
    // « 971 ») : il n'est pas dans pm.json, c'est le dossier qui le porte. Sert
    // à borner la synchro au périmètre installé.
    val depCode: String? = null
)
