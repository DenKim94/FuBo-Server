CREATE TABLE spieltag.team_generierung (
                                           id                     BIGSERIAL    PRIMARY KEY,
                                           termin_id              BIGINT       NOT NULL,
                                           teilnehmer_version     INTEGER      NOT NULL,
                                           seed                   BIGINT       NOT NULL,
                                           erzeugt_von_spieler_id BIGINT,
                                           erzeugt_von_bezeichnung VARCHAR(60) NOT NULL,
                                           erzeugt_am             TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                           abgeloest_am           TIMESTAMPTZ,
                                           differenz_teamstaerke  NUMERIC(6,2),
                                           CONSTRAINT fk_team_generierung_termin  FOREIGN KEY (termin_id) REFERENCES spieltag.termin (id) ON DELETE CASCADE,
                                           CONSTRAINT fk_team_generierung_spieler FOREIGN KEY (erzeugt_von_spieler_id) REFERENCES profil.spieler (id)
);

CREATE INDEX ix_team_generierung_aktuell
    ON spieltag.team_generierung (termin_id, erzeugt_am DESC)
    WHERE abgeloest_am IS NULL;

CREATE TABLE spieltag.team_zuteilung (
                                         id             BIGSERIAL    PRIMARY KEY,
                                         generierung_id BIGINT       NOT NULL,
                                         teilnahme_id   BIGINT       NOT NULL,
                                         team           CHAR(1)      NOT NULL,
                                         score_snapshot NUMERIC(6,2) NOT NULL,
                                         skills_snapshot JSONB       NOT NULL,
                                         CONSTRAINT fk_team_zuteilung_generierung FOREIGN KEY (generierung_id)
                                             REFERENCES spieltag.team_generierung (id) ON DELETE CASCADE,
                                         CONSTRAINT fk_team_zuteilung_teilnahme FOREIGN KEY (teilnahme_id)
                                             REFERENCES spieltag.teilnahme (id) ON DELETE CASCADE,
                                         CONSTRAINT uq_team_zuteilung UNIQUE (generierung_id, teilnahme_id),
                                         CONSTRAINT ck_team_zuteilung_team CHECK (team IN ('A', 'B'))
);

CREATE TABLE spieltag.generierung_kontingent (
                                                 id                  BIGSERIAL PRIMARY KEY,
                                                 termin_id           BIGINT    NOT NULL,
                                                 akteur_spieler_id   BIGINT,
                                                 akteur_gast_slot_id SMALLINT,
                                                 teilnehmer_version  INTEGER   NOT NULL,
                                                 anzahl              SMALLINT  NOT NULL DEFAULT 0,
                                                 CONSTRAINT fk_kontingent_termin   FOREIGN KEY (termin_id) REFERENCES spieltag.termin (id) ON DELETE CASCADE,
                                                 CONSTRAINT fk_kontingent_spieler  FOREIGN KEY (akteur_spieler_id) REFERENCES profil.spieler (id),
                                                 CONSTRAINT fk_kontingent_gastslot FOREIGN KEY (akteur_gast_slot_id) REFERENCES profil.gast_slot (id),
                                                 CONSTRAINT uq_kontingent UNIQUE NULLS NOT DISTINCT
                                                     (termin_id, akteur_spieler_id, akteur_gast_slot_id, teilnehmer_version),
                                                 CONSTRAINT ck_kontingent_anzahl CHECK (anzahl >= 0),
                                                 CONSTRAINT ck_kontingent_akteur CHECK (
                                                     (akteur_spieler_id IS NOT NULL AND akteur_gast_slot_id IS NULL)
                                                         OR (akteur_spieler_id IS NULL AND akteur_gast_slot_id IS NOT NULL)
                                                     )
);

CREATE TABLE spieltag.ergebnis (
                                   id                      BIGSERIAL   PRIMARY KEY,
                                   termin_id               BIGINT      NOT NULL,
                                   sieger                  CHAR(1)     NOT NULL,
                                   deutlich                BOOLEAN     NOT NULL DEFAULT FALSE,
                                   erfasst_von_spieler_id  BIGINT,
                                   erfasst_von_bezeichnung VARCHAR(60) NOT NULL,
                                   erfasst_am              TIMESTAMPTZ NOT NULL DEFAULT now(),
                                   korrigiert_am           TIMESTAMPTZ,
                                   version                 BIGINT      NOT NULL DEFAULT 0,
                                   CONSTRAINT fk_ergebnis_termin  FOREIGN KEY (termin_id) REFERENCES spieltag.termin (id) ON DELETE CASCADE,
                                   CONSTRAINT fk_ergebnis_spieler FOREIGN KEY (erfasst_von_spieler_id) REFERENCES profil.spieler (id),
                                   CONSTRAINT uq_ergebnis_termin  UNIQUE (termin_id),
                                   CONSTRAINT ck_ergebnis_sieger  CHECK (sieger IN ('A', 'B', 'U'))
);