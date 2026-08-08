-- =============================================================================
-- R__seed_beispielprofile.sql
--
-- Anonymisierter Beispieldatensatz: 12 Profile ohne Personenbezug.
-- Diese Location (db/demodata) wird NUR ueber src/test/resources/application.yml
-- bzw. ein dev-Profil eingebunden. Die Produktionskonfiguration kennt nur
-- classpath:db/migration und sieht diese Datei nie.
--
-- Warum "R__" (repeatable) statt "V0xx__" (versioned)?
--   Eine versionierte Migration mit hoher Nummer wuerde spaetere Schema-
--   migrationen in Entwicklungsdatenbanken blockieren: Flyway fuehrt per Default
--   keine Migration aus, deren Version unter der hoechsten bereits angewendeten
--   liegt (out-of-order). Wiederholbare Migrationen laufen dagegen immer NACH
--   allen versionierten und nehmen an der Versionsreihenfolge nicht teil.
--   Voraussetzung ist Idempotenz - hier ueber ON CONFLICT DO NOTHING erfuellt,
--   denn eine wiederholbare Migration laeuft bei jeder Pruefsummenaenderung neu.
--
-- Abdeckung der Testdaten (bewusst gewaehlt, nicht aus Realdaten abgeleitet):
--   - Feldkategorien nutzen den vollen Bereich 0 bis 6
--   - Torwart nutzt den vollen Bereich 0 bis 3 (die Realdaten enthalten nur 0/1)
--   - alle Kategoriesummen sind gerade, eine perfekte Aufteilung ist moeglich
--   - 12 Profile: ueber der Mindestteilnehmerzahl, teilbar in 2x6; fuer Tests
--     mit ungerader Anzahl genuegt es, 11 davon zuzusagen
-- =============================================================================

BEGIN;

CREATE TEMP TABLE beispiel (
    name          VARCHAR(60) PRIMARY KEY,
    angriff       INTEGER NOT NULL,
    verteidigung  INTEGER NOT NULL,
    spielstaerke  INTEGER NOT NULL,
    laufstaerke   INTEGER NOT NULL,
    torwart       INTEGER NOT NULL
) ON COMMIT DROP;

INSERT INTO beispiel (name, angriff, verteidigung, spielstaerke, laufstaerke, torwart) VALUES
    ('Beispielspieler 01', 6, 4, 5, 4, 0),
    ('Beispielspieler 02', 5, 5, 4, 5, 1),
    ('Beispielspieler 03', 4, 3, 5, 3, 0),
    ('Beispielspieler 04', 4, 4, 3, 4, 3),
    ('Beispielspieler 05', 3, 5, 4, 4, 0),
    ('Beispielspieler 06', 3, 3, 3, 3, 2),
    ('Beispielspieler 07', 3, 2, 4, 5, 0),
    ('Beispielspieler 08', 2, 4, 2, 3, 1),
    ('Beispielspieler 09', 2, 2, 3, 2, 0),
    ('Beispielspieler 10', 1, 3, 2, 4, 3),
    ('Beispielspieler 11', 1, 1, 1, 1, 0),
    ('Beispielspieler 12', 0, 2, 0, 2, 0);

-- 1) Profile anlegen; bereits vorhandene Namen bleiben unangetastet.
INSERT INTO profil.spieler (name, rolle, aktiv)
SELECT b.name, 'USER', TRUE
  FROM beispiel b
    ON CONFLICT (name) DO NOTHING;

-- 2) Skillwerte anlegen. Der Join laeuft ueber profil.spieler und erfasst damit
--    sowohl neu angelegte als auch bereits vorhandene Profile.
INSERT INTO profil.spieler_skill (spieler_id, kategorie, wert)
SELECT s.id, k.kategorie, k.wert
  FROM beispiel b
  JOIN profil.spieler s ON s.name = b.name
 CROSS JOIN LATERAL (
        VALUES ('ANGRIFF',      b.angriff),
               ('VERTEIDIGUNG', b.verteidigung),
               ('SPIELSTAERKE', b.spielstaerke),
               ('LAUFSTAERKE',  b.laufstaerke),
               ('TORWART',      b.torwart)
 ) AS k (kategorie, wert)
    ON CONFLICT (spieler_id, kategorie) DO NOTHING;

COMMIT;
