#!/usr/bin/env bash
# =============================================================================
# scripts/seed-lokal.sh
#
# Spielt einen Profildatensatz in die lokale Entwicklungsdatenbank ein.
# Das Skript selbst enthaelt keine Daten und darf eingecheckt werden.
#
# Aufruf:
#   ./scripts/seed-lokal.sh
#       -> nutzt den Pfad aus FUBO_LOCAL_SEED (.env), typischerweise die
#          lokale Datei mit realen Namen ausserhalb des Projektordners
#
#   ./scripts/seed-lokal.sh scripts/data/spielerprofile_anonym.sql
#       -> nutzt den uebergebenen Pfad (30 Profile, anonymisiert)
#
# Voraussetzung: Die Anwendung wurde mindestens einmal gestartet, damit Flyway
# das Schema und profil.skill_kategorie angelegt hat. Sonst greifen die
# Fremdschluessel von profil.spieler_skill ins Leere.
#
# Alle mitgelieferten Importdateien sind idempotent (ON CONFLICT DO NOTHING);
# ein wiederholter Aufruf ist unschaedlich.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
    echo "Fehler: .env nicht gefunden (Vorlage: .env.example)." >&2
    exit 1
fi

# .env einlesen; set -a exportiert alle dabei gesetzten Variablen automatisch
set -a
# shellcheck disable=SC1091
source .env
set +a

SEED_DATEI="${1:-${FUBO_LOCAL_SEED:-}}"

if [[ -z "$SEED_DATEI" ]]; then
    echo "Fehler: Keine Seed-Datei angegeben." >&2
    echo "  Entweder FUBO_LOCAL_SEED in der .env setzen" >&2
    echo "  oder den Pfad als Argument uebergeben." >&2
    exit 1
fi

if [[ ! -f "$SEED_DATEI" ]]; then
    echo "Fehler: Datei nicht gefunden: $SEED_DATEI" >&2
    exit 1
fi

if ! docker compose -f compose.dev.yml ps --status running --quiet fubo-db-dev | grep -q .; then
    echo "Fehler: Container fubo-db-dev laeuft nicht." >&2
    echo "  Start: docker compose -f compose.dev.yml --env-file .env up -d" >&2
    exit 1
fi

echo "Importiere: $SEED_DATEI"

# ON_ERROR_STOP=1 ist zwingend: ohne das Flag laeuft psql nach einem Fehler
# weiter und hinterlaesst einen halb importierten Zustand.
docker compose -f compose.dev.yml exec -T fubo-db-dev \
    psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" < "$SEED_DATEI"

echo
echo "Bestand nach dem Import:"
docker compose -f compose.dev.yml exec -T fubo-db-dev \
    psql -qtA -U "$DB_USER" -d "$DB_NAME" -c \
    "SELECT 'Profile:      ' || count(*) FROM profil.spieler
     UNION ALL
     SELECT 'Skillwerte:   ' || count(*) FROM profil.spieler_skill;"
