package com.pmfibre.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Envoie la localisation d'un PM à une application de cartographie pour lancer
 * l'itinéraire. On lance directement (try/catch) plutôt que de vérifier avant :
 * depuis Android 11, la vérification préalable échoue à cause des restrictions
 * de visibilité des applications.
 */
fun openItinerary(context: Context, lat: Double, lon: Double, label: String?) {
    val name = Uri.encode(label ?: "PM")

    val uris = listOf(
        // Navigation guidée (Google Maps, Waze…)
        Uri.parse("google.navigation:q=$lat,$lon"),
        // Affichage carte générique -> propose les applis installées
        Uri.parse("geo:$lat,$lon?q=$lat,$lon($name)"),
        // Dernier recours : lien web (ouvre Maps ou le navigateur)
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
    )

    for (uri in uris) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            return
        } catch (e: ActivityNotFoundException) {
            // aucune appli pour cette URI, on essaie la suivante
        }
    }
    Toast.makeText(
        context,
        "Aucune application de cartographie trouvée",
        Toast.LENGTH_LONG
    ).show()
}
