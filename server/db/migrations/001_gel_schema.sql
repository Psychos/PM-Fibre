-- Gel de schéma (roadmap § 4.3, § 4.4, § 3.4, § 3.6, § 3.7).
--
-- Toutes les tables et colonnes dont les fonctionnalités décidées auront besoin
-- sont créées ici, VIDES, en attendant leur interface. Motif : une migration
-- sur une base déjà déployée chez des utilisateurs coûte bien plus cher qu'une
-- colonne inutilisée pendant deux mois.
--
-- Réexécutable : MariaDB accepte IF NOT EXISTS sur ADD COLUMN et CREATE INDEX.

-- ---------------------------------------------------------------------------
-- § 4.4 — mode de saisie de la position.
-- Sans lui, impossible d'appliquer la règle des 10 m : on ne peut pas comparer
-- une position "rapide" (une photo au jugé) à une "précise" (moyenne de
-- plusieurs relevés) si la base ne dit pas laquelle est laquelle.
--   rapide | precise | manuelle | osm
-- NULL = saisie antérieure à cette migration, mode inconnu.
-- ---------------------------------------------------------------------------
ALTER TABLE pm_positions      ADD COLUMN IF NOT EXISTS method VARCHAR(16) NULL AFTER accuracy_m;
ALTER TABLE pm_position_history ADD COLUMN IF NOT EXISTS method VARCHAR(16) NULL AFTER accuracy_m;

-- ---------------------------------------------------------------------------
-- § 3.4 — retrait du référentiel ARCEP.
-- Un PM qui disparaît d'un trimestre n'est JAMAIS supprimé : les FK
-- pm_positions / pm_comments sont ON DELETE CASCADE, un DELETE effacerait la
-- position exacte et les commentaires que quelqu'un est allé relever sur le
-- terrain. Il est marqué retiré, et l'app l'affiche en conséquence.
--
-- dataset_version : dernier millésime ARCEP où le PM était présent. Sert aussi
-- à l'import versionné pour calculer les retraits sans relire tout le jeu.
-- ---------------------------------------------------------------------------
ALTER TABLE pm ADD COLUMN IF NOT EXISTS retired_at      DATETIME NULL;
ALTER TABLE pm ADD COLUMN IF NOT EXISTS dataset_version VARCHAR(32) NULL;
CREATE INDEX IF NOT EXISTS idx_pm_retired ON pm (retired_at);

-- ---------------------------------------------------------------------------
-- § 3.6 — étiquettes, deux familles (accès, type de site), multi-choix.
-- Une ligne par étiquette posée : l'auteur est conservé, et retirer une
-- étiquette est un DELETE, pas la réécriture d'une liste.
-- family est redondant avec le préfixe du slug, mais permet de filtrer une
-- famille sans LIKE.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pm_tags (
  pm_code     VARCHAR(64)  NOT NULL,
  tag         VARCHAR(32)  NOT NULL,   -- acces_haie, site_shelter, ...
  family      VARCHAR(16)  NOT NULL,   -- acces | site
  author      VARCHAR(255) NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (pm_code, tag),
  CONSTRAINT fk_tag_pm FOREIGN KEY (pm_code) REFERENCES pm(code) ON DELETE CASCADE,
  INDEX idx_tag_pm (pm_code),
  INDEX idx_tag_tag (tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- § 3.7 — « comment y accéder » : une indication courte affichée en tête de
-- fiche, et un point d'accès facultatif (portail, chemin) distinct du PM
-- lui-même. Les deux répondent à la même question, d'où une seule table.
--
-- Table séparée de pm, volontairement : pm est réécrite à chaque import ARCEP,
-- ce qui est exactement le piège du § 4.2. Ce qui vient des utilisateurs ne
-- doit pas vivre dans une table que l'import réécrit.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pm_access (
  pm_code     VARCHAR(64)  NOT NULL PRIMARY KEY,
  note        VARCHAR(255) NULL,       -- « portail vert, code 1234A »
  lat         DOUBLE NULL,             -- point d'accès, si différent du PM
  lon         DOUBLE NULL,
  author      VARCHAR(255) NULL,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_access_pm FOREIGN KEY (pm_code) REFERENCES pm(code) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- § 3.7 — photos. Le fichier vit sur le disque du serveur, la base ne garde
-- que de quoi le retrouver et le dédoublonner.
-- kind : pm (le PM lui-même) | acces (le chemin pour y arriver)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pm_photos (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  pm_code     VARCHAR(64)  NOT NULL,
  kind        VARCHAR(16)  NOT NULL DEFAULT 'pm',
  filename    VARCHAR(255) NOT NULL,
  sha256      CHAR(64)     NULL,
  bytes       INT          NULL,
  width       INT          NULL,
  height      INT          NULL,
  author      VARCHAR(255) NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_photo_pm FOREIGN KEY (pm_code) REFERENCES pm(code) ON DELETE CASCADE,
  INDEX idx_photo_pm (pm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- § 4.1 — pierres tombales, pour la synchro incrémentale.
-- Une synchro par `since` ne voit que ce qui existe : sans trace des
-- suppressions, un commentaire effacé sur le serveur resterait sur le
-- téléphone indéfiniment.
--
-- Pas de clé étrangère vers pm : une pierre tombale doit survivre à la
-- disparition de ce qu'elle décrit — c'est sa seule raison d'être.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tombstones (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  kind        VARCHAR(16)  NOT NULL,   -- position | comment | confirmation | tag | photo | access
  pm_code     VARCHAR(64)  NOT NULL,
  ref         VARCHAR(64)  NULL,       -- id du commentaire, slug d'étiquette... NULL si la clé est pm_code seul
  deleted_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  author      VARCHAR(255) NULL,
  INDEX idx_tomb_date (deleted_at),
  INDEX idx_tomb_pm (pm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- § 4.1 — index de synchro incrémentale.
-- La synchro actuelle tire `limit=10000` sans filtre ; elle passera à
-- « ce qui a changé depuis `since`, dans mes départements ». Ces requêtes
-- trient sur la date de modification, qui n'était indexée nulle part.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_pos_updated  ON pm_positions (updated_at);
CREATE INDEX IF NOT EXISTS idx_com_updated  ON pm_comments (updated_at);
CREATE INDEX IF NOT EXISTS idx_conf_updated ON pm_confirmations (confirmed_at);
