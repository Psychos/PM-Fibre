#!/usr/bin/env bash
# Banc d'essai : la MÊME image que la production, mais sur une base jetable.
#
# Pourquoi : éprouver un correctif d'API demande de créer des comptes, de
# déplacer des positions, de déposer des photos — autant d'écritures partagées
# par toute l'équipe. Les faire en production abîmerait des données de terrain,
# et s'y connecter consommerait une session d'un vrai utilisateur (cf. la règle
# absolue du CLAUDE.md). Le banc donne les mêmes endpoints sur une copie.
#
#   bash banc_essai.sh monte     recrée la base d'essai et démarre le conteneur
#   bash banc_essai.sh demonte   supprime le conteneur et la base
#
# La base `pmfibre_essai` est restaurée depuis la dernière sauvegarde vérifiée,
# ce qui éprouve aussi la restauration au passage. Le conteneur écoute sur
# 127.0.0.1:8099 : fermé au LAN, inconnu du tunnel, donc injoignable de
# l'extérieur. Il partage le réseau et le volume photos de la production —
# seule la base diffère, et c'est elle qui porte les données.
#
# À exécuter sur PsyOne, depuis n'importe où : le script se place dans ~/pmfibre.
set -u
cd ~/pmfibre
DUMP=$(ls -t backups/*.sql.gz | head -1)
RP=$(grep -E "^DB_ROOT_PASSWORD=" .env | cut -d= -f2-)

case "${1:-monte}" in
monte)
  docker rm -f pmfibre-essai >/dev/null 2>&1 || true
  docker exec -i pmfibre-db mariadb -uroot -p"$RP" -e \
    "DROP DATABASE IF EXISTS pmfibre_essai;
     CREATE DATABASE pmfibre_essai CHARACTER SET utf8mb4;
     GRANT ALL ON pmfibre_essai.* TO 'pmfibre'@'%';" 2>/dev/null
  echo "base d'essai creee depuis $DUMP"
  zcat "$DUMP" | docker exec -i pmfibre-db mariadb -uroot -p"$RP" pmfibre_essai

  # `--env-file .env` d'abord, puis les surcharges : la base et le port sont
  # les deux seules choses qui doivent differer de la production.
  docker run -d --name pmfibre-essai --network pmfibre_pmnet \
    --env-file .env \
    -e DB_HOST=db -e DB_PORT=3306 -e DB_NAME=pmfibre_essai \
    -e PHOTOS_DIR=/photos \
    -v "$PWD/db/migrations:/app/migrations:ro" \
    -v "$(grep -E '^PACKAGES_DIR_HOST=' .env | cut -d= -f2-):/packages:ro" \
    -p 127.0.0.1:8099:8000 \
    pmfibre-api:latest >/dev/null
  for _ in $(seq 1 30); do
    if curl -sf http://127.0.0.1:8099/health >/dev/null; then break; fi
    sleep 1
  done
  echo "banc : $(curl -s http://127.0.0.1:8099/health)"
  ;;
demonte)
  docker rm -f pmfibre-essai >/dev/null 2>&1 || true
  docker exec -i pmfibre-db mariadb -uroot -p"$RP" \
    -e "DROP DATABASE IF EXISTS pmfibre_essai;" 2>/dev/null
  echo "banc demonte, base d'essai supprimee"
  ;;
*)
  echo "usage : banc_essai.sh [monte|demonte]" >&2
  exit 2
  ;;
esac
