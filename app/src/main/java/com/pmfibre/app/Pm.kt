package com.pmfibre.app

/** Un Point de Mutualisation (PM) fibre, données ARCEP (ZAPM 2026 T1). */
data class Pm(
    val code: String?,      // référence ARCEP, ex. "FI-91477-000Y"
    val oi: String?,        // code opérateur d'infrastructure, ex. "FI"
    val com: String?,       // commune
    val dep: String?,       // département
    val etat: String?,      // état du PM, ex. "deploye"
    val date: String?,      // date de début
    val lgt: Int?,          // logements raccordables au PM
    val tot: Int?,          // logements total dans la zone du PM
    val lat: Double,        // latitude (WGS84)
    val lon: Double,        // longitude (WGS84)
    val precise: Boolean,   // true = point exact, false = centre de zone (estimé)
    val op: String? = null, // nom opérateur direct (PM ajoutés terrain, sans code OI)
    val userAdded: Boolean = false  // true = PM ajouté par un utilisateur (hors ARCEP)
)
