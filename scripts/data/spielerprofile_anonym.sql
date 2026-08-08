-- =============================================================================
-- spielerprofile_anonym.sql
--
-- 30 Profile mit den REALEN Skillwerten und ANONYMISIERTEN Namen.
-- Enthaelt keine personenbezogenen Daten und darf eingecheckt werden.
-- Ablage: scripts/data/spielerprofile_anonym.sql
--
-- Zweck: realistische Datengrundlage fuer die Arbeit am Teamgenerator
--        (Skillverteilung, Laufzeit von EXHAUSTIV bei 22 Teilnehmern, Guete der
--        Balance). Fuer diese Fragen tragen die echten Namen nichts bei.
--
-- Anonymisierung: Die Namen sind durch 'Spieler 01' bis 'Spieler 30' ersetzt und
-- die Zeilenreihenfolge ist gemischt, damit sich aus der Position kein Rueckschluss
-- auf die Quelldatei ziehen laesst. Der Ausreisser -1 (Angriff) ist bereits auf 0
-- korrigiert.
--
-- Bewusst KEINE Flyway-Migration: Der Datensatz soll nicht in jedem Testlauf
-- geladen werden. Fuer den automatischen Dev/Test-Seed dient der kleinere Satz
-- db/demodata/R__seed_beispielprofile.sql (12 Profile).
--
-- Anwendung:  ./scripts/seed-lokal.sh scripts/data/spielerprofile_anonym.sql
-- =============================================================================

BEGIN;

CREATE TEMP TABLE anonym (
    name          VARCHAR(60) PRIMARY KEY,
    angriff       INTEGER NOT NULL,
    verteidigung  INTEGER NOT NULL,
    spielstaerke  INTEGER NOT NULL,
    laufstaerke   INTEGER NOT NULL,
    torwart       INTEGER NOT NULL
) ON COMMIT DROP;

INSERT INTO anonym (name, angriff, verteidigung, spielstaerke, laufstaerke, torwart) VALUES
    ('Spieler 01',  2,  2,  2,  1,  0),
    ('Spieler 02',  4,  3,  3,  3,  0),
    ('Spieler 03',  2,  2,  3,  1,  3),
    ('Spieler 04',  2,  3,  2,  3,  1),
    ('Spieler 05',  4,  3,  4,  3,  0),
    ('Spieler 06',  3,  2,  3,  3,  0),
    ('Spieler 07',  3,  4,  5,  4,  0),
    ('Spieler 08',  5,  2,  3,  3,  0),
    ('Spieler 09',  3,  4,  2,  5,  0),
    ('Spieler 10',  4,  2,  4,  4,  0),
    ('Spieler 11',  4,  4,  5,  4,  0),
    ('Spieler 12',  0,  2,  0,  1,  0),
    ('Spieler 13',  6,  6,  6,  4,  0),
    ('Spieler 14',  3,  2,  2,  4,  0),
    ('Spieler 15',  3,  4,  2,  5,  0),
    ('Spieler 16',  5,  4,  5,  4,  0),
    ('Spieler 17',  2,  3,  2,  3,  0),
    ('Spieler 18',  2,  4,  3,  4,  0),
    ('Spieler 19',  3,  5,  4,  5,  0),
    ('Spieler 20',  3,  4,  4,  4,  0),
    ('Spieler 21',  2,  3,  4,  2,  0),
    ('Spieler 22',  1,  1,  1,  1,  1),
    ('Spieler 23',  4,  5,  5,  4,  0),
    ('Spieler 24',  3,  2,  3,  2,  0),
    ('Spieler 25',  1,  1,  2,  1,  0),
    ('Spieler 26',  4,  3,  3,  3,  0),
    ('Spieler 27',  3,  4,  4,  4,  0),
    ('Spieler 28',  2,  2,  1,  2,  1),
    ('Spieler 29',  3,  3,  4,  3,  1),
    ('Spieler 30',  3,  4,  3,  4,  1);

INSERT INTO profil.spieler (name, rolle, aktiv)
SELECT a.name, 'USER', TRUE
  FROM anonym a
    ON CONFLICT (name) DO NOTHING;

INSERT INTO profil.spieler_skill (spieler_id, kategorie, wert)
SELECT s.id, k.kategorie, k.wert
  FROM anonym a
  JOIN profil.spieler s ON s.name = a.name
 CROSS JOIN LATERAL (
        VALUES ('ANGRIFF',      a.angriff),
               ('VERTEIDIGUNG', a.verteidigung),
               ('SPIELSTAERKE', a.spielstaerke),
               ('LAUFSTAERKE',  a.laufstaerke),
               ('TORWART',      a.torwart)
 ) AS k (kategorie, wert)
    ON CONFLICT (spieler_id, kategorie) DO NOTHING;

COMMIT;
