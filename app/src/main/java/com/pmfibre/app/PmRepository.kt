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
    val author: String? = null, val accuracyM: Double? = null, val synced: Boolean = false
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
    @Volatile private var pms: List<Pm> = emptyList()          // union (added prime)
    @Volatile private var byCode: Map<String, Pm> = emptyMap()
    @Volatile private var searchIndex: List<Triple<Pm, String, String>> = emptyList()  // pm, code normalisé, commune normalisée
    @Volatile private var cpMap: Map<String, List<String>> = emptyMap()  // code postal -> communes normalisées
    @Volatile private var oiNames: Map<String, String> = emptyMap()
    // Polygones ZAPM (zone de desserte ARCEP) par code PM : code -> anneaux -> [lat, lon]
    @Volatile private var zoneRings: Map<String, List<List<DoubleArray>>> = emptyMap()
    @Volatile private var zoneBBox: Map<String, DoubleArray> = emptyMap()  // code -> [minLat, maxLat, minLon, maxLon]
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

    fun load(context: Context) {
        val mapText = context.assets.open("oi_map.json").bufferedReader().use { it.readText() }
        oiNames = parseOiMap(mapText)
        val text = context.assets.open("pm_full.json").bufferedReader().use { it.readText() }
        basePms = parse(text)
        addedPms = loadAddedPms(context)
        cpMap = loadCpMap(context)
        zoneRings = loadZones(context)
        zoneBBox = zoneRings.mapValues { (_, rings) -> boundingBox(rings) }
        rebuild()
        loadSaved(context)
    }

    private fun rebuild() {
        val map = LinkedHashMap<String, Pm>(basePms.size + addedPms.size)
        for (p in basePms) p.code?.let { map[it] = p }
        for (p in addedPms) p.code?.let { map[it] = p }   // un PM ajouté prime sur l'ARCEP
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
        val f = File(context.filesDir, "added_pms.json")
        if (!f.exists()) return emptyList()
        return try { parse(f.readText()) } catch (e: Exception) { emptyList() }
    }

    private fun persistAddedPms(context: Context) {
        val arr = JSONArray()
        for (p in addedPms) arr.put(JSONObject().apply {
            put("code", p.code); put("com", p.com); put("dep", p.dep); put("etat", p.etat)
            put("date", p.date); put("lat", p.lat); put("lon", p.lon)
            if (p.lgt != null) put("lgt", p.lgt)
            if (p.tot != null) put("tot", p.tot)
            if (p.oi != null) put("oi", p.oi)
            if (p.op != null) put("op", p.op)
            put("user", 1)
        })
        File(context.filesDir, "added_pms.json").writeText(arr.toString())
    }

    // ---- Positions exactes enregistrées ----

    fun view(pm: Pm): PmView {
        val s = pm.code?.let { saved[it] }
        return if (s != null) PmView(pm, s.lat, s.lon, true, s.ts, s.note, s.author, s.accuracyM)
        else PmView(pm, pm.lat, pm.lon, false, null, null, null, null)
    }

    fun saveExact(context: Context, code: String, lat: Double, lon: Double, note: String?,
                  author: String? = null, accuracyM: Double? = null, synced: Boolean = false) {
        val prev = saved[code]
        saved[code] = SavedPos(lat, lon, System.currentTimeMillis(), note?.ifBlank { null },
            author ?: prev?.author, accuracyM ?: prev?.accuracyM, synced)
        persistSaved(context)
    }

    /** Fusionne les positions exactes reçues du serveur (partage entre collègues).
     *  Marque tout `synced`, et PURGE les positions synced disparues du serveur
     *  (supprimées par un admin) pour qu'elles ne reviennent pas. */
    fun mergeServerPositions(context: Context, list: List<ApiClient.ServerPmPosition>): Int {
        val serverCodes = HashSet<String>(list.size)
        for (p in list) {
            serverCodes.add(p.code)
            val existing = saved[p.code]
            val ts = parseIsoMillis(p.updatedAt) ?: existing?.ts ?: System.currentTimeMillis()
            saved[p.code] = SavedPos(p.lat, p.lon, ts, existing?.note,
                p.author ?: existing?.author, existing?.accuracyM, synced = true)
        }
        // Supprimées côté serveur : on retire les entrées synced absentes de la liste.
        val stale = saved.filter { it.value.synced && it.key !in serverCodes }.keys.toList()
        for (k in stale) saved.remove(k)
        persistSaved(context)
        return list.size
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

    private fun loadSaved(context: Context) {
        saved.clear()
        val f = File(context.filesDir, "saved_positions.json")
        if (!f.exists()) return
        val o = JSONObject(f.readText())
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            saved[k] = SavedPos(
                e.getDouble("lat"), e.getDouble("lon"),
                e.optLong("ts", 0L), e.optString("note", "").ifEmpty { null },
                e.optString("author", "").ifEmpty { null },
                if (e.has("acc")) e.getDouble("acc") else null,
                e.optInt("s", 0) == 1
            )
        }
    }

    private fun persistSaved(context: Context) {
        File(context.filesDir, "saved_positions.json").writeText(exportJson())
    }

    /** Exporte la base de positions enregistrées en JSON. */
    fun exportJson(): String {
        val o = JSONObject()
        for ((code, s) in saved) {
            o.put(code, JSONObject().apply {
                put("lat", s.lat); put("lon", s.lon); put("ts", s.ts)
                if (s.note != null) put("note", s.note)
                if (s.author != null) put("author", s.author)
                if (s.accuracyM != null) put("acc", s.accuracyM)
                if (s.synced) put("s", 1)
            })
        }
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

    private fun parse(text: String): List<Pm> {
        val arr = JSONArray(text)
        val list = ArrayList<Pm>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Pm(
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
                    userAdded = o.optInt("user", 0) == 1
                )
            )
        }
        return list
    }

    // ---- Recherche / proximité (utilisent la position effective) ----

    fun nearest(lat: Double, lon: Double, limit: Int = 25): List<PmDistance> {
        return pms.asSequence()
            .map { view(it) }
            .map { PmDistance(it, haversine(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.meters }
            .take(limit)
            .toList()
    }

    // ---- Zone ARCEP (quel PM dessert ce point) ----

    private fun loadZones(context: Context): Map<String, List<List<DoubleArray>>> = try {
        val text = context.assets.open("zones_normandie.json").bufferedReader().use { it.readText() }
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
