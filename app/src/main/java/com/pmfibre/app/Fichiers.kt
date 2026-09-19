package com.pmfibre.app

import java.io.File
import java.io.FileOutputStream

/**
 * Écriture et lecture des petits fichiers d'état de l'application.
 *
 * `writeText` ouvre le fichier, le vide, puis écrit. Entre les deux, le fichier
 * existe et il est faux. Android tue les applications en arrière-plan quand il
 * veut, et la batterie s'arrête quand elle veut : il suffit d'être là au mauvais
 * moment pour garder un JSON coupé au milieu d'une ligne. Le magasin de
 * positions le relisait sans filet, l'exception remontait jusqu'à l'écran
 * d'accueil, et l'application ne démarrait plus — définitivement, puisque le
 * fichier restait cassé à chaque lancement. Seul un effacement des données la
 * récupérait, avec les relevés pas encore remontés.
 *
 * D'où le protocole d'ici : on écrit à côté, on force l'écriture sur le disque,
 * on décale l'ancien fichier en copie de secours, et on ne met le nom définitif
 * qu'à la fin. Une interruption laisse donc soit l'ancien fichier entier, soit
 * le nouveau entier, jamais un mélange des deux. À la lecture, un fichier
 * illisible est mis de côté au lieu d'être effacé, et on retombe sur la copie
 * précédente.
 */
object Fichiers {

    /** D'où vient l'état qu'on vient de lire. */
    enum class Etat {
        /** Aucun fichier : installation neuve, rien à récupérer. */
        ABSENT,

        /** Le fichier courant était bon. Cas normal. */
        COURANT,

        /** Le fichier courant était illisible, la copie de secours a servi. */
        SECOURS,

        /** Rien de lisible. L'appelant doit repartir de zéro. */
        PERDU,
    }

    /**
     * Écrit `contenu` sous `nom`, sans jamais laisser le fichier à moitié écrit.
     * Renvoie `false` si l'écriture a échoué — l'ancien contenu est alors resté
     * en place, ce qui vaut toujours mieux qu'un fichier tronqué.
     */
    fun ecrit(dir: File, nom: String, contenu: String): Boolean {
        val f = File(dir, nom)
        val tmp = File(dir, "$nom.tmp")
        val bak = File(dir, "$nom.bak")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(contenu.toByteArray(Charsets.UTF_8))
                out.flush()
                // Sans ce `sync`, le renommage peut être visible avant les
                // octets : on obtiendrait un fichier au bon nom et vide.
                out.fd.sync()
            }
            if (f.exists()) {
                bak.delete()
                f.renameTo(bak)
            }
            if (tmp.renameTo(f)) {
                true
            } else {
                // Le renommage a échoué : remettre l'ancien plutôt que rien.
                bak.renameTo(f)
                tmp.delete()
                false
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * Lit `nom`, puis sa copie de secours, en s'arrêtant au premier contenu que
     * `applique` accepte sans lever d'exception.
     *
     * ⚠️ `applique` peut être appelé deux fois, et peut échouer en cours de
     * route : il doit construire son résultat à part et ne publier l'état de
     * l'appelant qu'une fois la lecture entière réussie. Sans cela, un fichier
     * tronqué laisserait un état à moitié chargé, qui est exactement ce qu'on
     * cherche à éviter.
     */
    fun lit(dir: File, nom: String, applique: (String) -> Unit): Etat {
        val f = File(dir, nom)
        val bak = File(dir, "$nom.bak")
        if (!f.exists() && !bak.exists()) return Etat.ABSENT
        if (essaie(f, applique)) return Etat.COURANT
        // Mis de côté, pas supprimé : c'est peut-être la seule trace d'un relevé
        // de terrain, et un fichier tronqué reste partiellement lisible à la
        // main. Il porte un nom qui ne sera plus jamais relu automatiquement.
        if (f.exists()) f.renameTo(File(dir, "$nom.corrompu"))
        if (essaie(bak, applique)) return Etat.SECOURS
        return Etat.PERDU
    }

    private fun essaie(f: File, applique: (String) -> Unit): Boolean =
        if (!f.exists()) false
        else try {
            applique(f.readText())
            true
        } catch (_: Exception) {
            false
        }
}
