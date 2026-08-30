-- A20b: Bei ungerader Teilnehmerzahl wird ein Auswechselspieler gefuehrt. Wer das ist,
-- soll der Admin einstellen koennen - entweder der schwaechste Spieler des groesseren
-- Teams oder der zuletzt angemeldete Teilnehmer.
--
-- Erste Migration seit V008 (S2). S2b und S3 sind ohne Schemaaenderung ausgekommen; hier
-- geht es nicht anders, weil die Einstellung anwendungsweit gilt und damit in dieselbe
-- Zeile gehoert wie algorithm_type.

ALTER TABLE configs.app_config
    ADD COLUMN auswechsel_modus VARCHAR(24) NOT NULL DEFAULT 'SCHWAECHSTER_UEBERZAHL';

-- Der Default steht in der Spalte und nicht nur im Code: Die Zeile existiert bereits
-- (V007 legt sie an), und ohne DEFAULT scheiterte das ALTER an der NOT-NULL-Bedingung.
-- Zugleich ist damit die Vorgabe aus A20b an der Stelle festgehalten, an der sie auch
-- ein Blick in die Datenbank findet.

-- Dieselbe Absicherung wie ck_app_config_algo fuer algorithm_type: Die Anwendung prueft
-- ueber das Java-Enum, die Datenbank bleibt die letzte Instanz. Ein Schreibzugriff aus
-- psql oder einem spaeteren Skript soll keinen Wert hinterlassen koennen, den die
-- Anwendung beim Lesen nicht mehr abbilden kann.
ALTER TABLE configs.app_config
    ADD CONSTRAINT ck_app_config_auswechsel
        CHECK (auswechsel_modus IN ('SCHWAECHSTER_UEBERZAHL', 'ZULETZT_ANGEMELDET'));
