package com.pmfibre.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Données ARCEP hors de l'APK (roadmap 3.2).
 *
 * Un paquet par département — `deps/<code>.tgz` contenant `pm.json` et
 * `zones.json` — téléchargé dans `filesDir/deps/<code>/`. L'APK ne contient plus
 * que `oi_map.json` et démarre vide : 103 départements font 8 Mo de données dont
 * un technicien n'utilise que deux ou trois.
 *
 * Trois précautions, dans cet ordre :
 *
 * 1. **sha256 avant extraction.** L'archive est vérifiée entière avant qu'un
 *    seul octet n'en sorte. Un téléchargement tronqué (4G qui coupe) donnerait
 *    sinon un `pm.json` mutilé, et `PmRepository.parse` plante sur `getDouble`.
 * 2. **Écriture atomique.** L'extraction va dans `<code>.tmp/`, renommé en
 *    `<code>/` une fois complète. Un dossier `<code>/` existe donc, ou n'existe
 *    pas ; il n'est jamais à moitié écrit. `nettoie()` ramasse les `.tmp` laissés
 *    par une interruption.
 * 3. **Deux membres, pas un de plus.** Le lecteur tar n'accepte que `pm.json` et
 *    `zones.json` et ignore le reste : aucun nom d'entrée ne peut désigner un
 *    chemin, donc pas de traversée de répertoire — y compris si l'archive venait
 *    à être servie par autre chose que notre nginx.
 *
 * Ce qui n'est **pas** fait : WorkManager (reprise en tâche de fond, option
 * « Wi-Fi seulement ») annoncé en 3.2. Un paquet pèse ~80 Ko, six font 500 Ko :
 * une coroutine suffit, et l'atomicité comme l'intégrité — la vraie raison d'être
 * de WorkManager ici — sont assurées ci-dessus. À reprendre le jour où l'on
 * distribuera des données autrement plus lourdes.
 */
object DepStore {

    /** Diffusion statique par le nginx du site (roadmap 3.2), pas par l'API : les
     *  données ARCEP sont sous licence ouverte, aucune authentification requise.
     *  Pour un essai en LAN : "http://192.168.1.98:8081/data". */
    var baseUrl: String = "https://mapm.online/data"

    private const val PREFS = "pmfibre_deps"
    private const val K_ETAG = "manifest_etag"
    private const val K_DERNIERE_VERIF = "derniere_verif"
    private const val K_IGNORE = "dataset_ignore"

    private const val DELAI_VERIF_MS = 24L * 60 * 60 * 1000   // roadmap 3.4
    private const val TIMEOUT_VERIF_MS = 3_000                // vérification de lancement
    private const val TIMEOUT_TELECHARGE_MS = 30_000          // action explicite
    private const val TAILLE_MAX_MEMBRE = 64L * 1024 * 1024
    private val MEMBRES = setOf("pm.json", "zones.json")

    data class DepInfo(
        val code: String, val nom: String, val region: String,
        val pm: Int, val exact: Int, val zones: Int,
        val size: Long, val sha256: String, val url: String
    )

    data class Manifeste(
        val dataset: String, val generated: String,
        val maxDeps: Int, val minAppVersion: Int, val deps: List<DepInfo>
    )

    /** Ce qu'a changé la réinstallation d'un département déjà présent (roadmap 3.4). */
    data class Diff(val ajoutes: Int, val retires: Int)

    /**
     * Ce qu'on sait d'un paquet réellement présent sur le téléphone.
     *
     * L'application lisait le millésime dans le manifeste téléchargé — or ce
     * fichier est remplacé dès qu'on va voir s'il y a du neuf, bien avant
     * qu'aucun département n'ait bougé. Elle annonçait donc « à jour » en
     * comparant le manifeste à lui-même (§ F10). Le millésime est désormais
     * écrit dans le paquet installé, au moment de l'installation : il ne peut
     * plus se désaccorder du contenu, il part avec lui au déchargement, et une
     * installation interrompue n'en laisse aucun.
     *
     * `dataset` et `sha256` sont nuls pour un paquet posé par une version
     * antérieure, qui ne notait rien : on retombe alors sur le nombre de PM.
     */
    data class PaquetInstalle(
        val code: String, val dataset: String?, val sha256: String?,
        val pm: Int, val installeLe: Long
    )

    /** Où en est un département vis-à-vis du manifeste. */
    enum class EtatDep {
        /** Pas installé sur ce téléphone. */
        ABSENT,

        /** Installé, et c'est bien le paquet que le manifeste décrit. */
        A_JOUR,

        /** Installé, et le manifeste en décrit un autre. */
        A_METTRE_A_JOUR,

        /** Installé par une version qui ne notait pas le millésime, sans écart
         *  visible. On ne peut ni l'affirmer à jour ni le dire périmé. */
        INCONNU,
    }

    class DepException(message: String) : Exception(message)

    // ---- Emplacements ----
    fun racine(context: Context): File = File(context.filesDir, "deps")

    fun dossier(context: Context, code: String): File = File(racine(context), code)

    /** Départements réellement exploitables : un dossier sans `pm.json` ne compte pas. */
    fun installes(context: Context): List<String> =
        (racine(context).listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, "pm.json").isFile }
            .map { it.name }
            .sorted()

    /** Supprime les extractions interrompues. À appeler au démarrage. */
    fun nettoie(context: Context) {
        for (f in racine(context).listFiles() ?: emptyArray()) {
            if (f.name.endsWith(".tmp") || f.name.endsWith(".part")) f.deleteRecursively()
        }
    }

    // ---- Préférences ----
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun verificationDue(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(K_DERNIERE_VERIF, 0L) >= DELAI_VERIF_MS

    fun marqueVerifiee(context: Context) {
        prefs(context).edit().putLong(K_DERNIERE_VERIF, System.currentTimeMillis()).apply()
    }

    /** Horodatage de la dernière vérification, ou 0 si elle n'a jamais eu lieu. */
    fun derniereVerification(context: Context): Long =
        prefs(context).getLong(K_DERNIERE_VERIF, 0L)

    /** « Ignorer cette version » (roadmap 3.4) : plus de bandeau pour ce millésime. */
    fun ignore(context: Context, dataset: String) {
        prefs(context).edit().putString(K_IGNORE, dataset).apply()
    }

    fun estIgnore(context: Context, dataset: String): Boolean =
        prefs(context).getString(K_IGNORE, null) == dataset

    fun reseauDisponible(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val reseau = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(reseau) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Réseau utilisable pour une vérification automatique. Si l'utilisateur a
     * demandé « Wi-Fi seulement », la 4G ne compte pas : certains forfaits de
     * terrain sont comptés au mégaoctet, et une vérification n'est jamais urgente.
     * Une action lancée à la main, elle, ignore ce réglage.
     */
    fun reseauAutorise(context: Context): Boolean {
        if (!reseauDisponible(context)) return false
        if (!Settings.majWifiSeulement) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    @Suppress("DEPRECATION")
    fun versionApp(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    // ---- Manifeste ----
    private fun fichierManifeste(context: Context) = File(racine(context), "manifest.json")

    fun manifesteLocal(context: Context): Manifeste? {
        val f = fichierManifeste(context)
        if (!f.isFile) return null
        return try { parseManifeste(f.readText()) } catch (e: Exception) { null }
    }

    private fun parseManifeste(texte: String): Manifeste {
        val o = JSONObject(texte)
        val arr = o.getJSONArray("deps")
        val deps = ArrayList<DepInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val code = d.getString("code")
            deps.add(
                DepInfo(
                    code = code,
                    nom = d.optString("nom", code),
                    region = d.optString("region", "Autres"),
                    pm = d.optInt("pm", 0),
                    exact = d.optInt("exact", 0),
                    zones = d.optInt("zones", 0),
                    size = d.optLong("size", 0L),
                    sha256 = d.optString("sha256", ""),
                    url = d.optString("url", "deps/$code.tgz")
                )
            )
        }
        return Manifeste(
            dataset = o.optString("dataset", "?"),
            generated = o.optString("generated", ""),
            // Le plafond vient du manifeste, jamais d'une constante de l'app
            // (roadmap 3.1) : il doit pouvoir bouger sans republier l'APK.
            maxDeps = o.optInt("max_deps", 6),
            minAppVersion = o.optInt("min_app_version", 0),
            deps = deps
        )
    }

    /**
     * Récupère le manifeste. `null` = rien de neuf (304).
     *
     * `force` ignore l'ETag : c'est le cas de l'écran Départements ouvert à la
     * main, où l'on veut la liste même si le manifeste n'a pas bougé.
     */
    suspend fun recupereManifeste(context: Context, force: Boolean = false): Manifeste? =
        withContext(Dispatchers.IO) {
            val etag = if (force) null else prefs(context).getString(K_ETAG, null)
            val timeout = if (force) TIMEOUT_TELECHARGE_MS else TIMEOUT_VERIF_MS
            val conn = (URL("$baseUrl/manifest.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeout
                readTimeout = timeout
                if (etag != null) setRequestProperty("If-None-Match", etag)
            }
            try {
                // 304 : le serveur dit que le manifeste en cache est le bon. La
                // vérification a bien eu lieu — le retour anticipé sautait la
                // date et on revérifiait à chaque lancement (§ F10) — et
                // l'appelant reçoit le manifeste qu'il demandait.
                if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    marqueVerifiee(context)
                    return@withContext manifesteLocal(context)
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw DepException("Manifeste indisponible (HTTP ${conn.responseCode}).")
                }
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                val m = parseManifeste(texte)   // parser d'abord : on ne met en cache que du lisible
                racine(context).mkdirs()
                fichierManifeste(context).writeText(texte)
                conn.getHeaderField("ETag")?.let {
                    prefs(context).edit().putString(K_ETAG, it).apply()
                }
                marqueVerifiee(context)
                m
            } finally {
                conn.disconnect()
            }
        }

    // ---- Ce qui est installé, département par département ----

    /**
     * Ce qui est installé pour ce département, ou null s'il ne l'est pas.
     *
     * Sans fiche d'installation — paquet posé par une version antérieure — on
     * compte les PM du fichier : c'est la seule chose comparable au manifeste
     * qui soit déjà sur le disque. Un écart prouve que le paquet est périmé ;
     * une égalité ne prouve rien, d'où [EtatDep.INCONNU].
     */
    fun paquetInstalle(context: Context, code: String): PaquetInstalle? {
        val dir = dossier(context, code)
        val pmJson = File(dir, "pm.json")
        if (!pmJson.isFile) return null
        val fiche = File(dir, "paquet.json")
        if (fiche.isFile) {
            try {
                val o = JSONObject(fiche.readText())
                return PaquetInstalle(
                    code = code,
                    dataset = o.optString("dataset", "").ifEmpty { null },
                    sha256 = o.optString("sha256", "").ifEmpty { null },
                    pm = o.optInt("pm", 0),
                    installeLe = o.optLong("installe_le", 0L)
                )
            } catch (e: Exception) {
                // Fiche illisible : on se rabat sur le comptage, comme pour un
                // paquet ancien. Le contenu, lui, est intact.
            }
        }
        return PaquetInstalle(code, null, null, compteP(pmJson), pmJson.lastModified())
    }

    fun paquetsInstalles(context: Context): List<PaquetInstalle> =
        installes(context).mapNotNull { paquetInstalle(context, it) }

    /** Millésime des données réellement présentes, pour l'écran Paramètres. */
    fun millesimeInstalle(context: Context): String? = millesimeDe(paquetsInstalles(context))

    fun etatsDeps(context: Context, distant: Manifeste): Map<String, EtatDep> {
        val parCode = distant.deps.associateBy { it.code }
        return installes(context).associateWith {
            etatDep(paquetInstalle(context, it), parCode[it])
        }
    }

    /** Les départements installés dont le manifeste décrit un autre paquet. */
    fun aMettreAJour(context: Context, distant: Manifeste): List<String> =
        etatsDeps(context, distant).filterValues { it == EtatDep.A_METTRE_A_JOUR }.keys.sorted()

    /**
     * Vrai s'il y a lieu d'afficher le bandeau.
     *
     * La condition porte sur les paquets, plus sur le millésime du manifeste :
     * ce qui compte est qu'un département installé soit périmé, pas qu'un
     * fichier téléchargé porte une autre date. Un paquet de millésime inconnu
     * ne l'allume pas — le bandeau annonce des données nouvelles, on ne va pas
     * l'affirmer sans le savoir ; l'écran Départements, lui, le signale.
     */
    fun miseAJourDisponible(context: Context, distant: Manifeste): Boolean =
        !estIgnore(context, distant.dataset) && aMettreAJour(context, distant).isNotEmpty()

    /** Nombre d'entrées d'un `pm.json`, sans construire les fiches. */
    private fun compteP(f: File): Int = try {
        JSONArray(f.readText()).length()
    } catch (e: Exception) { 0 }

    // ---- Installation ----
    /**
     * Télécharge et installe un département. Renvoie ce qui a changé quand il
     * était déjà présent (roadmap 3.4 : « 12 PM ajoutés, 3 retirés »).
     *
     * Les positions, PM ajoutés, commentaires et étiquettes ne sont pas touchés :
     * ils vivent ailleurs et sont indexés par code PM (roadmap 3.3).
     */
    suspend fun installe(context: Context, manifeste: Manifeste, info: DepInfo): Diff =
      withContext(Dispatchers.IO) {
        val racine = racine(context)
        racine.mkdirs()
        val cible = File(racine, info.code)
        val temp = File(racine, info.code + ".tmp")
        val archive = File(racine, info.code + ".part")
        temp.deleteRecursively()
        archive.delete()

        try {
            val somme = telecharge(URL("$baseUrl/${info.url}"), archive)
            if (info.sha256.isNotEmpty() && !somme.equals(info.sha256, ignoreCase = true)) {
                throw DepException("Paquet ${info.code} corrompu (empreinte différente) — réessaie.")
            }
            val membres = lireTarGz(archive)
            val pm = membres["pm.json"]
                ?: throw DepException("Paquet ${info.code} incomplet (pm.json absent).")
            val zones = membres["zones.json"]

            // L'ancien paquet est lu en entier, pas seulement ses codes : c'est
            // le seul instant où l'on peut garder la fiche d'un PM que le
            // référentiel retire (§ F12). Dans une minute, le fichier n'existe
            // plus et un code absent ne dira plus s'il a été retiré ou si le
            // département n'est pas installé.
            val ancien = File(cible, "pm.json").let { if (it.isFile) it.readText() else null }
            val avant = codesDe(ancien)
            temp.mkdirs()
            File(temp, "pm.json").writeBytes(pm)
            File(temp, "zones.json").writeBytes(zones ?: "{}".toByteArray())
            // La fiche d'installation entre dans le dossier temporaire, donc
            // elle arrive avec le paquet ou pas du tout : le millésime noté ne
            // peut pas décrire un contenu qui n'a pas été posé (§ F10).
            File(temp, "paquet.json").writeText(
                ficheInstallation(manifeste.dataset, info, System.currentTimeMillis()))

            cible.deleteRecursively()
            if (!temp.renameTo(cible)) throw DepException("Installation de ${info.code} impossible.")

            val apres = codesDe(File(cible, "pm.json").let { if (it.isFile) it.readText() else null })
            val retires = if (avant.isEmpty()) emptySet() else avant.filterTo(HashSet()) { it !in apres }
            if (ancien != null && retires.isNotEmpty()) {
                PmRepository.conserveRetires(context, fichesDe(ancien, retires, info.code))
            }
            Diff(
                ajoutes = if (avant.isEmpty()) 0 else apres.count { it !in avant },
                retires = retires.size
            )
        } finally {
            archive.delete()
            temp.deleteRecursively()
        }
    }

    /** Retire les données ARCEP d'un département — et rien d'autre (roadmap 3.3). */
    fun decharge(context: Context, code: String): Boolean =
        dossier(context, code).deleteRecursively()

    private fun codesDe(texte: String?): Set<String> {
        if (texte == null) return emptySet()
        return try {
            val arr = JSONArray(texte)
            val s = HashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i).optString("code", "")
                if (c.isNotEmpty()) s.add(c)
            }
            s
        } catch (e: Exception) { emptySet() }
    }

    /** Les fiches d'un `pm.json` dont le code est dans `codes` (§ F12). */
    private fun fichesDe(texte: String, codes: Set<String>, dep: String): List<Pm> = try {
        val arr = JSONArray(texte)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("code", "") !in codes) continue
                // Une fiche illisible ne doit pas faire échouer l'installation :
                // au pire, ce PM-là n'est pas gardé.
                try { add(pmDepuisJson(o, dep)) } catch (e: Exception) { /* passée */ }
            }
        }
    } catch (e: Exception) { emptyList() }

    /** Écrit la réponse dans `dest` et renvoie son sha256 en hexadécimal. */
    private fun telecharge(url: URL, dest: File): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_TELECHARGE_MS
            readTimeout = TIMEOUT_TELECHARGE_MS
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw DepException("Téléchargement refusé (HTTP ${conn.responseCode}).")
            }
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { ins ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    // ---- Lecture tar.gz ----
    /**
     * Lecteur tar minimal, limité aux membres de [MEMBRES].
     *
     * Pas de commons-compress pour deux fichiers dans une archive qu'on produit
     * soi-même (`data/build_packages.py`). Le format est lu tel quel : en-têtes de
     * 512 octets, nom en 0..99, taille octale en 124..135, type en 156, données
     * alignées sur 512.
     */
    internal fun lireTarGz(archive: File): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            val entete = ByteArray(512)
            while (true) {
                if (!litExactement(gz, entete, 512)) break
                if (entete.all { it.toInt() == 0 }) break          // fin d'archive
                val nom = chaine(entete, 0, 100)
                if (nom.isEmpty()) break
                val taille = octal(entete, 124, 12)
                if (taille < 0 || taille > TAILLE_MAX_MEMBRE) {
                    throw DepException("Archive invalide (membre de $taille octets).")
                }
                val type = entete[156].toInt()
                val regulier = type == '0'.code || type == 0
                val bloc = ((taille + 511) / 512) * 512
                if (regulier && nom in MEMBRES) {
                    val data = ByteArray(taille.toInt())
                    if (!litExactement(gz, data, data.size)) {
                        throw DepException("Archive tronquée ($nom).")
                    }
                    out[nom] = data
                    saute(gz, bloc - taille)
                } else {
                    saute(gz, bloc)                                 // tout le reste est ignoré
                }
            }
        }
        return out
    }

    private fun litExactement(ins: InputStream, buf: ByteArray, n: Int): Boolean {
        var lus = 0
        while (lus < n) {
            val r = ins.read(buf, lus, n - lus)
            if (r <= 0) return false
            lus += r
        }
        return true
    }

    private fun saute(ins: InputStream, n: Long) {
        var reste = n
        val poubelle = ByteArray(8192)
        while (reste > 0) {
            val r = ins.read(poubelle, 0, minOf(reste, poubelle.size.toLong()).toInt())
            if (r <= 0) return
            reste -= r
        }
    }

    private fun chaine(buf: ByteArray, offset: Int, len: Int): String {
        var fin = offset
        while (fin < offset + len && buf[fin].toInt() != 0) fin++
        return String(buf, offset, fin - offset, Charsets.US_ASCII)
    }

    private fun octal(buf: ByteArray, offset: Int, len: Int): Long {
        var v = 0L
        for (i in offset until offset + len) {
            val c = buf[i].toInt().toChar()
            if (c in '0'..'7') v = v * 8 + (c - '0') else if (v > 0) break
        }
        return v
    }
}

// ---- Ce qu'un paquet installé sait de lui-même (§ F10) ----
//
// Hors de l'objet, et donc sans `Context` : la comparaison au manifeste est la
// règle qui décide ce qu'on affiche à l'utilisateur, elle s'éprouve sur la JVM.

fun ficheInstallation(dataset: String, info: DepStore.DepInfo, quand: Long): String =
    JSONObject()
        .put("dataset", dataset).put("sha256", info.sha256)
        .put("pm", info.pm).put("installe_le", quand)
        .toString()

/**
 * Compare ce qui est posé à ce que le manifeste décrit.
 *
 * L'empreinte du paquet tranche : elle porte sur l'archive entière, deux
 * millésimes qui ne changent rien à un département donnent la même et il n'y a
 * alors rien à retélécharger. À défaut — paquet d'avant cette version — le
 * nombre de PM sert de témoin : un écart prouve que le paquet est périmé, une
 * égalité ne prouve rien.
 */
fun etatDep(installe: DepStore.PaquetInstalle?, info: DepStore.DepInfo?): DepStore.EtatDep = when {
    installe == null -> DepStore.EtatDep.ABSENT
    // Le département n'est plus au manifeste : rien à quoi le comparer, et
    // surtout rien à proposer de télécharger.
    info == null -> DepStore.EtatDep.INCONNU
    !installe.sha256.isNullOrEmpty() && info.sha256.isNotEmpty() ->
        if (installe.sha256.equals(info.sha256, ignoreCase = true)) DepStore.EtatDep.A_JOUR
        else DepStore.EtatDep.A_METTRE_A_JOUR
    installe.pm > 0 && info.pm > 0 && installe.pm != info.pm -> DepStore.EtatDep.A_METTRE_A_JOUR
    else -> DepStore.EtatDep.INCONNU
}

/**
 * Le millésime des données présentes, tel qu'on l'affiche.
 *
 * Plusieurs départements peuvent venir de millésimes différents — on n'installe
 * pas tout le même jour — et le dire vaut mieux que d'en élire un.
 */
fun millesimeDe(paquets: List<DepStore.PaquetInstalle>): String? {
    if (paquets.isEmpty()) return null
    val connus = paquets.mapNotNull { it.dataset }.distinct().sorted()
    val inconnus = paquets.count { it.dataset == null }
    return when {
        connus.isEmpty() -> "inconnu"
        inconnus > 0 -> connus.joinToString(", ") + " (+ $inconnus inconnu(s))"
        else -> connus.joinToString(", ")
    }
}
