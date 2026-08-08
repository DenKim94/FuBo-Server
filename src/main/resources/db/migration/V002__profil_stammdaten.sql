CREATE TABLE profil.skill_kategorie (
                                        id           BIGSERIAL    PRIMARY KEY,
                                        schluessel   VARCHAR(40)  NOT NULL,
                                        bezeichnung  VARCHAR(60)  NOT NULL,
                                        gewicht      NUMERIC(4,2) NOT NULL DEFAULT 1.00,
                                        reihenfolge  SMALLINT     NOT NULL,
                                        min_wert     SMALLINT     NOT NULL DEFAULT 0,
                                        max_wert     SMALLINT     NOT NULL DEFAULT 6,
                                        aktiv        BOOLEAN      NOT NULL DEFAULT TRUE,
                                        CONSTRAINT uq_skill_kategorie_schluessel  UNIQUE (schluessel),
                                        CONSTRAINT uq_skill_kategorie_reihenfolge UNIQUE (reihenfolge),
                                        CONSTRAINT ck_skill_kategorie_grenzen     CHECK (min_wert >= 0 AND max_wert >= min_wert),
                                        CONSTRAINT ck_skill_kategorie_gewicht     CHECK (gewicht >= 0)
);

CREATE TABLE profil.spieler (
                                id           BIGSERIAL   PRIMARY KEY,
                                name         VARCHAR(60) NOT NULL,
                                rolle        VARCHAR(10) NOT NULL DEFAULT 'USER',
                                aktiv        BOOLEAN     NOT NULL DEFAULT TRUE,
                                erstellt_am  TIMESTAMPTZ NOT NULL DEFAULT now(),
                                geaendert_am TIMESTAMPTZ NOT NULL DEFAULT now(),
                                version      BIGINT      NOT NULL DEFAULT 0,
                                CONSTRAINT uq_spieler_name  UNIQUE (name),
                                CONSTRAINT ck_spieler_rolle CHECK (rolle IN ('ADMIN', 'USER'))
);

-- Genau ein Admin, in der Datenbank erzwungen
CREATE UNIQUE INDEX uq_spieler_genau_ein_admin
    ON profil.spieler (rolle)
    WHERE rolle = 'ADMIN';

-- Deckt die Namensliste des Login-Dropdowns ab (nur aktive Profile)
CREATE INDEX ix_spieler_aktiv_name ON profil.spieler (name) WHERE aktiv;

CREATE TABLE profil.spieler_skill (
                                      id         BIGSERIAL   PRIMARY KEY,
                                      spieler_id BIGINT      NOT NULL,
                                      kategorie  VARCHAR(40) NOT NULL,
                                      wert       SMALLINT    NOT NULL,
                                      CONSTRAINT fk_spieler_skill_spieler FOREIGN KEY (spieler_id)
                                          REFERENCES profil.spieler (id) ON DELETE CASCADE,
                                      CONSTRAINT fk_spieler_skill_kategorie FOREIGN KEY (kategorie)
                                          REFERENCES profil.skill_kategorie (schluessel),
                                      CONSTRAINT uq_spieler_skill UNIQUE (spieler_id, kategorie),
                                      CONSTRAINT ck_spieler_skill_wert CHECK (wert BETWEEN 0 AND 6)
);

CREATE TABLE profil.gast_vorlage (
                                     id        BIGSERIAL   PRIMARY KEY,
                                     stufe     VARCHAR(10) NOT NULL,
                                     kategorie VARCHAR(40) NOT NULL,
                                     wert      SMALLINT    NOT NULL,
                                     CONSTRAINT fk_gast_vorlage_kategorie FOREIGN KEY (kategorie)
                                         REFERENCES profil.skill_kategorie (schluessel),
                                     CONSTRAINT uq_gast_vorlage       UNIQUE (stufe, kategorie),
                                     CONSTRAINT ck_gast_vorlage_stufe CHECK (stufe IN ('STARK', 'MITTEL', 'SCHWACH')),
                                     CONSTRAINT ck_gast_vorlage_wert  CHECK (wert BETWEEN 0 AND 6)
);

/**
 * Prueft, ob ein Skillwert im kategoriespezifischen Bereich liegt.
 * Ein CHECK kann nicht auf eine andere Tabelle zugreifen, daher ein Trigger.
 */
CREATE OR REPLACE FUNCTION profil.pruefe_skill_wertebereich()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
minWert SMALLINT;
    maxWert SMALLINT;
BEGIN
SELECT k.min_wert, k.max_wert
INTO minWert, maxWert
FROM profil.skill_kategorie k
WHERE k.schluessel = NEW.kategorie;

IF NOT FOUND THEN
        RAISE EXCEPTION 'Unbekannte Skillkategorie: %', NEW.kategorie
            USING ERRCODE = '23514';
END IF;

    IF NEW.wert < minWert OR NEW.wert > maxWert THEN
        RAISE EXCEPTION 'Wert % liegt ausserhalb des Bereichs %..% der Kategorie %',
                        NEW.wert, minWert, maxWert, NEW.kategorie
            USING ERRCODE = '23514';
END IF;

RETURN NEW;
END;
$$;

CREATE TRIGGER tr_spieler_skill_wertebereich
    BEFORE INSERT OR UPDATE ON profil.spieler_skill
                         FOR EACH ROW EXECUTE FUNCTION profil.pruefe_skill_wertebereich();

CREATE TRIGGER tr_gast_vorlage_wertebereich
    BEFORE INSERT OR UPDATE ON profil.gast_vorlage
                         FOR EACH ROW EXECUTE FUNCTION profil.pruefe_skill_wertebereich();