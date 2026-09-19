package com.pmfibre.app

/**
 * Étiquettes rapides (roadmap 3.6).
 *
 * Deux familles, multi-sélection. Le libellé affiché vit ici, le slug vit des
 * deux côtés : l'application l'envoie, le serveur le valide. Cette liste doit
 * donc rester alignée sur `ETIQUETTES` dans `server/api/app/main.py` — c'est le
 * serveur qui fait autorité, une étiquette inconnue de lui est refusée en 422.
 *
 * Une étiquette reçue du serveur mais absente d'ici est conservée et affichée
 * telle quelle plutôt qu'ignorée : c'est ce qui arrivera à une application pas
 * encore mise à jour, et perdre silencieusement une information du terrain
 * serait pire que d'afficher un slug un peu brut.
 */
object Etiquettes {

    data class Etiquette(val slug: String, val libelle: String, val icone: String)

    /** Difficulté à trouver ou à atteindre le PM. */
    val acces = listOf(
        Etiquette("acces_haie", "Derrière une haie", "🌳"),
        Etiquette("acces_impasse", "Impasse / recoin", "↩️"),
        Etiquette("acces_arriere", "Accès par l'arrière", "🔄"),
        Etiquette("acces_portail", "Derrière un portail", "🚧"),
        Etiquette("acces_vegetation", "Végétation dense", "🌿"),
        Etiquette("acces_non_visible", "Non visible de la route", "🙈"),
    )

    /** Ce qu'on cherche des yeux en arrivant. */
    val site = listOf(
        Etiquette("site_shelter", "Shelter", "🏠"),
        Etiquette("site_armoire", "Armoire de rue", "🗄️"),
        Etiquette("site_local", "Local technique", "🏢"),
        Etiquette("site_autre", "Autre", "❔"),
    )

    val toutes: List<Etiquette> = acces + site

    private val parSlug: Map<String, Etiquette> = toutes.associateBy { it.slug }

    fun libelle(slug: String): String = parSlug[slug]?.libelle ?: slug
    fun icone(slug: String): String = parSlug[slug]?.icone ?: "🏷️"
    fun connue(slug: String): Boolean = parSlug.containsKey(slug)

    /**
     * Drapeau de synthèse affiché sur la carte et dans les listes (roadmap 3.6).
     *
     * Une seule étiquette le lève : « non visible de la route ». Les autres
     * disent ce qu'on trouvera sur place ; celle-ci dit qu'on ne trouvera rien
     * en roulant au pas, ce qui change la façon d'y aller.
     */
    fun difficile(tags: Collection<String>): Boolean = tags.contains("acces_non_visible")

    /** Ordre d'affichage stable : accès d'abord, puis type de site, puis le reste. */
    fun ordonne(tags: Collection<String>): List<String> {
        val rang = toutes.withIndex().associate { (i, e) -> e.slug to i }
        return tags.distinct().sortedWith(compareBy({ rang[it] ?: Int.MAX_VALUE }, { it }))
    }
}
