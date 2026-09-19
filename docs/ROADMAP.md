# PM Fibre — Feuille de route

> Compte rendu de la séance de conception du 18-19 septembre 2026.
> Il fige les décisions ; les sections 2 à 4 les décrivent telles qu'elles ont
> été arrêtées et n'ont pas été réécrites depuis.
> **État du code au 19 septembre 2026 : les six points bloquants de la
> section 5 sont faits** — chacun porte sa note de réalisation, y compris les
> écarts assumés. Rien n'est encore déployé ni publié.
> À lire en premier par toute nouvelle session de travail.

---

## 1. Objectif de la prochaine étape

Couvrir **toute la France** au lieu de la seule Normandie, **avant la diffusion de
l'application**. La raison est décisive : une fois l'app entre les mains des
utilisateurs, les changements de schéma de base et de format de données se font sur
une base vivante, à la main, sans filet.

---

## 2. Volumétrie mesurée

Chiffres **mesurés sur le jeu ARCEP T2 2026** le 19 septembre (la colonne
« France » était extrapolée dans la version d'origine).

| | Normandie | 7 départements | France entière |
|---|---|---|---|
| PM | 4 930 | 7 930 | **99 451** |
| Départements | 5 | 7 | 103 (DOM/COM compris) |
| `pm_multi.json` | — | 1,5 Mo | 19,4 Mo |
| `zones_multi.json` | 1,05 Mo | 1,7 Mo | 20,9 Mo |
| **Paquets `.tgz`** | — | **664 Ko** | **8,22 Mo** |

Détail Normandie : Seine-Maritime 1 873 · Calvados 1 081 · Eure 758 · Manche 616 ·
Orne 604. Positions exactes d'origine OSM (`p=1`) : 324 en Normandie, 1 321 sur
les 7 départements, **11 278 sur la France** (11 %).

**Moyenne par département** : ~965 PM ≈ **82 Ko gzippés**, l'estimation
« ~100 Ko » était bonne. Le plus gros, le Nord (3 544 PM), tient en **315 Ko**
et non 350. Le plus petit, Saint-Barthélemy, en 1,4 Ko.

**Pire cas du plafond de 6 départements** (§ 3.1) : Nord + Gironde +
Pas-de-Calais + les trois suivants = **1,25 Mo**. Le § 3.1 tablait sur 2,5 Mo de
PM plus 3 Mo de zones — c'était du brut ; compressé, l'ordre de grandeur est
cinq fois moindre.

Conclusion inchangée, et renforcée : le découpage par département est très
léger. Pas besoin de deltas, un paquet complet par département suffit.

---

## 3. Décisions actées

### 3.1 Plafond de 5 à 6 départements

Un technicien travaille rarement au-delà de 2 ou 3 départements. Le plafond laisse
de la marge et peut être relevé à tout moment.

Conséquences :

- **Pas besoin de SQLite/Room dans l'immédiat.** 6 départements ≈ 6 000 PM, soit
  l'ordre de grandeur de la Normandie actuelle (4 930), qui fonctionne déjà en
  mémoire. La migration SQLite passe de « nécessaire » à « optimisation si le
  démarrage devient lent ».
- Pire cas (6 gros départements) ≈ 14 000 PM, ~2,5 Mo de PM + 3 Mo de zones.
  Charger les zones en tâche de fond après l'affichage de la carte.
- Le plafond est une **valeur du manifeste serveur** (`max_deps`), pas une
  constante de l'app : il doit pouvoir évoluer sans republier l'APK.
- Quand les 6 cases sont prises : afficher « 6/6 — décochez un département pour
  en ajouter un », ne pas griser les autres.
- **Pas de case à cocher par région** (l'Occitanie fait 13 départements) :
  regroupement visuel par région uniquement.

### 3.2 Distribution des données

- Les données sortent de l'APK (`assets/`) et deviennent des paquets téléchargés
  dans `filesDir/deps/<code>/`.
- L'APK ne garde que `oi_map.json` (3 Ko) et démarre vide.
- Manifeste servi en statique par le nginx existant (`site/`), pas par l'API :
  ```
  /data/manifest.json → {
    dataset: "ZAPM 2026T2", generated: "...", max_deps: 6, min_app_version: 12,
    deps: [ { code: "14", nom: "Calvados", pm: 1080,
              size: ..., sha256: "...", url: "deps/14.tgz" }, ... ]
  }
  ```
- `min_app_version` permet de forcer une mise à jour d'app si le format change.
- Téléchargement via WorkManager : reprise possible, option « Wi-Fi seulement »,
  écriture atomique (`.part` + rename), vérification sha256.
- Les données ARCEP sont en licence ouverte → diffusion statique sans
  authentification, avec mention d'attribution.

### 3.3 Décharger un département

Supprime les données ARCEP, **jamais** `saved_positions.json`, `added_pms.json`,
les commentaires ni les étiquettes : ils sont indexés par code PM et doivent
survivre au rechargement ultérieur du département. À indiquer clairement dans la
boîte de confirmation.

### 3.4 Vérification de mise à jour au lancement

- `GET manifest.json` avec `If-None-Match`/ETag, timeout 3 s, **jamais bloquant**,
  uniquement si `ConnectivityManager` signale du réseau.
- Si écart → bandeau discret, bouton vers l'écran Départements.
- « Plus tard » et « ignorer cette version » obligatoires, au plus une
  vérification par 24 h.
- Une mise à jour ARCEP = un nouveau trimestre. Afficher un rapport
  (« 12 PM ajoutés, 3 retirés »). Un PM disparu du référentiel s'affiche
  « retiré du référentiel », sa position exacte n'est pas effacée.

### 3.5 Deux modes de capture GPS

| Mode | Comportement |
|---|---|
| **Capture rapide** | Le fix unique actuel. |
| **Capture précise (30 s)** | Décompte, pour qui prend le temps. |

Spécification de la capture précise :

- `requestLocationUpdates` pendant 30 s par défaut, arrêt anticipé si la précision
  est bonne et stable, bouton « Valider maintenant » toujours disponible.
- Jeter les 5 premières secondes (convergence), ne retenir que les fixes proches
  de la meilleure précision observée, puis **médiane** sur lat/lon (plus robuste
  qu'une moyenne face à un fix aberrant).
- La précision enregistrée est la **médiane des précisions retenues**, pas la
  meilleure valeur affichée (trop optimiste).
- `FLAG_KEEP_SCREEN_ON` pendant la capture, sinon Android bride le GNSS.
- Avertir si les fixes dérivent (« reste immobile »).
- Jauge de précision en direct : 38 m → 22 m → 11 m → 6 m.

Deux boutons explicites, pas un appui long : le choix est délibéré.

### 3.6 Étiquettes rapides

Listes arrêtées avec Olivier, d'après son terrain réel (Évreux, Louviers, campagne).
Multi-sélection, pose possible **hors ligne** avec synchro différée, table dédiée
côté serveur, icône sur la carte et dans la liste, filtrables.

**Accès** (difficulté à trouver ou à atteindre) :

- derrière une haie
- impasse / recoin
- accès par l'arrière
- derrière un portail *(enceinte grillagée d'un shelter opérateur)*
- végétation dense
- non visible de la route *(drapeau de synthèse affiché sur la carte)*

**Type de site** :

- shelter *(petit bâtiment contenant 1 à 3 PM)*
- armoire de rue
- local technique
- autre

Écartés, et pourquoi :

- **propriété privée** — le PM est sur le domaine public ou une emprise opérateur.
- **en sous-sol / PM d'immeuble / PM en façade** — existent en zone très dense, pas
  sur le terrain couvert (zone moins dense : un PM dessert au minimum quelques
  centaines de lignes, donc armoire ou shelter).
- **chambre souterraine** — concerne les PBO, pas les PM.

L'étiquette « autre » n'est pas du remplissage : si elle est cochée 40 fois avec la
même note, elle dira quelle étiquette ajouter — et si l'app sort de Normandie, ce
sont les utilisateurs qui indiqueront ce qui manque.

**Répartition étiquettes / commentaires** : étiquette pour le fréquent, commentaire
pour l'exception (« passer par le parc municipal », « accès par l'école, demander
au gardien »). Une liste d'étiquettes qui tenterait de couvrir ces cas deviendrait
illisible.

### 3.7 Trouvabilité plutôt que précision

Constat déterminant issu de la séance : le besoin n'est **pas la précision de la
coordonnée**, c'est la **trouvabilité de l'objet**. Certains PM sont derrière des
haies, dans des recoins, invisibles depuis la route. Même à 2 m près, on cherche.
Inversement, une photo fait trouver un PM situé à 30 m de sa position enregistrée.

Le GPS amène **à l'approche**, autre chose doit faire **les derniers mètres** :

1. **Photos** — une du PM, une de la **vue d'approche depuis la route** avec un
   repère (portail, numéro, poteau). Compression ~200 Ko, envoi différé, vignette
   dans la fiche. Développement le plus rentable de toute la liste.
2. **Indication d'accès** — champ unique, court, éditable, affiché **en tête de
   fiche**, disponible **hors ligne**.
3. **Point d'accès distinct** — deuxième couple de coordonnées facultatif : « où se
   garer / par où entrer », séparé de la position du PM. Cas du lotissement dont
   l'accès se fait par l'arrière.
4. **Fond ortho IGN** — BD ORTHO 20 cm/pixel, gratuite, flux WMTS Géoplateforme,
   gérée par osmdroid. Utile autant pour **lire** (la haie, le recoin et le chemin
   d'accès se voient) que pour **saisir** (pointage à 1-2 m, dix fois mieux que le
   GNSS du téléphone).
5. **Cercle d'incertitude** sur la carte plutôt qu'un point net : un point net à
   30 m près est un mensonge visuel, le collègue cherchera au mauvais endroit en
   toute confiance.

Écartés pour l'instant : moyennage multi-relevés côté serveur (la matière existe
pourtant déjà dans `pm_position_history`), récepteur GNSS externe (u-blox ZED-F9P +
réseau RTK Centipède, gratuit en France, précision centimétrique — luxe inutile
pour localiser une armoire de rue).

### 3.8 Interface

- L'onglet **Compte** (`InfoScreen`) disparaît : c'est aujourd'hui un fourre-tout
  (données, profil, déconnexion, Hall of Fame, aide, comptes admin, mise à jour,
  GitHub, import/export).
- **Trois onglets** en bas : Recherche · À proximité · Carte.
- **`[ ? ] [ ⚙ ]`** en haut à droite, dans une `TopAppBar` commune au `MainScreen`
  (il n'en existe aucune aujourd'hui ; seuls les écrans de détail en ont une).
  Ordre `? ⚙`, l'engrenage tout à droite selon la convention Android.
- Le **« ? » est contextuel** : il connaît l'onglet courant et ouvre l'aide à la
  bonne section, avec un bouton « ▲ Toute l'aide ». Idem dans les écrans de détail.
- Le **badge « mise à jour disponible »** va sur l'engrenage, pas sur le « ? ».
- Sur l'onglet Carte, masquer ou affiner la `TopAppBar` (hauteur utile).

Rubriques des **Paramètres** (sous-écrans, pas une longue page) :

- Départements — `3/6`, télécharger / décharger
- Affichage — thème, contraste élevé, taille du texte, style des PM, libellés
- Carte — fond de carte, cache des tuiles hors-ligne (taille, vider)
- Profil — e-mail, mot de passe, mes contributions, **se déconnecter** (bas, rouge)
- Données — import/export des positions, forcer une resynchro
- Mises à jour — vérifier maintenant, dernière vérification, Wi-Fi seulement
- Administration — comptes, codes d'invitation *(admin uniquement)*
- À propos — version app, dataset ARCEP, licence, GitHub, Hall of Fame

Persistance via un objet `Settings` calqué sur `SessionStore` (SharedPreferences),
thème appliqué au niveau de `MainActivity`.

**Avant le sélecteur de thème, centraliser les couleurs** dans un
`MaterialTheme`/`colorScheme` : elles sont aujourd'hui codées en dur un peu partout
(`BluePrimary`, `HelpBlueDark`, `PmExactColor`, `PmApproxColor`, `themes.xml`).
Le sélecteur devient alors trivial. Le mode sombre / contraste doit couvrir les
marqueurs de carte, le fond de carte (osmdroid sait inverser les tuiles) et le
couple vert/rouge — ~8 % des hommes sont daltoniens, donc changer la **forme** ou
ajouter un liseré, pas seulement la teinte. Utile aussi en plein soleil.

### 3.9 Carte

- Remplacer les pins « goutte » par des **points ronds**.
- Passer de `Marker` (un overlay par PM, lent au-delà de quelques centaines) à
  **`SimpleFastPointOverlay`** : conçu pour des milliers de points en un seul
  overlay, style `CIRCLE`, algorithme `MAXIMUM_OPTIMIZATION`, clustering optionnel,
  `onClick`. Avec des `LabelledGeoPoint`, on obtient le libellé à côté du point —
  le rendu des cartes NRA. Déjà présent dans osmdroid 6.1.20, aucune dépendance
  à ajouter.
- Attention au conflit de couleurs : les PM en bleu entreraient en concurrence
  avec le point bleu « ma position » (`blueDotIcon`), à distinguer autrement.
- **Shelters** : 1 à 3 PM au même endroit → points superposés indiscernables.
  Prévoir un affichage groupé (« 3 PM ici ») ; c'est aussi une information utile :
  trouver le shelter, c'est trouver les trois.

---

## 4. Pièges identifiés dans le code existant

Par ordre de gravité. Les trois premiers sont bloquants avant diffusion.

### 4.1 La purge silencieuse des positions — CRITIQUE

`ApiClient.fetchAllPositions` demande `/pm?has_position=true&limit=10000`, soit
exactement le plafond serveur, et `PmRepository.mergeServerPositions` **purge**
toute position `synced` absente de la réponse.

À l'échelle nationale la liste sera tronquée → **effacement silencieux de positions
valides**, c'est-à-dire du travail de terrain perdu sans message d'erreur.

Correctif : filtrer par les départements de l'utilisateur (`?dep=14,27,50`) **et**
passer en synchro incrémentale (`?since=<timestamp>` + table de *tombstones* pour
les positions supprimées par un admin), en remplacement de la purge. Supprime le
plafond et divise le trafic.

### 4.2 L'import national ne se déclenchera jamais

`main.py` → `_import_pm_if_empty` sort immédiatement si la table `pm` contient déjà
quelque chose. En production elle contiendra les 4 930 PM normands : l'import
national **ne tournera pas**.

Correctif : import **versionné et idempotent**, piloté par la version du jeu ARCEP
stockée dans `settings`, capable de passer de 4 930 à 99 268 lignes puis d'un
trimestre au suivant. Prévoir un insert en masse (`db.merge` ligne à ligne sur
99 k lignes sera très lent).

Même schéma de verrou pour `_seed_osm_positions` / `osm_seed_done`.

### 4.3 Aucun système de migration

Le serveur fait `Base.metadata.create_all` + le SQL d'init
(`server/db/init/01_schema.sql`). Cela crée les tables absentes, **jamais** une
colonne sur une table existante. Chaque évolution de schéma en production se fera
au `ALTER TABLE` à la main.

Correctif : Alembic, ou à défaut un dossier de scripts SQL numérotés + un
`schema_version` dans `settings`.

**Corollaire — gel de schéma.** Tout ce qui touche la base passe en un seul lot,
avant diffusion, même quand l'interface viendra plus tard : `method` sur les
positions, table des étiquettes, indication d'accès, table des photos, point
d'accès. Une colonne vide ne coûte rien ; une colonne ajoutée en production coûte
une soirée.

### 4.4 La règle des 10 mètres empêche l'amélioration

`main.py` → `MIN_MOVE_METERS = 10.0` refuse tout déplacement inférieur à 10 m
(anti-doublon). Conséquence : **une capture précise corrigeant une position de 6 m
sera rejetée** — exactement le cas d'usage du nouveau mode.

Correctif : autoriser le petit déplacement **quand la nouvelle mesure est meilleure
que l'ancienne**, ce qui suppose de savoir d'où vient chaque position → champ
`method` dans `pm_positions` (`rapide` / `précise` / `manuelle` / `osm`). Sert aussi
à avertir avant d'écraser une position précise par une capture rapide moins bonne,
et à afficher la qualité dans la fiche (« 🎯 précise, 4 m, par Olivier »).

### 4.5 Les commentaires disparaissent hors ligne

`PmDetailScreen` charge les commentaires depuis le serveur, l'échec est silencieux
(`catch (_: Exception) {}`) → la fiche affiche « Aucun commentaire ». **Hors réseau,
l'information d'accès manque précisément là où on en a besoin**, au bout du chemin
de campagne.

Correctif : cache local comme pour les positions. Et remonter l'indication d'accès
en tête de fiche — elle est aujourd'hui tout en bas, après les coordonnées,
l'adresse et les boutons.

### 4.6 `dep_code` absent de `pm_full.json`

`data/pm_full.json` (national) ne contient que `dep`, le **nom** du département
(`"CALVADOS"`), alors que `pm_normandie.json` contient bien `dep_code`. Le code
INSEE (`01`…`95`, `2A`, `2B`, `971`…`978`) est la clé de découpage : à standardiser
partout.

Bonus gratuit : le code ARCEP porte déjà le code commune
(`FI-91477-000Y` → 91477 → département 91). Si une recherche ne donne rien, l'app
peut donc proposer « ce PM est dans l'Essonne (91), le télécharger ? » sans index
supplémentaire.

### 4.7 Les zones ARCEP côté serveur

`geo.py` charge `zones_normandie.json` **en entier en RAM** pour valider
« position dans la zone » à chaque `PUT /pm/{code}/position`. 21 Mo de JSON en dict
Python à l'échelle nationale, c'est lourd → charger les zones **par département à
la demande** (un fichier par département), ou les basculer en base.

Sans cela, `PUT /pm/{code}/position` refusera toute position hors Normandie.

### 4.8 La carte n'affiche que le voisinage du GPS — l'affaire de Louviers

`MapScreen.kt:140` appelle `PmRepository.nearest(loc.latitude, loc.longitude, 120)` :
les 120 PM les plus proches de la position GPS, calculés **une seule fois** au
moment du fix. Faire glisser la carte vers Louviers ne recalcule rien → carte vide,
alors que les 758 PM de l'Eure sont présents dans l'APK.

Ce n'est **pas** un manque de données, c'est un bug d'affichage.

Correctif : afficher les PM de la **zone visible** (`MapListener` sur scroll/zoom,
anti-rebond ~300 ms, filtre par bounding box). Sur 758 — ou même 4 930 — PM, c'est
un balayage instantané, aucune structure de données à ajouter. Prévoir un
garde-fou sous un certain zoom (regroupement, ou « zoomez pour voir les PM »).

Cohérent avec le chantier France : avec l'affichage par bounding box, avoir 1 ou
6 départements chargés ne change plus rien aux performances de la carte.

### 4.9 Le retour arrière quitte l'application

**Aucun `BackHandler` dans tout le projet** (vérifié, zéro occurrence). Les
sous-écrans sont affichés par des booléens dans `MainScreen` (`selected`, `showAdd`,
`showAdmin`, `showHelp`) avec un `return` anticipé : pour Android il n'y a qu'un
seul écran, donc « retour » = quitter. La flèche des `TopAppBar` fonctionne, le
bouton/geste système non.

Second défaut, indépendant : le `return` **retire l'écran courant de la
composition**. Tout l'état de `NearbyScreen` (`results`, `lastLoc`, `status`,
`servingPm`…) est en `remember` local, donc détruit. Au retour, la liste est vide et
il faut refaire un fix GPS. Corriger le `BackHandler` seul ne règle pas ça.

Correctif des deux d'un coup : afficher la fiche **par-dessus** l'écran courant
(un `Box` avec le détail en second enfant) au lieu de `return`. L'écran reste
composé, sa liste et son défilement sont intacts. Idem pour `AddPmScreen`,
`AdminScreen`, `HelpScreen`.

---

## 5. Ordre de travail

### Avant diffusion — bloquant

> État vérifié sur le disque le 19 septembre 2026. Le document d'origine a été
> écrit en lisant GitHub, qui ignorait deux scripts restés non suivis.

1. **`dep_code` partout, paquets par département, manifeste** — *fait,
   France entière*

   Déjà en place :

   - `data/build_multi.py` — filtre le shapefile ARCEP **ZAPM T2 2026** et pose
     `dep_code` depuis le champ `INSEE_DEP`, à la source. C'est la bonne
     correction du § 4.6 : le code INSEE n'est pas déduit du nom du département,
     il est lu dans le référentiel. Sortie `data/pm_multi.json`, 7 930 PM.
   - `data/build_zones_multi.py` — anneaux ZAPM des mêmes PM, simplifiés en
     Douglas-Peucker à 0,0002° (~22 m), arrondis à 5 décimales. Sortie
     `data/zones_multi.json`, 7 930 zones, 1,7 Mo.
   - Côté serveur, `dep_code` existait déjà : colonne indexée dans `models.py`,
     champ des schémas, filtre `GET /pm?dep=`, lecture dans
     `_import_pm_if_empty`. Rien à reprendre là.
   - `data/build_packages.py` — **le générateur de paquets et le manifeste**,
     écrit le 19 septembre. Part de `pm_multi.json` + `zones_multi.json` (pas du
     shapefile, disparu), regroupe par `dep_code` et écrit
     `site/public/data/deps/<code>.tgz` (`pm.json` + `zones.json`) plus
     `site/public/data/manifest.json` au format du § 3.2. 7 930 PM, 664 Ko au
     total, de 53 Ko (Orne) à 178 Ko (Yvelines) — conforme à l'estimation
     « ~100 Ko gzippés par département » du § 2. Étendu le 19 septembre aux
     **103 départements** : 99 451 PM, 8,22 Mo au total.
   - **Archives déterministes** : `mtime`, `uid`/`gid` et ordre des entrées
     figés, gzip sans horodatage. Un contenu inchangé redonne le même `sha256`,
     sinon le manifeste annoncerait une mise à jour à chaque régénération.
     Vérifié sur deux exécutions successives.
   - **Plus aucune position nulle** : chaque PM sort avec une position, exacte
     (`p: 1`, 1 321 cas) ou centroïde de zone (6 609). Le centroïde est repris
     tel quel de `pm_full.json` quand il y figure (6 601) et recalculé depuis
     les anneaux ZAPM pour les 8 PM apparus en T2. Contrôle de non-régression :
     sur les 7 922 PM communs avec l'asset actuel, **aucune position et aucun
     marqueur `p` ne change**.
   - **Divergence de schéma tranchée : `p` survit, `src` disparaît** de la
     sortie. `PmRepository.kt:319` lit déjà `optInt("p", 0) == 1` ; garder `p`
     évite de toucher au parseur. `src` reste interne à `pm_multi.json`.
   - `site/nginx.conf` sert le manifeste en `no-cache` (revalidation par ETag,
     § 3.4) et les paquets en `max-age=3600`. Sans cela le cache Cloudflare
     devant `mapm.online` masquerait les mises à jour ARCEP. Syntaxe validée
     contre `nginx:alpine`.

   Reste à faire :

   - **Reste : les noms de départements sont sans accents** (« Cotes-d'Armor »,
     « La Reunion », « Puy-de-Dome »), parce que `NOM_DEP` l'est dans la source
     ARCEP. `joli_nom()` gère la casse et les particules sans table, mais les
     accents en exigeraient une, de 103 entrées. Sans conséquence tant que le
     nom n'est pas affiché — donc à trancher au point 6, avec l'écran des
     départements.
   - **`min_app_version` vaut 6**, soit le `versionCode` actuel (5) plus un :
     aucune version publiée ne sait lire ces paquets. À rectifier si l'APK qui
     les consommera porte un autre numéro.
   - **`zones_multi.json` est déjà câblé** : il a été recopié tel quel sur
     l'asset `app/src/main/assets/zones_normandie.json` en v5 (fichiers
     identiques octet pour octet) — d'où les contours ARCEP visibles sur 78 et
     72. Le nom de l'asset est désormais trompeur : il ne contient plus la
     Normandie seule. `geo.py`, lui, lit toujours l'ancien fichier 5
     départements côté serveur *(§ 4.7)*.
   - **`pm_multi.json` n'est pas câblé, et c'est délibéré.** 6 609 de ses
     7 930 PM ont `lat`/`lon` à `null` ; le parsing Kotlin utilise `getDouble`
     non optionnel et plante dessus. L'asset de l'app est donc reparti de
     l'ancien `pm_full.json` national via `filter_normandie_app.py`, qui a
     toujours un centroïde de zone en repli — mais qui filtre **par nom de
     département** et n'expose pas `dep_code`. Le § 4.6 reste à moitié réglé
     côté app.
   - **Contrainte qui en découle pour les paquets** : tout PM livré à l'app
     doit porter une position, exacte ou approchée. Le repli est le centroïde
     de sa zone ARCEP, disponible pour 6 601 des 6 609 cas dans l'ancien
     `pm_full.json`, et calculable depuis `zones_multi.json` pour le reste.
   - **Divergence de schéma à trancher.** `pm_full.json` marque les positions
     OSM exactes par `p: 1`, `pm_multi.json` par `src: "osm"`. Un seul des deux
     doit survivre avant que l'app ne lise les paquets.
   - **Le shapefile source a été retéléchargé le 19 septembre** (il avait
     disparu du disque, avec le scratchpad que cite le `CLAUDE.md`) :
     ressource « 2026T2-Zapm », zip de 74 Mo, sur la page data.gouv
     « Le marché du haut et très haut débit fixe (déploiements) ». Le lien est
     désormais dans l'en-tête de `build_multi.py`, et `data/raw_*/` est ignoré
     par git — 280 Mo une fois extrait.
     **La chaîne est reproductible** : rejoué sur les 7 départements,
     `build_multi.py` a régénéré `pm_multi.json` octet pour octet. C'est ce qui
     a permis d'élargir à la France sans rien prendre au hasard.
   - Ordre de reconstruction complet, depuis un dépôt propre :
     ```
     # extraire 2026t2-zapm.zip dans data/raw_2026T2/extracted/
     cd data && python build_multi.py && python build_zones_multi.py
     python build_packages.py
     ```
   - Écart T1 → T2 déjà mesurable : 8 PM apparus, 5 retirés sur les 7
     départements. De quoi éprouver le rapport de mise à jour du § 3.4.

   Note héritée, consignée dans l'en-tête de `build_multi.py` : les sources OSM
   brutes (`PMZ.geojson`, KML) ne sont plus sur ce poste. Les positions exactes
   sont donc **recyclées** depuis l'ancien `pm_full.json` (`p = 1`) par code PM —
   un PM ne bouge quasiment jamais. 1 321 positions sur 7 930 (17 %).

2. Import serveur national **versionné** en remplacement de `_import_pm_if_empty`,
   et mise en place d'un vrai mécanisme de migration *(§ 4.2, § 4.3)* — *fait*

   `server/api/app/importer.py` lit les paquets par département (via
   `packages.py`, les mêmes fichiers que le site sert à l'app) et n'agit que si
   le millésime du manifeste diffère de celui gardé dans `settings`. Un PM
   disparu du référentiel est **marqué `retired_at`, jamais supprimé** : les
   clés étrangères sont `ON DELETE CASCADE`, un `DELETE` emporterait sa position
   exacte. Seules les positions marquées `p: 1` entrent en base — les 88 % de
   centroïdes approchés du paquet resteraient sinon publiés comme des relevés de
   terrain. `server/api/app/migrate.py` applique les SQL numérotés de
   `server/db/migrations/` et les trace dans `schema_migrations`.

3. `geo.py` : zones par département, chargées à la demande *(§ 4.7)* — *fait*

   Cache LRU de 12 départements. Au passage : l'ancien `zones_normandie.json`
   était bien déployé sur PsyOne mais **n'était pas versionné**, si bien qu'un
   redéploiement depuis le dépôt seul aurait rendu `check_in_zone` permissif
   pour tous les PM, sans rien signaler.

4. **Synchro : filtre par département, `since`, tombstones** — *fait*

   `GET /sync/positions?dep=14,27&since=…` rend une page **et dit s'il en
   reste** (`complete`, `next_since`). C'est le point essentiel : l'ancien
   `/pm?has_position=true&limit=10000` demandait exactement le plafond du
   serveur, donc une réponse tronquée était indiscernable d'un inventaire
   complet, et la purge locale prenait les lignes manquantes pour des
   suppressions. Un plafond plus haut n'aurait fait que repousser l'échéance.
   Les suppressions passent désormais par la table `tombstones`, alimentée par
   `DELETE /pm/{code}/position` et `DELETE /comments/{id}` ; republier une
   position lève sa pierre tombale. `?dep=` accepte enfin une liste (c'était une
   égalité, `?dep=14,27,50` n'aurait rien rendu). Côté app,
   `ApiClient.syncPositions` boucle jusqu'à `complete` et n'applique rien tant
   que la boucle n'a pas abouti ; `mergeServerPositions` ne purge plus que sur
   un inventaire complet et national.

5. **Gel de schéma** : `method` sur les positions, table des étiquettes,
   indication d'accès, table des photos, point d'accès — vides, en attendant leur
   interface *(§ 4.3, § 4.4)* — *fait*

   `server/db/migrations/001_gel_schema.sql`, en une passe : `method`,
   `retired_at`, `dataset_version`, `pm_tags`, `pm_access`, `pm_photos`,
   `tombstones` et les index de date qu'exige la synchro par `since`.
   `pm_access` est délibérément séparée de `pm`, que l'import réécrit.
6. **App : données hors assets, écran des départements, vérification de mise à
   jour** — *fait*

   `pm_full.json` (1,3 Mo) et `zones_normandie.json` (1,7 Mo) quittent l'APK ;
   il ne reste que `oi_map.json` et `cp_normandie.json`, et l'app **démarre
   vide** — un état normal, pas une erreur, annoncé par un bandeau qui mène à
   l'écran Départements. `DepStore.kt` télécharge `deps/<code>.tgz`, vérifie le
   sha256 **avant** d'en extraire quoi que ce soit, écrit dans `<code>.tmp/`
   qu'il renomme une fois complet, et n'accepte du tar que les deux membres
   `pm.json` et `zones.json` — aucun nom d'entrée ne peut donc désigner un
   chemin. `PmRepository.load()` lit `filesDir/deps/` au lieu des assets ; un
   paquet illisible est ignoré plutôt que fatal.

   Le regroupement par région vit **dans le manifeste**, comme `max_deps` : un
   découpage administratif qui bouge se corrige en régénérant les données, sans
   republier l'APK. Les cases ne sont pas grisées au plafond — « 6/6 — décochez
   un département pour en ajouter un » dit *pourquoi*, ce qu'une case désactivée
   ne fait pas. Le déchargement ne touche ni les positions, ni les PM ajoutés,
   ni les commentaires (§ 3.3), et la boîte de confirmation le dit.

   La vérification de lancement suit § 3.4 : `If-None-Match`, 3 s, une fois par
   24 h, uniquement si `ConnectivityManager` signale du réseau, et elle
   n'allume qu'un bandeau — « Plus tard », « Ignorer cette version », « Mettre à
   jour ». Rien ne se télécharge sans un geste explicite. Le curseur de synchro
   est désormais mémorisé **avec son périmètre** : installer un département
   remet la synchro à un inventaire complet, sans quoi un différentiel « depuis
   hier » ne rendrait rien du département qu'on vient d'ajouter.

   `versionCode` passe à 6, valeur qu'exige le `min_app_version` du manifeste.

   Deux écarts assumés :

   - **Pas de WorkManager** (reprise en tâche de fond, « Wi-Fi seulement »),
     annoncé en § 3.2. Un paquet pèse ~80 Ko, six font 500 Ko ; ce que
     WorkManager apportait vraiment ici — écriture atomique et intégrité — est
     assuré autrement. À reprendre si l'on distribue un jour plus lourd.
   - **`cp_normandie.json` reste normand.** La recherche par code postal ne
     marche donc qu'en Normandie : les paquets ARCEP ne portent aucun code
     postal, il faudra une autre source (base officielle des codes postaux) pour
     l'étendre aux 103 départements.

### Ensuite, sans urgence

7. Confort : `BackHandler` + fiche en surimpression *(§ 4.9)* — *fait*

   Les sous-écrans s'affichent désormais **par-dessus** l'onglet, dans un `Box`,
   derrière une `Surface` opaque. Le `return` anticipé d'avant sortait l'onglet de
   la composition et emportait tout son état `remember` : ouvrir une fiche depuis
   « Autour » puis revenir rendait une liste vide et un fix GPS à refaire.
   `BackHandler` ferme le sous-écran, sinon ramène à l'onglet Recherche, sinon
   demande une seconde pression pour quitter. L'écran d'aide, qui décrivait encore
   une application à données embarquées, a été remis à jour dans la foulée.

8. Carte : points ronds, `SimpleFastPointOverlay`, affichage par zone visible,
   seuil de zoom, fond ortho IGN, cercle d'incertitude *(§ 3.9, § 4.8)* — *fait*

   `PmRepository.inBoundingBox()` remplace le `nearest()` unique : la carte rend ce
   qu'on regarde, et non le voisinage du fix GPS — l'affaire de Louviers est close.
   Un `DelayedMapListener` (300 ms) absorbe la rafale d'événements d'un glissement,
   un `OnFirstLayoutListener` remplit la carte même sans fix GPS. Les PM à moins de
   ~11 m les uns des autres sont regroupés (shelters) : un appui ouvre la fiche
   quand ils sont seuls, la liste des occupants sinon.

   Écart assumé à la décision : `MEDIUM_OPTIMIZATION` et non `MAXIMUM_OPTIMIZATION`.
   Ce dernier met sa grille en cache par niveau de zoom ; comme la liste est rebâtie
   à chaque déplacement, le cache ne servirait à rien et afficherait un glissement
   de retard.
9. Terrain : photos, indication d'accès en tête de fiche avec cache hors ligne,
   étiquettes, deux modes de capture GPS *(§ 3.5, § 3.6, § 3.7)* — **fait**

   La capture précise est dans `GpsCapture.kt` : trente secondes, cinq premières
   jetées, seuls les fixes proches de la meilleure précision observée sont retenus,
   puis **médiane** sur lat/lon et sur la précision. `FLAG_KEEP_SCREEN_ON` pendant
   la mesure, jauge en direct, avertissement de dérive, « Valider maintenant »
   toujours disponible, arrêt anticipé dès que c'est bon et stable.

   Le calcul est isolé dans `GpsFusion`, sans dépendance Android, et couvert par
   neuf tests JVM : c'est le seul endroit du client qui puisse produire une
   position fausse *et crédible*. La colonne `method` gelée au point 5 sert enfin
   (`gps_precis`), avec une liste blanche côté serveur — un client ne teinte pas
   l'historique avec ce qu'il veut, et une valeur inconnue ne fait pas perdre la
   position.

   **Étiquettes et indication d'accès.** Table `pm_tags` (une ligne par étiquette
   posée) et `pm_access` (une ligne par PM), liste blanche `ETIQUETTES` côté
   serveur sur les dix slugs du § 3.6. Le `PUT /pm/{code}/tags` reçoit
   **l'ensemble complet**, pas un ajout : le serveur déduit lui-même les poses et
   les retraits. C'est ce qui permet au client hors ligne de ne garder qu'un
   état par fiche au lieu d'une file ordonnée d'ajouts et de retraits, dont
   l'ordre de rejeu deviendrait la source de bugs. `GET /sync/meta` suit le même
   contrat incrémental que `/sync/positions` (`since`, `next_since`, `complete`,
   tombstones), avec un curseur commun aux deux familles.

   Côté téléphone, `MetaStore` écrit **local d'abord**, toujours, réseau ou non :
   le PM qu'on met un quart d'heure à trouver est justement celui du fond d'une
   zone sans couverture. Deux drapeaux « sale » par fiche, un par famille,
   tiennent la règle de résolution : tant qu'une pose locale n'est pas remontée,
   elle prime sur ce qui descend. Une étiquette inconnue reçue du serveur est
   conservée et réaffichée telle quelle plutôt qu'ignorée — c'est le sort d'une
   application pas encore mise à jour, et perdre une information du terrain
   serait pire que d'afficher un slug brut.

   **Point d'accès distinct** : deuxième couple de coordonnées facultatif sur la
   même ligne `pm_access`, avec son propre bouton « 🚗 Y aller (accès) ». Une
   seule des deux coordonnées est refusée des deux côtés (422 côté serveur,
   message côté app pour que ça vaille aussi hors ligne).

   **Photos.** Stockage adressable par contenu : le nom du fichier est le
   `sha256` de ses octets. La déduplication est gratuite, deux envois de la même
   photo ne coûtent qu'une ligne, un identifiant ne change jamais de contenu —
   donc un cache client sans invalidation à gérer, servi en `immutable`. Le
   format est reconnu aux octets magiques, pas au `Content-Type` déclaré, et les
   dimensions sont lues à la main (SOF JPEG, IHDR PNG) pour ne pas ajouter
   Pillow à l'image Docker. Lecture bornée à `MAX_BYTES + 1` : un client ne
   dicte pas la mémoire du serveur. Six photos par PM, 2 Mo chacune au plus.

   Côté téléphone, `PhotoStore` réduit à 1 600 px de côté et vise ~200 Ko par
   qualité JPEG dégressive — une qualité fixe donne 90 Ko sur un mur nu et
   600 Ko sur une haie. Le sous-échantillonnage précède le décodage (12 Mpx en
   pleine résolution réclament ~48 Mo de tas), l'orientation EXIF est appliquée
   aux pixels. La photo part en file d'attente sur-le-champ et l'envoi attend la
   synchro ; une fois envoyée, ses octets deviennent son cache, sans aller la
   retélécharger. Une photo refusée pour de bon (PM inconnu, quota, format)
   quitte la file : la garder ferait retenter le même envoi à chaque ouverture,
   sur le forfait de l'utilisateur.

   **Écart assumé au § 3.7** : la roadmap décrit « une photo du PM, une de la vue
   d'approche » comme deux prises de vue attendues. L'application propose deux
   boutons distincts (« 📷 Le PM », « 📷 L'accès », stockés `kind = pm | acces`)
   mais n'en rend aucune obligatoire : sur le terrain, imposer la seconde
   ferait surtout renoncer à la première.

   **Trou comblé hors roadmap** : les photos vivent dans un volume Docker, hors
   du dump SQL. Une base restaurée aurait pointé sur des fichiers absents. Le
   script de sauvegarde archive donc aussi `/photos`, mais seulement quand
   l'empreinte de l'ensemble a changé — inutile de recopier les mêmes octets
   toutes les nuits.

   **Visible partout, pas seulement dans la fiche** (« icône sur la carte et dans
   la liste, filtrables », § 3.6) : les icônes d'étiquettes et le 🔑 d'une
   indication d'accès apparaissent dans les listes Recherche et Autour et dans
   le dialogue des shelters ; la carte pose un anneau orange sur les PM signalés
   — un anneau plutôt qu'un pictogramme, pour ne pas masquer la couleur et la
   forme qui disent le statut de géolocalisation — borné à 150 groupes visibles.
   L'onglet Autour gagne un troisième filtre « 🙈 Signalés », qui ratisse 300 PM
   avant de filtrer : les PM signalés sont rares, filtrer les 25 plus proches
   n'en rendrait souvent aucun.
10. Interface : trois onglets, `? ⚙`, aide contextuelle, centralisation des
    couleurs puis thèmes *(§ 3.8)* — **fait**

    Les couleurs d'abord, comme prévu : `Theme.kt` porte quatre palettes (clair /
    sombre × normal / contraste élevé) et un `LocalCouleursPm` pour les teintes
    métier qui n'entrent dans aucun rôle Material — le vert d'une position relevée,
    le rouge d'un centre de zone, l'orange d'un avertissement, le bleu de « moi ».
    `BluePrimary`, `HelpBlueDark`, `PmExactColor`, `PmApproxColor` ont disparu au
    profit du thème ; les quatre noms d'appel de `MainActivity` survivent en
    propriétés `@Composable` pour ne pas réécrire dix-neuf sites d'un coup.

    La taille du texte passe par `LocalDensity`/`fontScale` et non par une
    `Typography` : la plupart des textes de l'app donnent leur taille en dur
    (`fontSize = 14.sp`) et une typographie Material les laisserait intacts.

    `Settings` (SharedPreferences, calqué sur `SessionStore`) est relu **avant**
    `setContent` : sinon l'application s'ouvrirait une fraction de seconde en clair
    chez quelqu'un qui a choisi le mode sombre. Un `values-night/themes.xml` évite
    le même éclair blanc au niveau de la fenêtre Android.

    L'onglet Compte (`InfoScreen`, 180 lignes) est supprimé, ses fonctions
    réparties dans les huit rubriques de `SettingsScreen`. Restent trois onglets et
    une `TopAppBar` commune portant `?` et `⚙`, le badge de mise à jour sur
    l'engrenage. Le `?` est contextuel : trois à quatre lignes sur l'écran courant
    et un bouton « ▲ Toute l'aide ».

    Écart assumé : la `TopAppBar` n'est ni masquée ni affinée sur l'onglet Carte.
    Elle y porte le titre de l'onglet et les deux boutons ; les masquer obligerait
    à refaire flotter `?` et `⚙` au-dessus de la carte, pour quarante pixels.

    Le réglage « Wi-Fi seulement » est branché (`DepStore.reseauAutorise`, utilisé
    par la vérification automatique et pas par une action lancée à la main), de
    même que le fond de carte par défaut, les libellés et la taille des points.

Le point 5 est celui auquel on ne pense pas et qui fait toute la différence : la
séance a défini précisément ce qu'on voudra stocker, autant poser les tables
pendant que la base est encore jetable.

---

## 6. Questions encore ouvertes

- ~~Retour arrière depuis un onglet secondaire~~ — **tranchée (point 7)** : retour
  à l'onglet Recherche, puis double pression pour quitter. Sur le terrain une
  pression de trop coûte un fix GPS et une saisie en cours.
- ~~Couleur des PM sur la carte~~ — **tranchée (point 8)** : vert pour une position
  relevée sur place, rouge pour un centre de zone ARCEP, et **la forme distingue
  autant que la couleur** (rond / carré) — environ 8 % des hommes confondent le
  vert et le rouge, et c'est justement la population du terrain. Le point bleu de
  « ma position » garde son halo blanc et passe au-dessus des PM.
- Migration SQLite/Room : à trancher seulement si le démarrage devient lent.
- `MainActivity.kt` fait 1 472 lignes. Si la refonte Paramètres ajoute 6 ou 7
  sous-écrans, Navigation Compose se justifiera ; les booléens + `BackHandler`
  suffisent d'ici là.

---

## 6 bis. Mise en production du 2026-09-19 (v1.2)

Les dix points de la section 5 sont livrés. Tout a été déployé le même jour et
vérifié depuis l'extérieur, pas seulement depuis le réseau local.

**Nom de version : 1.11 → 1.2, pas 1.12.** La 1.12 n'a jamais été distribuée, et
le saut de fonctions depuis la 1.11 méritait un numéro qui se voie. Le nom est
cosmétique : `versionCode` (6) est la seule valeur que l'app et le
`min_app_version` du manifeste comparent.

**Site.** `~/mapm-site/public/data/` : manifeste + 103 paquets, 8,5 Mo.
`https://mapm.online/data/manifest.json` répond 200 avec `Cache-Control:
no-cache` et un ETag, `deps/01.tgz` 200 en `application/gzip` avec
`max-age=3600`, et le sha256 servi est bien celui du manifeste — c'est-à-dire
que la vérification que fait `DepStore` avant d'extraire passe pour de vrai.
Le rechargement de nginx demande `nginx -s reload` : `docker compose up -d` ne
recrée pas le conteneur quand seul le **contenu** d'un fichier monté a changé,
si bien que la nouvelle configuration ne serait jamais relue.

**API.** Migration `001_gel_schema.sql` appliquée, puis l'import national a
tourné tout seul au démarrage : ZAPM 2026T2, 99 451 PM vus, **+91 526 en base**,
4 retirés, 9957 positions OSM amorcées. La base passe de 7 930 PM sur
7 départements à la France entière.

Deux écarts ont été trouvés en comparant le compose du dépôt à celui qui
tournait sur PsyOne, **avant** de déployer — c'est la comparaison qui les a
révélés, pas un test :

- `MAX_SESSIONS_PER_USER` avait disparu de la liste `environment:`. Le
  déploiement l'aurait retiré du conteneur et `auth.py` serait retombé sur son
  défaut de 2 : le troisième appareil d'un utilisateur évince le premier, sans
  rien signaler. La valeur avait justement été portée à 5.
- Le montage des paquets pointait vers `../site/public/data`, vrai dans le
  dépôt, faux en production où le site vit dans `~/mapm-site`. Docker crée un
  bind absent en **dossier vide** : l'import n'aurait rien trouvé et serait
  resté silencieux, ce qui est son comportement normal quand il n'y a pas de
  paquet — le genre de panne qui ne se voit qu'au moment où l'on se demande
  pourquoi la base n'a pas bougé. Le chemin passe désormais par
  `PACKAGES_DIR_HOST`, dont le défaut reste celui du dépôt.

**Sauvegardes.** Le volume `pmfibre_photos` est créé et accessible en écriture
par l'API. Le cron de 4h07 archive désormais les photos, mais seulement quand le
lot a changé.

**Page du site.** Elle annonçait une « base embarquée hors-ligne » qui n'existe
plus : depuis la 1.2 l'app démarre vide et télécharge les départements choisis.
Un visiteur qui installe sans le savoir croit à une application cassée.

Reste, hors production : `cp_normandie.json` toujours normand (recherche par
code postal limitée à la Normandie), et les noms de départements sans accents.

---

## 7. Conventions de travail

- Une session de travail **par lot**, pas par fichier : découper plus fin fait
  repayer la mise en contexte à chaque fois.
- Commencer chaque session par la lecture de ce document.
- Le tenir à jour : dans trois mois, la raison pour laquelle `MIN_MOVE_METERS` a
  été assoupli, ou pour laquelle « propriété privée » ne figure pas dans les
  étiquettes, doit se retrouver ici.
