-- PM Fibre — schéma BDD (MariaDB)
-- Généré pour le projet PM Fibre (v4 client/serveur).
-- Charset utf8mb4 partout (communes avec accents, commentaires libres).

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Utilisateurs : prénom (identifiant unique) + mot de passe haché.
-- email NULLABLE, conservé « pour plus tard » (pas requis à l'inscription).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
  id             INT AUTO_INCREMENT PRIMARY KEY,
  username       VARCHAR(64) NOT NULL UNIQUE,   -- prénom, identifiant de connexion
  password_hash  VARCHAR(255) NOT NULL,         -- pbkdf2_sha256$iter$salt$hash
  email          VARCHAR(255) NULL,             -- optionnel, pour plus tard
  role           ENUM('user','admin') NOT NULL DEFAULT 'user',
  user_type      VARCHAR(16) NOT NULL DEFAULT 'interne',   -- interne | externe (selon code d'invitation)
  active         BOOLEAN NOT NULL DEFAULT TRUE,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login     TIMESTAMP NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Réglages éditables à chaud depuis l'app (codes d'invitation, etc.)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS settings (
  k  VARCHAR(64) PRIMARY KEY,
  v  VARCHAR(255) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Sessions : jeton opaque haché -> user
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sessions (
  token_hash  CHAR(64) PRIMARY KEY,       -- sha256 hex du jeton
  user_id     INT NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at  TIMESTAMP NOT NULL,
  CONSTRAINT fk_sess_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_sess_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- PM : données de référence ARCEP (socle "fiche"). Périmètre Normandie.
-- osm_lat/osm_lon = approximation OSM éventuelle (indicatif, NON "exacte").
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pm (
  code        VARCHAR(64) PRIMARY KEY,    -- réf ARCEP
  oi          VARCHAR(16),
  op          VARCHAR(255),
  com         VARCHAR(255),
  dep         VARCHAR(64),
  dep_code    VARCHAR(3),
  etat        VARCHAR(32),
  date_pm     DATE,
  lgt         INT,
  tot         INT,
  osm_lat     DOUBLE NULL,
  osm_lon     DOUBLE NULL,
  source      VARCHAR(16) NOT NULL DEFAULT 'arcep',   -- 'arcep' | 'user'
  created_by  VARCHAR(64) NULL,
  address     VARCHAR(255) NULL,                        -- adresse indicative (fichier collègue)
  INDEX idx_pm_dep (dep_code),
  INDEX idx_pm_com (com),
  INDEX idx_pm_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Positions EXACTES saisies par les users (source de vérité "Position exacte")
-- 1 position courante par PM ; historique dans pm_position_history.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pm_positions (
  pm_code     VARCHAR(64) PRIMARY KEY,
  lat         DOUBLE NOT NULL,
  lon         DOUBLE NOT NULL,
  accuracy_m  DOUBLE NULL,                -- précision GPS en mètres si dispo
  author      VARCHAR(255),               -- e-mail de l'auteur
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_pos_pm FOREIGN KEY (pm_code) REFERENCES pm(code) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Historique des positions (audit / correction d'une position imprécise)
CREATE TABLE IF NOT EXISTS pm_position_history (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  pm_code     VARCHAR(64) NOT NULL,
  lat         DOUBLE NOT NULL,
  lon         DOUBLE NOT NULL,
  accuracy_m  DOUBLE NULL,
  author      VARCHAR(255),
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_hist_pm (pm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Commentaires par PM (éditables par admin)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pm_comments (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  pm_code     VARCHAR(64) NOT NULL,
  body        TEXT NOT NULL,
  author      VARCHAR(255),
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_com_pm FOREIGN KEY (pm_code) REFERENCES pm(code) ON DELETE CASCADE,
  INDEX idx_com_pm (pm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Confirmations : un user atteste que la position exacte d'un PM est correcte
-- (sans la déplacer). 1 confirmation par (PM, user).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pm_confirmations (
  pm_code       VARCHAR(64) NOT NULL,
  username      VARCHAR(64) NOT NULL,
  confirmed_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (pm_code, username),
  INDEX idx_conf_pm (pm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
