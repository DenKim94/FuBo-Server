#!/usr/bin/env bash
# =============================================================================
# scripts/seed-lokal.sh
#
# Spielt einen Profildatensatz in die lokale Entwicklungsdatenbank ein.
# Das Skript selbst enthaelt keine Daten und darf eingecheckt werden.
#
# Aufruf (Beispiel):
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

# -----------------------------------------------------------------------------
# Die .env wird gelesen, nicht ausgefuehrt.
#
# Frueher stand hier "set -a; source .env; set +a". Das unterstellt der Datei
# Shell-Syntax, die sie nicht hat: Sie wird von der Anwendung ueber
# spring.config.import als Java-Properties-Datei gelesen, und Docker Compose
# bringt fuer --env-file seinen eigenen Parser mit. Beide nehmen alles nach dem
# ersten "=" woertlich; die Shell nicht.
#
# Aufgefallen ist es an SMTP_ABSENDER: Die erlaubte Form
# "Anzeigename <adresse@domain>" laesst die Shell ueber das "<" stolpern
# ("syntax error near unexpected token `newline'"). Anfuehrungszeichen sind
# keine Loesung - sie waeren fuer den Properties-Reader Teil des Werts und
# braechten die Absenderpruefung in MailConfig zu Fall.
#
# Der laute Fall ist dabei der harmlosere. Ein Passwort mit "$" oder einem
# Backtick wuerde beim Sourcen still expandiert; das Skript verbaende sich dann
# mit einem falschen Wert und meldete einen Authentifizierungsfehler, dessen
# Ursache niemand in der .env sucht.
#
# Gelesen wird deshalb nur, was dieses Skript braucht - ohne Interpretation.
# -----------------------------------------------------------------------------

# Liefert den Wert eines Schluessels aus der .env; erster Treffer gewinnt, wie
# beim Properties-Reader. Das abschliessende \r faengt eine unter Windows
# gespeicherte Datei ab - sonst endete der Benutzername auf einem
# Steuerzeichen, und psql meldete eine Rolle, die es "nicht gibt".
env_wert() {
    awk -v schluessel="$1" '
        index($0, schluessel "=") == 1 {
            sub(/^[^=]*=/, "")
            sub(/\r$/, "")
            print
            exit
        }
    ' .env
}

# Pflichtangabe aus der .env holen oder mit benennender Meldung abbrechen.
env_pflicht() {
    local wert
    wert="$(env_wert "$1")"
    if [[ -z "$wert" ]]; then
        echo "Fehler: $1 fehlt in der .env oder ist leer." >&2
        exit 1
    fi
    printf '%s' "$wert"
}

DB_USER="$(env_pflicht DB_USER)"
DB_NAME="$(env_pflicht DB_NAME)"

SEED_DATEI="${1:-$(env_wert FUBO_LOCAL_SEED)}"

if [[ -z "$SEED_DATEI" ]]; then
    echo "Fehler: Keine Seed-Datei angegeben." >&2
    echo "  Entweder FUBO_LOCAL_SEED in der .env setzen" >&2
    echo "  oder den Pfad als Argument uebergeben." >&2
    exit 1
fi

# Die Tilde von Hand aufloesen. Beim frueheren "source" erledigte das die Shell
# im Rahmen der Zuweisung; ein gelesener Wert ist dagegen reiner Text. Ein Pfad
# als Argument ist davon nicht betroffen - dort hat die aufrufende Shell die
# Tilde bereits ersetzt.
if [[ "$SEED_DATEI" == "~/"* ]]; then
    SEED_DATEI="$HOME/${SEED_DATEI#\~/}"
elif [[ "$SEED_DATEI" == "~" ]]; then
    SEED_DATEI="$HOME"
fi

if [[ ! -f "$SEED_DATEI" ]]; then
    echo "Fehler: Datei nicht gefunden: $SEED_DATEI" >&2
    exit 1
fi

# --env-file gibt Compose die Werte fuer ${DB_USER} und ${DB_PASSWORD} aus
# compose.dev.yml. Frueher standen sie durch das "set -a" in der Umgebung;
# jetzt liest Compose sie selbst - mit seinem eigenen Parser, der wie der
# Properties-Reader keine Shell-Syntax kennt.
COMPOSE=(docker compose -f compose.dev.yml --env-file .env)

if ! "${COMPOSE[@]}" ps --status running --quiet fubo-db-dev | grep -q .; then
    echo "Fehler: Container fubo-db-dev laeuft nicht." >&2
    echo "  Start: docker compose -f compose.dev.yml --env-file .env up -d" >&2
    exit 1
fi

echo "Importiere: $SEED_DATEI"

# ON_ERROR_STOP=1 ist zwingend: ohne das Flag laeuft psql nach einem Fehler
# weiter und hinterlaesst einen halb importierten Zustand.
"${COMPOSE[@]}" exec -T fubo-db-dev \
    psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" < "$SEED_DATEI"

echo
echo "Bestand nach dem Import:"
"${COMPOSE[@]}" exec -T fubo-db-dev \
    psql -qtA -U "$DB_USER" -d "$DB_NAME" -c \
    "SELECT 'Profile:      ' || count(*) FROM profil.spieler
     UNION ALL
     SELECT 'Skillwerte:   ' || count(*) FROM profil.spieler_skill;"
