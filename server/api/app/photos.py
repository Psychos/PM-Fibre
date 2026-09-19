"""Stockage des photos de PM (§ 3.7).

Le fichier vit sur le disque, la base ne garde que de quoi le retrouver : une
photo de 200 Ko en `LONGBLOB` transformerait chaque dump de sauvegarde
nocturne en plusieurs centaines de mégaoctets, et le NAS garde trente jours.

Le nom de fichier est l'empreinte SHA-256 du contenu. Deux conséquences
gratuites : deux envois du même cliché — le même collègue qui appuie deux fois,
une reprise après coupure réseau — n'occupent qu'une place, et un fichier ne
peut jamais être écrasé par un autre de même nom mais de contenu différent.
"""

import hashlib
import os
import struct

# Chemin du stockage. Valeur par défaut = point de montage du volume Docker ;
# en développement local, poser PHOTOS_DIR sur un répertoire quelconque.
PHOTOS_DIR = os.environ.get("PHOTOS_DIR", "/photos")

# 2 Mo. Le client envoie du JPEG recompressé à ~200 Ko (§ 3.7) ; cette borne
# n'est pas une cible, c'est le garde-fou contre un client qui enverrait
# l'original de 8 Mo sorti du capteur.
MAX_BYTES = 2 * 1024 * 1024

# Par PM, toutes catégories confondues. La fiche en montre deux (le PM, la vue
# d'approche) ; au-delà, personne ne les regarde et le disque se remplit.
MAX_PAR_PM = 6

FORMATS = {b"\xff\xd8\xff": ("jpg", "image/jpeg"), b"\x89PNG": ("png", "image/png")}


def format_reel(data: bytes) -> tuple[str, str] | None:
    """Extension et type MIME déduits du CONTENU, jamais de l'en-tête déclaré.

    Le `Content-Type` d'un envoi multipart est écrit par le client : s'y fier
    reviendrait à servir plus tard, sous `image/jpeg`, ce qu'un client aurait
    déposé en le nommant ainsi.
    """
    for magie, infos in FORMATS.items():
        if data.startswith(magie):
            return infos
    return None


def dimensions(data: bytes) -> tuple[int | None, int | None]:
    """Largeur et hauteur, lues dans l'en-tête, sans bibliothèque d'images.

    Pillow pèse une dizaine de mégaoctets dans l'image Docker pour deux entiers
    qui ne servent qu'à dimensionner une vignette. En cas de doute on rend
    `None` : une vignette sans dimensions connues se débrouille, un serveur qui
    refuse une photo pour un en-tête exotique, non.
    """
    try:
        if data.startswith(b"\x89PNG"):
            larg, haut = struct.unpack(">II", data[16:24])
            return larg, haut
        if data.startswith(b"\xff\xd8\xff"):
            i = 2
            while i + 9 < len(data):
                if data[i] != 0xFF:
                    return None, None
                marqueur = data[i + 1]
                taille = struct.unpack(">H", data[i + 2:i + 4])[0]
                # SOF0..SOF15, sauf DHT (C4), DNL (C8) et DAC (CC) qui partagent
                # la plage sans porter de dimensions.
                if 0xC0 <= marqueur <= 0xCF and marqueur not in (0xC4, 0xC8, 0xCC):
                    haut, larg = struct.unpack(">HH", data[i + 5:i + 9])
                    return larg, haut
                i += 2 + taille
    except (struct.error, IndexError):
        pass
    return None, None


def chemin(nom: str) -> str:
    """Chemin absolu d'un fichier stocké, à partir du nom gardé en base."""
    return os.path.join(PHOTOS_DIR, nom)


def enregistre(data: bytes, ext: str) -> str:
    """Écrit le fichier s'il n'existe pas déjà et rend son nom relatif.

    Deux niveaux de sous-répertoires : quelques dizaines de milliers de fichiers
    à plat ralentissent tout listage, et les sauvegardes en font.
    """
    sha = hashlib.sha256(data).hexdigest()
    nom = f"{sha[:2]}/{sha}.{ext}"
    dest = chemin(nom)
    if not os.path.exists(dest):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        # Écriture atomique : une coupure en plein `write` laisserait sinon un
        # fichier tronqué portant l'empreinte du fichier complet — donc jamais
        # réécrit, et définitivement illisible.
        temporaire = dest + ".tmp"
        with open(temporaire, "wb") as f:
            f.write(data)
        os.replace(temporaire, dest)
    return nom


def supprime(nom: str) -> None:
    """Efface le fichier ; son absence n'est pas une erreur."""
    try:
        os.remove(chemin(nom))
    except OSError:
        pass
