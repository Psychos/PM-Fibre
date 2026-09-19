# PM Fibre — Feuille de route

> Compte rendu de la séance de conception du 18-19 septembre 2026.
> **Aucun code n'a été écrit** : ce document fige les décisions avant de coder.
> À lire en premier par toute nouvelle session de travail.

---

## 1. Objectif de la prochaine étape

Couvrir **toute la France** au lieu de la seule Normandie, **avant la diffusion de
l'application**. La raison est décisive : une fois l'app entre les mains des
utilisateurs, les changements de schéma de base et de format de données se font sur
une base vivante, à la main, sans filet.

---

## 2. Volumétrie mesurée

| | Normandie (actuel) | France entière |
|---|---|---|
| PM | 4 930 | **99 268** |
| Départements | 5 | 103 (DOM/COM compris) |
| `pm_full.json` | 832 Ko | 16,6 Mo |
| `zones_*.json` | 1,05 Mo | ~21 Mo (extrapolé) |

Détail Normandie : Seine-Maritime 1 872 · Calvados 1 080 · Eure 758 · Manche 616 ·
Orne 604. Positions exactes d'origine OSM (`p=1`) : 325.

**Moyenne par département** : ~960 PM ≈ 160 Ko de PM + 200 Ko de zones, soit
**~100 Ko gzippés**. Le plus gros département (Nord, 3 545 PM) ≈ 1,3 Mo brut,
~350 Ko gzippés.

Conclusion : le découpage par département est très léger. Pas besoin de deltas,
un paquet complet par département suffit.

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

1. `dep_code` partout, générateur de paquets par département, manifeste
   (version du jeu de données, `max_deps`, `min_app_version`)
2. Import serveur national **versionné** en remplacement de `_import_pm_if_empty`,
   et mise en place d'un vrai mécanisme de migration *(§ 4.2, § 4.3)*
3. `geo.py` : zones par département, chargées à la demande *(§ 4.7)*
4. **Synchro : filtre par département, `since`, tombstones** — supprimer la
   dépendance au `limit=10000` avant qu'elle n'efface les positions de quelqu'un
   *(§ 4.1)*
5. **Gel de schéma** : `method` sur les positions, table des étiquettes,
   indication d'accès, table des photos, point d'accès — vides, en attendant leur
   interface *(§ 4.3, § 4.4)*
6. App : données hors assets, écran des départements, vérification de mise à jour

### Ensuite, sans urgence

7. Confort : `BackHandler` + fiche en surimpression *(§ 4.9)* — une demi-heure,
   indépendant de tout le reste
8. Carte : points ronds, `SimpleFastPointOverlay`, affichage par zone visible,
   seuil de zoom, fond ortho IGN, cercle d'incertitude *(§ 3.9, § 4.8)*
9. Terrain : photos, indication d'accès en tête de fiche avec cache hors ligne,
   étiquettes, deux modes de capture GPS *(§ 3.5, § 3.6, § 3.7)*
10. Interface : trois onglets, `? ⚙`, aide contextuelle, centralisation des
    couleurs puis thèmes *(§ 3.8)*

Le point 5 est celui auquel on ne pense pas et qui fait toute la différence : la
séance a défini précisément ce qu'on voudra stocker, autant poser les tables
pendant que la base est encore jetable.

---

## 6. Questions encore ouvertes

- Retour arrière depuis un onglet secondaire : ramener à l'onglet principal
  (convention Android) ou quitter directement ? Prévoir « appuyez à nouveau pour
  quitter » sur l'onglet racine.
- Couleur des PM sur la carte une fois les pins remplacés, sans entrer en conflit
  avec le point bleu « ma position ».
- Migration SQLite/Room : à trancher seulement si le démarrage devient lent.
- `MainActivity.kt` fait 1 472 lignes. Si la refonte Paramètres ajoute 6 ou 7
  sous-écrans, Navigation Compose se justifiera ; les booléens + `BackHandler`
  suffisent d'ici là.

---

## 7. Conventions de travail

- Une session de travail **par lot**, pas par fichier : découper plus fin fait
  repayer la mise en contexte à chaque fois.
- Commencer chaque session par la lecture de ce document.
- Le tenir à jour : dans trois mois, la raison pour laquelle `MIN_MOVE_METERS` a
  été assoupli, ou pour laquelle « propriété privée » ne figure pas dans les
  étiquettes, doit se retrouver ici.
