CREATE TABLE spieltag.terminserie (
                                      id           BIGSERIAL   PRIMARY KEY,
                                      titel        VARCHAR(80) NOT NULL,
                                      wochentag    SMALLINT    NOT NULL,
                                      uhrzeit      TIME        NOT NULL,
                                      startdatum   DATE        NOT NULL,
                                      enddatum     DATE        NOT NULL,
                                      ort          VARCHAR(160),
                                      angelegt_von BIGINT,
                                      angelegt_am  TIMESTAMPTZ NOT NULL DEFAULT now(),
                                      version      BIGINT      NOT NULL DEFAULT 0,
                                      CONSTRAINT fk_terminserie_spieler  FOREIGN KEY (angelegt_von) REFERENCES profil.spieler (id),
                                      CONSTRAINT ck_terminserie_wochentag CHECK (wochentag BETWEEN 1 AND 7),
                                      CONSTRAINT ck_terminserie_zeitraum  CHECK (enddatum > startdatum)
);

CREATE TABLE spieltag.termin (
                                 id                 BIGSERIAL   PRIMARY KEY,
                                 serie_id           BIGINT,
                                 datum              DATE        NOT NULL,
                                 uhrzeit            TIME        NOT NULL,
                                 ort                VARCHAR(160),
                                 status             VARCHAR(20) NOT NULL DEFAULT 'GEPLANT',
                                 teilnehmer_version INTEGER     NOT NULL DEFAULT 0,
                                 teams_fixiert      BOOLEAN     NOT NULL DEFAULT FALSE,
                                 version            BIGINT      NOT NULL DEFAULT 0,
                                 CONSTRAINT fk_termin_serie  FOREIGN KEY (serie_id) REFERENCES spieltag.terminserie (id),
                                 CONSTRAINT uq_termin_zeit   UNIQUE (datum, uhrzeit),
                                 CONSTRAINT ck_termin_status CHECK (status IN ('GEPLANT', 'ABGESAGT', 'ABGESCHLOSSEN'))
);

CREATE TABLE spieltag.teilnahme (
                                    id          BIGSERIAL   PRIMARY KEY,
                                    termin_id   BIGINT      NOT NULL,
                                    spieler_id  BIGINT,
                                    gast_name   VARCHAR(40),
                                    gast_stufe  VARCHAR(10),
                                    zusage      BOOLEAN     NOT NULL,
                                    gemeldet_am TIMESTAMPTZ NOT NULL DEFAULT now(),
                                    version     BIGINT      NOT NULL DEFAULT 0,
                                    CONSTRAINT fk_teilnahme_termin  FOREIGN KEY (termin_id) REFERENCES spieltag.termin (id) ON DELETE CASCADE,
                                    CONSTRAINT fk_teilnahme_spieler FOREIGN KEY (spieler_id) REFERENCES profil.spieler (id),
                                    CONSTRAINT uq_teilnahme_spieler UNIQUE (termin_id, spieler_id),
                                    CONSTRAINT ck_teilnahme_akteur  CHECK (
                                        (spieler_id IS NOT NULL AND gast_name IS NULL)
                                            OR (spieler_id IS NULL AND gast_name IS NOT NULL)
                                        ),
                                    CONSTRAINT ck_teilnahme_stufe CHECK (gast_stufe IS NULL OR gast_stufe IN ('STARK','MITTEL','SCHWACH'))
);

-- Gaeste haben spieler_id = NULL; NULL-Werte gelten im Unique-Index als verschieden,
-- daher zusaetzlich ein partieller Index auf den Gastnamen.
CREATE UNIQUE INDEX uq_teilnahme_gast
    ON spieltag.teilnahme (termin_id, gast_name)
    WHERE gast_name IS NOT NULL;

-- Reihenfolge der Warteschlange wird abgeleitet, nicht gespeichert
CREATE INDEX ix_teilnahme_reihenfolge ON spieltag.teilnahme (termin_id, gemeldet_am, id);

CREATE INDEX ix_termin_serie ON spieltag.termin (serie_id)
    WHERE serie_id IS NOT NULL;