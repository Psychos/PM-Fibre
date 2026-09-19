package com.pmfibre.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Position exacte enregistrée (localement et/ou synchronisée depuis le serveur).
 *  `synced` = présente côté serveur (ne doit plus être re-poussée ; purgée si supprimée là-bas). */
data class SavedPos(
    val lat: Double, val lon: Double, val ts: Long, val note: String?,
    val author: String? = null, val accuracyM: Double? = null, val synced: Boolean = false,
    /** Saisie au clavier sur la carte, et non relevée par le GPS. Le serveur en
     *  fait un contrôle de zone strict (100 m au lieu de 500) — encore faut-il
     *  qu'il l'apprenne, et une capture faite hors ligne ne remonte que ce que
     *  ce fichier a retenu (§ F07). */
    val manual: Boolean = false,
    /** Comment la position a été obtenue : `gps_precis` pour un relevé moyenné
     *  sur trente secondes, `manuel`, ou null pour un fix unique. */
    val method: String? = null
)

/** Écrit une position enregistrée au format du fichier local.
 *
 *  La (dé)sérialisation est sortie du dépôt pour être éprouvable sans Android :
 *  c'est le format d'un fichier qui survit aux mises à jour de l'application, et
 *  un champ oublié d'un côté ne se voit pas à la lecture du code. */
fun SavedPos.enJson(): JSONObject = JSONObject().apply {
    put("lat", lat); put("lon", lon); put("ts", ts)
    if (note != null) put("note", note)
    if (author != null) put("author", author)
    if (accuracyM != null) put("acc", accuracyM)
    if (synced) put("s", 1)
    if (manual) put("m", 1)
    if (method != null) put("meth", method)
}

/** Relit une position enregistrée. Un fichier écrit par une version antérieure
 *  n'a ni `m` ni `meth` : la capture est alors tenue pour un relevé GPS, ce
 *  qu'elle était dans l'immense majorité des cas. */
fun savedPosDepuisJson(e: JSONObject): SavedPos = SavedPos(
    e.getDouble("lat"), e.getDouble("lon"),
    e.optLong("ts", 0L), e.optString("note", "").ifEmpty { null },
    e.optString("author", "").ifEmpty { null },
    if (e.has("acc")) e.getDouble("acc") else null,
    e.optInt("s", 0) == 1,
    e.optInt("m", 0) == 1,
    e.optString("meth", "").ifEmpty { null }
)

/** Vue d'un PM avec la position effective (enregistrée si elle existe, sinon approximative). */
data class PmView(
    val pm: Pm,
    val lat: Double,
    val lon: Double,
    val exact: Boolean,       // true = position enregistrée par l'utilisateur
    val savedTs: Long?,
    val note: String?,
    val author: String? = null,
    val accuracyM: Double? = null
)

/**
 * Contient tous les PM (données ARCEP intégrées) + les positions exactes
 * enregistrées par l'utilisateur, et fournit recherche et proximité.
 */
object PmRepository {

    @Volatile private var basePms: List<Pm> = emptyList()      // ARCEP embarqués
    @Volatile private var addedPms: List<Pm> = emptyList()     // ajoutés par des users
    // Fiches minimales des PM retirés du référentiel qui portent une
    // contribution de l'équipe (§ F12). Le paquet ne les contient plus.
    @Volatile private var retiredPms: List<Pm> = emptyList()
    @Volatile private var vivants: Set<String> = emptySet()    // codes encore au référentiel
    @Volatile private var pms: List<Pm> = emptyList()          // union (added prime)
    @Volatile private var byCode: Map<String, Pm> = emptyMap()
    @Volatile private var searchIndex: List<Triple<Pm, String, String>> = emptyList()  // pm, code normalisé, commune normalisée
    @Volatile private var cpMap: Map<String, List<String>> = emptyMap()  // code postal -> communes normalisées
    @Volatile private var oiNames: Map<String, String> = emptyMap()
    // Polygones ZAPM (zone de desserte ARCEP) par code PM : code -> anneaux -> [lat, lon]
    @Volatile private var zoneRings: Map<String, List<List<DoubleArray>>> = emptyMap()
    @Volatile private var zoneBBox: Map<String, DoubleArray> = emptyMap()  // code -> [minLat, maxLat, minLon, maxLon]
    @Volatile private var depCodes: List<String> = emptyList()  // départements installés
    private val saved = HashMap<String, SavedPos>()   // code PM -> position enregistrée

    /** Normalise pour la recherche : sans accents, majuscules, alphanumérique seul. */
    fun normalize(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (c in d) {
            when {
                c.isLetterOrDigit() && c.code < 128 -> sb.append(c.uppercaseChar())
                // les diacritiques (accents décomposés) et symboles sont ignorés
            }
        }
        return sb.toString()
    }

    val size: Int get() = pms.size
    val savedCount: Int get() = saved.size
    val addedCount: Int get() = addedPms.size

    fun isLoaded(): Boolean = pms.isNotEmpty()

    /** Périmètre installé, tel que le voit la synchro (`/sync/positions?dep=…`). */
    val departements: List<String> get() = depCodes

    /**
     * Charge les départements installés dans `filesDir/deps/` (roadmap 3.2).
     *
     * L'app démarre vide : aucun département installé = aucun PM, et c'est un
     * état normal, pas une erreur. Seul `oi_map.json` (3 Ko) reste dans l'APK.
     *
     * Un paquet illisible est ignoré plutôt que fatal : la donnée manque, mais
     * l'app ouvre — et l'écran Départements permet de le réinstaller.
     */
    fun load(context: Context) {
        val mapText = context.assets.open("oi_map.json").bufferedReader().use { it.readText() }
        oiNames = parseOiMap(mapText)
        DepStore.nettoie(context)

        val fiches = ArrayList<Pm>()
        val anneaux = HashMap<String, List<List<DoubleArray>>>()
        val codes = DepStore.installes(context)
        for (dep in codes) {
            val dossier = DepStore.dossier(context, dep)
            try {
                fiches.addAll(parse(File(dossier, "pm.json").readText(), dep))
            } catch (e: Exception) { continue }
            try {
                anneaux.putAll(parseZones(File(dossier, "zones.json").readText()))
            } catch (e: Exception) { /* zones absentes : le PM reste utilisable sans polygone */ }
        }
        basePms = fiches
        depCodes = codes
        zoneRings = anneaux
        zoneBBox = zoneRings.mapValues { (_, rings) -> boundingBox(rings) }

        addedPms = loadAddedPms(context)
        retiredPms = loadRetiredPms(context)
        cpMap = loadCpMap(context)
        rebuild()
        loadSaved(context)
        // Un PM revenu au référentiel n'a plus à être gardé à part : sa vraie
        // fiche est de retour et prime déjà dans l'index.
        val revenus = retiredPms.filter { it.code != null && it.code in vivants }
        if (revenus.isNotEmpty()) {
            retiredPms = retiredPms - revenus.toSet()
            persistRetiredPms(context)
        }
    }

    /** Relit les paquets après installation ou déchargement d'un département. */
    fun reload(context: Context) = load(context)

    private fun rebuild() {
        val map = LinkedHashMap<String, Pm>(basePms.size + addedPms.size + retiredPms.size)
        // Les retirés d'abord : si le référentiel en rend un, sa vraie fiche
        // écrase la fiche minimale, qui n'a plus lieu d'être.
        for (p in retiredPms) p.code?.let { map[it] = p }
        val encoreLa = HashSet<String>(basePms.size + addedPms.size)
        for (p in basePms) p.code?.let { map[it] = p; encoreLa.add(it) }
        for (p in addedPms) p.code?.let { map[it] = p; encoreLa.add(it) }   // un PM ajouté prime sur l'ARCEP
        vivants = encoreLa
        pms = map.values.toList()
        byCode = map
        searchIndex = pms.map { Triple(it, normalize(it.code ?: ""), normalize(it.com ?: "")) }
    }

    private fun loadCpMap(context: Context): Map<String, List<String>> = try {
        val o = JSONObject(context.assets.open("cp_normandie.json").bufferedReader().use { it.readText() })
        buildMap {
            for (cp in o.keys()) {
                val arr = o.getJSONArray(cp)
                put(cp, List(arr.length()) { arr.getString(it) })
            }
        }
    } catch (e: Exception) { emptyMap() }

    fun operatorName(pm: Pm): String {
        pm.op?.let { if (it.isNotBlank()) return it }
        val code = pm.oi ?: return "—"
        return oiNames[code] ?: code
    }

    // ---- PM ajoutés par les utilisateurs ----
    /** Remplace la liste des PM ajoutés (reçue du serveur) et la persiste. */
    fun mergeAddedPms(context: Context, list: List<Pm>) {
        addedPms = list
        persistAddedPms(context)
        rebuild()
    }

    /** Ajoute/actualise un PM localement (après création), sans attendre la synchro. */
    fun addLocalPm(context: Context, pm: Pm) {
        addedPms = addedPms.filterNot { it.code == pm.code } + pm
        persistAddedPms(context)
        rebuild()
    }

    private fun loadAddedPms(context: Context): List<Pm> {
        // Moins critique que les positions : ces PM sont tous connus du serveur,
        // et `fetchAddedPms` en redescend l'inventaire complet à chaque synchro.
        // Une perte se répare donc toute seule — il n'y a pas de curseur à
        // remettre à zéro ici.
        var lus: List<Pm> = emptyList()
        Fichiers.lit(context.filesDir, "added_pms.json") { texte -> lus = parse(texte) }
        return lus
    }

    private fun persistAddedPms(context: Context) {
        val arr = JSONArray()
        for (p in addedPms) arr.put(p.copy(userAdded = true).enJson())
        Fichiers.ecrit(context.filesDir, "added_pms.json", arr.toString())
    }

    // ---- PM retirés du référentiel (§ F12) ----

    /**
     * Garde une fiche minimale des PM que la mise à jour d'un paquet vient de
     * retirer, quand l'équipe y a laissé quelque chose.
     *
     * Appelée par `DepStore` au seul moment où l'on peut faire la différence
     * entre « retiré du référentiel » et « département non installé » : celui
     * où l'on tient encore l'ancien paquet et déjà le nouveau. Après coup, un
     * code absent ne dit plus lequel des deux.
     *
     * Sans cela, la position relevée sur place restait dans
     * `saved_positions.json` sans qu'aucune fiche ne permette d'y accéder : le
     * relevé existait, et personne ne pouvait plus le retrouver.
     */
    fun conserveRetires(context: Context, anciennes: List<Pm>) {
        val avecContribution = anciennes.mapNotNull { it.code }
            .filter { code ->
                saved.containsKey(code) || !MetaStore.meta(code).vide ||
                    PhotoStore.enAttentePour(context, code).isNotEmpty()
            }.toSet()
        val gardees = fichesRetireesAConserver(anciennes, avecContribution)
        if (gardees.isEmpty()) return
        val codes = gardees.mapNotNull { it.code }.toSet()
        retiredPms = retiredPms.filterNot { it.code in codes } + gardees
        persistRetiredPms(context)
        rebuild()
    }

    private fun loadRetiredPms(context: Context): List<Pm> {
        var lus: List<Pm> = emptyList()
        // Ce fichier-là n'a aucune copie ailleurs : le serveur ne sait pas
        // quelles fiches ce téléphone a gardées, et le paquet ne les contient
        // plus. Fichiers lui donne l'écriture atomique et la copie de secours.
        Fichiers.lit(context.filesDir, "retired_pms.json") { texte -> lus = parse(texte) }
        return lus
    }

    private fun persistRetiredPms(context: Context) {
        val arr = JSONArray()
        for (p in retiredPms) arr.put(p.copy(retire = true).enJson())
        Fichiers.ecrit(context.filesDir, "retired_pms.json", arr.toString())
    }

    // ---- Positions exactes enregistrées ----

    fun view(pm: Pm): PmView {
        val s = pm.code?.let { saved[it] }
        return if (s != null) PmView(pm, s.lat, s.lon, true, s.ts, s.note, s.author, s.accuracyM)
        else PmView(pm, pm.lat, pm.lon, false, null, null, null, null)
    }

    fun saveExact(context: Context, code: String, lat: Double, lon: Double, note: String?,
                  author: String? = null, accuracyM: Double? = null, synced: Boolean = false,
                  manual: Boolean = false, method: String? = null) {
        val prev = saved[code]
        // `manual` et `method` décrivent la capture qu'on enregistre à l'instant :
        // pas d'héritage de la précédente, contrairement à l'auteur et à la
        // précision, qu'on préfère garder plutôt que perdre.
        saved[code] = SavedPos(lat, lon, System.currentTimeMillis(), note?.ifBlank { null },
            author ?: prev?.author, accuracyM ?: prev?.accuracyM, synced, manual, method)
        persistSaved(context)
    }

    /** Bilan d'une fusion : ce qui a été appliqué, et ce qui a été protégé. */
    data class FusionResult(val appliquees: Int, val protegees: Int)

    /** Applique une synchro serveur : positions reçues, puis suppressions.
     *
     *  Les suppressions viennent désormais des pierres tombales (`deleted`) et
     *  non plus d'une absence dans la liste. L'absence ne prouvait rien : une
     *  réponse tronquée ressemblait trait pour trait à des suppressions en
     *  masse, et effaçait des positions relevées sur le terrain.
     *
     *  La purge par absence n'est conservée que pour un inventaire complet et
     *  national (`full` et périmètre non restreint), seul cas où « absent » veut
     *  encore dire « supprimé ».
     *
     *  **Une capture locale non acquittée n'est jamais remplacée.** La garde
     *  existait pour les suppressions, avec le raisonnement écrit juste en
     *  dessous, mais pas pour le remplacement : une position relevée sur le
     *  terrain dont l'envoi venait d'être refusé se faisait écraser par la
     *  version du serveur, et marquer `synced` — donc jamais retentée. Le relevé
     *  disparaissait sans un mot. C'est la perte la plus grave que puisse subir
     *  l'application : le technicien s'est déplacé, et le travail est perdu. */
    fun mergeServerPositions(context: Context, r: ApiClient.SyncResult): FusionResult {
        val serverCodes = HashSet<String>(r.positions.size)
        var appliquees = 0
        var protegees = 0
        for (p in r.positions) {
            serverCodes.add(p.code)
            val existing = saved[p.code]
            if (existing != null && !existing.synced) {
                // Le local prime tant qu'il n'est pas remonté. Il repartira au
                // prochain essai ; c'est au serveur d'arbitrer, pas à la
                // descente d'effacer ce qu'il n'a jamais reçu.
                protegees++
                continue
            }
            val ts = parseIsoMillis(p.updatedAt) ?: existing?.ts ?: System.currentTimeMillis()
            // La précision et le mode suivent la position : garder ceux de
            // l'ancienne entrée les ferait décrire un relevé qui n'est plus là.
            // `manual` retombe à false — la ligne est déjà côté serveur, elle
            // n'a plus de contrôle de zone à subir.
            saved[p.code] = SavedPos(p.lat, p.lon, ts, existing?.note,
                p.author ?: existing?.author, p.accuracyM ?: existing?.accuracyM,
                synced = true, manual = false, method = p.method)
            appliquees++
        }
        for (code in r.deleted) {
            // Une position locale non encore envoyée n'est pas concernée par une
            // suppression serveur : elle n'y a jamais été.
            if (saved[code]?.synced == true) saved.remove(code)
        }
        if (r.full && r.deps.isEmpty()) {
            val stale = saved.filter { it.value.synced && it.key !in serverCodes }.keys.toList()
            for (k in stale) saved.remove(k)
        }
        persistSaved(context)
        return FusionResult(appliquees, protegees)
    }

    /** Le serveur a refusé la capture en 409 : il a déjà une position à moins de
     *  10 m, la nôtre n'apporte rien. On accepte la sienne — c'est le seul refus
     *  qui ne perd aucune information, et le seul où acquitter est légitime. */
    fun marqueAcquittee(context: Context, code: String) {
        val s = saved[code] ?: return
        if (s.synced) return
        saved[code] = s.copy(synced = true)
        persistSaved(context)
    }

    /** Positions locales PAS ENCORE envoyées au serveur (captures hors-ligne, migration v3) :
     *  jamais celles déjà synchronisées (sinon on annulerait les suppressions admin). */
    fun locallyOwnedPositions(username: String): List<Pair<String, SavedPos>> =
        saved.entries
            .filter { !it.value.synced && (it.value.author == null || it.value.author == username) }
            .map { it.key to it.value }

    /** Recherche hors-ligne par code, commune (insensible accents/casse) ou code postal.
     *  Ex. : "evreux", "Évreux", "27000", "PMU-27" fonctionnent tous. */
    fun search(query: String, limit: Int = 60): List<PmView> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        val q = normalize(raw)
        if (q.isEmpty()) return emptyList()

        // Code postal (4-5 chiffres) -> communes correspondantes du référentiel
        val cpCommunes: Set<String> =
            if (raw.matches(Regex("\\d{4,5}"))) {
                cpMap.entries.asSequence()
                    .filter { it.key.startsWith(raw.padStart(if (raw.length == 4) 4 else 5, '0')) || it.key.startsWith(raw) }
                    .flatMap { it.value.asSequence() }
                    .toSet()
            } else emptySet()

        return searchIndex.asSequence()
            .filter { (_, code, com) ->
                code.contains(q) || com.contains(q) || (cpCommunes.isNotEmpty() && com in cpCommunes)
            }
            .take(limit)
            .map { view(it.first) }
            .toList()
    }

    /** PM proches SANS position exacte (à géolocaliser), triés par distance. */
    fun nearestToLocate(lat: Double, lon: Double, limit: Int = 25): List<PmDistance> =
        pms.asSequence()
            .map { view(it) }
            .filter { !it.exact }
            .map { PmDistance(it, haversine(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.meters }
            .take(limit)
            .toList()

    /** Convertit un horodatage ISO du serveur ("yyyy-MM-dd'T'HH:mm:ss", UTC) en millis. */
    private fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(iso.substringBefore('.'))?.time
        } catch (e: Exception) { null }
    }

    fun deleteExact(context: Context, code: String) {
        saved.remove(code)
        persistSaved(context)
    }

    private const val FICHIER_POSITIONS = "saved_positions.json"

    /**
     * Relit les positions enregistrées.
     *
     * Ce fichier est le seul endroit où vivent les relevés pas encore remontés :
     * il est lu à chaque démarrage, et il l'était sans filet. Un JSON tronqué —
     * application tuée pendant l'écriture, batterie vide — levait une exception
     * dans le `LaunchedEffect` de démarrage, et l'application ne s'ouvrait plus
     * du tout, à ce lancement comme à tous les suivants.
     *
     * Le parcours écrit dans une table à part et ne publie `saved` qu'une fois la
     * lecture entière réussie : `Fichiers.lit` peut rappeler ce bloc sur la copie
     * de secours, et un demi-chargement serait pire que pas de chargement.
     */
    private fun loadSaved(context: Context) {
        saved.clear()
        val etat = Fichiers.lit(context.filesDir, FICHIER_POSITIONS) { texte ->
            val lues = HashMap<String, SavedPos>()
            val o = JSONObject(texte)
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                lues[k] = savedPosDepuisJson(e)
            }
            saved.clear()
            saved.putAll(lues)
        }
        if (etat == Fichiers.Etat.SECOURS || etat == Fichiers.Etat.PERDU) {
            // L'état local n'est plus celui que le curseur de synchro décrit. Un
            // différentiel « depuis hier » ne rendrait que ce qui a bougé hier,
            // et tout ce qu'on vient de perdre resterait absent pour de bon.
            // On redemande l'inventaire complet au prochain appel.
            Sync.reinitialise(context)
        }
    }

    private fun persistSaved(context: Context) {
        Fichiers.ecrit(context.filesDir, FICHIER_POSITIONS, exportJson())
    }

    /** Exporte la base de positions enregistrées en JSON. */
    fun exportJson(): String {
        val o = JSONObject()
        for ((code, s) in saved) o.put(code, s.enJson())
        return o.toString(2)
    }

    /** Importe/fusionne une base de positions. Renvoie le nombre d'entrées importées. */
    fun importJson(context: Context, text: String): Int {
        val o = JSONObject(text)
        var n = 0
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            saved[k] = SavedPos(
                e.getDouble("lat"), e.getDouble("lon"),
                e.optLong("ts", System.currentTimeMillis()),
                e.optString("note", "").ifEmpty { null }
            )
            n++
        }
        persistSaved(context)
        return n
    }

    // ---- Chargement des données ----

    private fun parseOiMap(text: String): Map<String, String> {
        val o = JSONObject(text)
        val map = HashMap<String, String>(o.length())
        for (k in o.keys()) map[k] = o.getString(k)
        return map
    }

    /** `depCode` vient du dossier du paquet : pm.json ne le porte pas. */
    private fun parse(text: String, depCode: String? = null): List<Pm> {
        val arr = JSONArray(text)
        val list = ArrayList<Pm>(arr.length())
        for (i in 0 until arr.length()) list.add(pmDepuisJson(arr.getJSONObject(i), depCode))
        return list
    }

    // ---- Recherche / proximité (utilisent la position effective) ----

    /**
     * PM contenus dans un rectangle géographique (roadmap 4.8). La carte affichait
     * jusqu'ici les 120 plus proches du fix GPS, calculés une seule fois : faire
     * glisser la carte vers une autre commune ne rendait rien, alors que les fiches
     * étaient là. C'est un défaut d'affichage, pas un manque de données.
     *
     * Un simple balayage suffit : sur six départements (~6000 fiches) il dure une
     * fraction de milliseconde, et aucun index géographique n'est à maintenir à
     * chaque (dé)chargement de paquet. Le plafond borne le cas du dézoom, où le
     * rectangle couvrirait tout le périmètre installé.
     */
    fun inBoundingBox(sud: Double, nord: Double, ouest: Double, est: Double,
                      limit: Int = 3000): List<PmView> {
        val out = ArrayList<PmView>()
        for (pm in pms) {
            val v = view(pm)
            if (v.lat in sud..nord && v.lon in ouest..est) {
                out.add(v)
                if (out.size >= limit) break
            }
        }
        return out
    }

    fun nearest(lat: Double, lon: Double, limit: Int = 25): List<PmDistance> {
        return pms.asSequence()
            .map { view(it) }
            .map { PmDistance(it, haversine(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.meters }
            .take(limit)
            .toList()
    }

    // ---- Zone ARCEP (quel PM dessert ce point) ----

    private fun parseZones(text: String): Map<String, List<List<DoubleArray>>> = try {
        val o = JSONObject(text)
        buildMap {
            for (code in o.keys()) {
                val ringsArr = o.getJSONArray(code)
                val rings = List(ringsArr.length()) { ri ->
                    val ring = ringsArr.getJSONArray(ri)
                    List(ring.length()) { pi ->
                        val p = ring.getJSONArray(pi)
                        doubleArrayOf(p.getDouble(0), p.getDouble(1))
                    }
                }
                put(code, rings)
            }
        }
    } catch (e: Exception) { emptyMap() }

    private fun boundingBox(rings: List<List<DoubleArray>>): DoubleArray {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (ring in rings) for (p in ring) {
            if (p[0] < minLat) minLat = p[0]
            if (p[0] > maxLat) maxLat = p[0]
            if (p[1] < minLon) minLon = p[1]
            if (p[1] > maxLon) maxLon = p[1]
        }
        return doubleArrayOf(minLat, maxLat, minLon, maxLon)
    }

    /** Ray casting (x=lon, y=lat) — même algorithme que `geo.py` côté serveur. */
    private fun pointInRing(lat: Double, lon: Double, ring: List<DoubleArray>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i][0]; val xi = ring[i][1]
            val yj = ring[j][0]; val xj = ring[j][1]
            if ((yi > lat) != (yj > lat)) {
                val xCross = (xj - xi) * (lat - yi) / (yj - yi) + xi
                if (lon < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Distance min (m) du point aux segments de l'anneau (projection équirectangulaire, comme `geo.py`). */
    private fun distToRingM(lat: Double, lon: Double, ring: List<DoubleArray>): Double {
        val coslat = cos(Math.toRadians(lat))
        fun toXY(p: DoubleArray) =
            Math.toRadians(p[1]) * coslat * 6_371_000.0 to Math.toRadians(p[0]) * 6_371_000.0
        val (px, py) = toXY(doubleArrayOf(lat, lon))
        var best = Double.MAX_VALUE
        for (i in ring.indices) {
            val (ax, ay) = toXY(ring[i])
            val (bx, by) = toXY(ring[(i + 1) % ring.size])
            val dx = bx - ax; val dy = by - ay
            val seg2 = dx * dx + dy * dy
            val t = if (seg2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / seg2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            val d = hypot(px - cx, py - cy)
            if (d < best) best = d
        }
        return best
    }

    /** PM dont la zone ARCEP contient ce point (`insideZone=true`), ou à défaut le PM dont la
     *  zone est la plus proche à moins de `toleranceM` (marge GPS / simplification des polygones). */
    fun pmServing(lat: Double, lon: Double, toleranceM: Double = 150.0): ServingPm? {
        for ((code, rings) in zoneRings) {
            val bbox = zoneBBox[code] ?: continue
            if (lat < bbox[0] || lat > bbox[1] || lon < bbox[2] || lon > bbox[3]) continue
            if (rings.any { pointInRing(lat, lon, it) }) {
                val pm = byCode[code] ?: continue
                return ServingPm(pm, true, 0.0)
            }
        }

        val latMargin = toleranceM / 111_000.0
        val lonMargin = toleranceM / (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        var best: ServingPm? = null
        for ((code, rings) in zoneRings) {
            val bbox = zoneBBox[code] ?: continue
            if (lat < bbox[0] - latMargin || lat > bbox[1] + latMargin ||
                lon < bbox[2] - lonMargin || lon > bbox[3] + lonMargin
            ) continue
            val d = rings.minOf { distToRingM(lat, lon, it) }
            if (d <= toleranceM && (best == null || d < best!!.distanceM)) {
                val pm = byCode[code] ?: continue
                best = ServingPm(pm, false, d)
            }
        }
        return best
    }
}

// ---- Format d'une fiche gardée en local (PM ajoutés, PM retirés) ----
//
// Hors de l'objet, et donc sans `Context` : ces deux fonctions se relisent et
// s'éprouvent sur la JVM. Elles lisent aussi bien un `pm.json` de paquet qu'un
// fichier écrit par l'application — d'où les clés facultatives.

fun pmDepuisJson(o: JSONObject, depCode: String? = null): Pm = Pm(
    code = o.optString("code", "").ifEmpty { null },
    oi = o.optString("oi", "").ifEmpty { null },
    com = o.optString("com", "").ifEmpty { null },
    dep = o.optString("dep", "").ifEmpty { null },
    etat = o.optString("etat", "").ifEmpty { null },
    date = o.optString("date", "").ifEmpty { null },
    lgt = if (o.isNull("lgt")) null else o.optInt("lgt"),
    tot = if (o.isNull("tot")) null else o.optInt("tot"),
    lat = o.getDouble("lat"),
    lon = o.getDouble("lon"),
    precise = o.optInt("p", 0) == 1,
    op = o.optString("op", "").ifEmpty { null },
    userAdded = o.optInt("user", 0) == 1,
    depCode = depCode ?: o.optString("dep_code", "").ifEmpty { null },
    retire = o.optInt("retire", 0) == 1
)

fun Pm.enJson(): JSONObject = JSONObject().apply {
    put("code", code); put("com", com); put("dep", dep); put("etat", etat)
    put("date", date); put("lat", lat); put("lon", lon)
    if (lgt != null) put("lgt", lgt)
    if (tot != null) put("tot", tot)
    if (oi != null) put("oi", oi)
    if (op != null) put("op", op)
    if (depCode != null) put("dep_code", depCode)
    if (precise) put("p", 1)
    if (userAdded) put("user", 1)
    if (retire) put("retire", 1)
}

/**
 * Parmi les fiches que le référentiel vient de retirer, celles qu'on garde.
 *
 * Une fiche retirée qui ne porte rien n'intéresse personne : le PM n'existe
 * plus, la garder encombrerait la recherche et la carte. Une fiche qui porte
 * une position relevée sur place, une étiquette ou un accès est un travail de
 * terrain — et le fichier des positions le gardait déjà, sans que la fiche
 * correspondante soit encore trouvable (§ F12).
 */
fun fichesRetireesAConserver(anciennes: List<Pm>, avecContribution: Set<String>): List<Pm> =
    anciennes.filter { it.code != null && it.code in avecContribution }
        .map { it.copy(retire = true) }

/** Résultat de `PmRepository.pmServing` : le PM dont la zone ARCEP couvre (ou avoisine) le point. */
data class ServingPm(val pm: Pm, val insideZone: Boolean, val distanceM: Double)

data class PmDistance(val view: PmView, val meters: Double)

/** Distance en mètres entre deux points GPS (formule de Haversine). */
fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
