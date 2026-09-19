package com.pmfibre.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.content.FileProvider
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Photos de terrain (roadmap 3.7) : capture, compression, file d'attente, cache.
 *
 * « Une photo fait trouver un PM situé à 30 m de sa position enregistrée. » Le
 * GPS amène à l'approche, la photo fait les derniers mètres.
 *
 * Tout est différé, comme les étiquettes : le PM difficile à trouver est souvent
 * celui qu'on photographie au fond d'une zone sans réseau. La photo est
 * compressée et mise en file d'attente sur-le-champ ; l'envoi attend la synchro.
 */
object PhotoStore {

    /** Côté le plus long après réduction. Au-delà, on transporte du détail que
     *  personne ne regarde : la photo sert à reconnaître un portail, pas à lire
     *  une étiquette de câble. */
    private const val COTE_MAX = 1600

    /** Cible de compression, conforme au ~200 Ko de la roadmap. Le serveur
     *  refuse au-delà de 2 Mo ; on reste très en deçà, la 4G de campagne étant
     *  ce qu'elle est. */
    private const val CIBLE_OCTETS = 250 * 1024

    private const val FILE_ATTENTE = "photos_queue.json"

    data class EnAttente(val fichier: String, val code: String, val kind: String, val ts: Long)

    private fun dossier(context: Context, nom: String): File =
        File(File(context.filesDir, "photos"), nom).apply { mkdirs() }

    private fun dossierQueue(context: Context) = dossier(context, "queue")
    private fun dossierCache(context: Context) = dossier(context, "cache")

    // ---- Capture ----

    /**
     * Fichier cible d'une prise de vue, et son URI partageable.
     *
     * `ACTION_IMAGE_CAPTURE` sans fichier de sortie ne rend qu'une vignette de
     * quelques centaines de pixels — inutilisable pour reconnaître un lieu. Il
     * faut donc lui donner un fichier, et un `file://` est refusé depuis
     * Android 7 : d'où le `FileProvider`.
     */
    fun uriDeCapture(context: Context): Pair<File, Uri> {
        val f = File(dossier(context, "tmp"), "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        return f to uri
    }

    // ---- Compression ----

    /**
     * Réduit et recompresse la prise de vue, et rend les octets à envoyer.
     *
     * Le sous-échantillonnage précède le décodage : décoder en pleine résolution
     * les 12 mégapixels d'un capteur récent réclame ~48 Mo de tas, ce qu'un
     * téléphone d'entrée de gamme refuse. `inSampleSize` divise avant.
     *
     * Rend `null` si le fichier n'est pas décodable — une capture annulée laisse
     * parfois un fichier vide.
     */
    fun compresse(source: File): ByteArray? {
        if (!source.exists() || source.length() == 0L) return null

        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bornes)
        if (bornes.outWidth <= 0 || bornes.outHeight <= 0) return null

        var echantillon = 1
        val cote = maxOf(bornes.outWidth, bornes.outHeight)
        while (cote / (echantillon * 2) >= COTE_MAX) echantillon *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = echantillon }
        var bmp = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null

        // Le capteur écrit l'orientation dans l'EXIF au lieu de tourner les
        // pixels : sans cette étape, une photo prise à la verticale s'affiche
        // couchée, et une armoire couchée ne ressemble plus à rien.
        bmp = redresse(bmp, source)

        val plusGrand = maxOf(bmp.width, bmp.height)
        if (plusGrand > COTE_MAX) {
            val ratio = COTE_MAX.toFloat() / plusGrand
            val reduit = Bitmap.createScaledBitmap(
                bmp, (bmp.width * ratio).toInt().coerceAtLeast(1),
                (bmp.height * ratio).toInt().coerceAtLeast(1), true)
            if (reduit !== bmp) bmp.recycle()
            bmp = reduit
        }

        // Qualité dégressive : la taille d'un JPEG dépend de la scène autant que
        // de la qualité, une valeur fixe donnerait 90 Ko sur un mur nu et 600 Ko
        // sur une haie. On descend jusqu'à tenir la cible, sans aller sous 45 où
        // les artefacts commencent à masquer les détails utiles.
        var qualite = 85
        var octets: ByteArray
        while (true) {
            val flux = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, qualite, flux)
            octets = flux.toByteArray()
            if (octets.size <= CIBLE_OCTETS || qualite <= 45) break
            qualite -= 10
        }
        bmp.recycle()
        return octets
    }

    /**
     * Décode un fichier pour l'affichage, sous-échantillonné au passage.
     *
     * Une fiche peut porter six photos : les décoder en pleine taille pour les
     * afficher en vignettes de 96 dp remplirait le tas pour rien.
     */
    fun decode(fichier: File, coteMax: Int): Bitmap? {
        if (!fichier.exists() || fichier.length() == 0L) return null
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fichier.absolutePath, bornes)
        if (bornes.outWidth <= 0 || bornes.outHeight <= 0) return null
        var echantillon = 1
        val cote = maxOf(bornes.outWidth, bornes.outHeight)
        while (cote / (echantillon * 2) >= coteMax) echantillon *= 2
        return try {
            BitmapFactory.decodeFile(
                fichier.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = echantillon })
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun redresse(bmp: Bitmap, source: File): Bitmap {
        val degres = try {
            when (ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (degres == 0f) return bmp
        val m = Matrix().apply { postRotate(degres) }
        return try {
            val tourne = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (tourne !== bmp) bmp.recycle()
            tourne
        } catch (_: OutOfMemoryError) {
            bmp
        }
    }

    // ---- File d'attente ----

    /** Met la photo en file d'attente et rend son fichier local (vignette immédiate). */
    fun ajoute(context: Context, code: String, kind: String, octets: ByteArray): File {
        val f = File(dossierQueue(context), "${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
        f.writeBytes(octets)
        val liste = enAttente(context).toMutableList()
        liste.add(EnAttente(f.name, code, kind, System.currentTimeMillis()))
        ecritQueue(context, liste)
        return f
    }

    fun enAttente(context: Context): List<EnAttente> {
        val f = File(context.filesDir, FILE_ATTENTE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(EnAttente(o.getString("f"), o.getString("code"),
                        o.optString("kind", "pm"), o.optLong("ts", 0L)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun enAttentePour(context: Context, code: String?): List<EnAttente> =
        if (code == null) emptyList() else enAttente(context).filter { it.code == code }

    fun fichierEnAttente(context: Context, e: EnAttente): File =
        File(dossierQueue(context), e.fichier)

    /** Retire une entrée de la file et efface son fichier (envoyée, ou abandonnée). */
    fun retire(context: Context, nom: String) {
        ecritQueue(context, enAttente(context).filterNot { it.fichier == nom })
        File(dossierQueue(context), nom).delete()
    }

    private fun ecritQueue(context: Context, liste: List<EnAttente>) {
        val arr = JSONArray()
        for (e in liste) {
            arr.put(JSONObject().put("f", e.fichier).put("code", e.code)
                .put("kind", e.kind).put("ts", e.ts))
        }
        File(context.filesDir, FILE_ATTENTE).writeText(arr.toString())
    }

    // ---- Cache des photos téléchargées ----

    /**
     * Fichier local d'une photo du serveur. Son nom côté serveur est l'empreinte
     * de son contenu : un identifiant ne change donc jamais de contenu et le
     * cache n'a aucune invalidation à gérer.
     */
    fun fichierCache(context: Context, id: Int): File = File(dossierCache(context), "$id.jpg")

    fun enCache(context: Context, id: Int): File? =
        fichierCache(context, id).takeIf { it.exists() && it.length() > 0 }

    fun ecritCache(context: Context, id: Int, octets: ByteArray): File {
        val f = fichierCache(context, id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(octets)
        tmp.renameTo(f)
        return f
    }

    fun oublieCache(context: Context, id: Int) {
        fichierCache(context, id).delete()
    }

    /** Taille du cache, pour l'écran Paramètres › Données. */
    fun tailleCache(context: Context): Long =
        dossierCache(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun videCache(context: Context) {
        dossierCache(context).listFiles()?.forEach { it.delete() }
    }

    /** Nettoie les fichiers de capture temporaires : ils ne servent qu'un instant. */
    fun nettoieTemporaires(context: Context) {
        val limite = System.currentTimeMillis() - 24 * 3600_000L
        dossier(context, "tmp").listFiles()?.forEach {
            if (it.lastModified() < limite) it.delete()
        }
    }
}
