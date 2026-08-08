-- Genau ein Admin-Konto (Singleton), verknuepft mit dem Admin-Profil
CREATE TABLE profil.admin_konto (
                                    id                    SMALLINT     PRIMARY KEY DEFAULT 1,
                                    spieler_id            BIGINT       NOT NULL,
                                    passwort_hash         VARCHAR(72)  NOT NULL,
                                    email                 VARCHAR(120) NOT NULL,
                                    passwort_geaendert_am TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                    version               BIGINT       NOT NULL DEFAULT 0,
                                    CONSTRAINT ck_admin_konto_singleton CHECK (id = 1),
                                    CONSTRAINT uq_admin_konto_spieler   UNIQUE (spieler_id),
                                    CONSTRAINT fk_admin_konto_spieler   FOREIGN KEY (spieler_id) REFERENCES profil.spieler (id)
);

-- Zentrale PIN, genau eine Zeile, ausschliesslich als BCrypt-Hash
CREATE TABLE profil.zugangsdaten (
                                     id            SMALLINT    PRIMARY KEY DEFAULT 1,
                                     pin_hash      VARCHAR(72) NOT NULL,
                                     geaendert_am  TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     geaendert_von SMALLINT,
                                     version       BIGINT      NOT NULL DEFAULT 0,
                                     CONSTRAINT ck_zugangsdaten_singleton CHECK (id = 1),
                                     CONSTRAINT fk_zugangsdaten_admin FOREIGN KEY (geaendert_von) REFERENCES profil.admin_konto (id)
);

CREATE TABLE profil.session (
                                id                   BIGSERIAL   PRIMARY KEY,
                                token_hash           CHAR(64)    NOT NULL,
                                spieler_id           BIGINT,
                                gast_name            VARCHAR(40),
                                gast_stufe           VARCHAR(10),
                                rolle                VARCHAR(10) NOT NULL,
                                stage                VARCHAR(24) NOT NULL,
                                erstellt_am          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                letzte_aktivitaet_am TIMESTAMPTZ NOT NULL DEFAULT now(),
                                gueltig_bis          TIMESTAMPTZ NOT NULL,
                                absolut_gueltig_bis  TIMESTAMPTZ NOT NULL,
                                widerrufen_am        TIMESTAMPTZ,
                                CONSTRAINT uq_session_token   UNIQUE (token_hash),
                                CONSTRAINT fk_session_spieler FOREIGN KEY (spieler_id) REFERENCES profil.spieler (id),
                                CONSTRAINT ck_session_rolle   CHECK (rolle IN ('ADMIN', 'USER', 'GAST')),
                                CONSTRAINT ck_session_stage   CHECK (stage IN ('PIN_VERIFIED', 'PROFILE_AUTHENTICATED')),
                                CONSTRAINT ck_session_stufe   CHECK (gast_stufe IS NULL OR gast_stufe IN ('STARK','MITTEL','SCHWACH')),
    -- In der Stufe PIN_VERIFIED ist noch keine Identitaet gewaehlt
                                CONSTRAINT ck_session_identitaet CHECK (
                                    stage = 'PIN_VERIFIED'
                                        OR spieler_id IS NOT NULL
                                        OR gast_name  IS NOT NULL
                                    )
);

-- A6: Belegtstatus der Namen ergibt sich aus den aktiven Sitzungen
CREATE INDEX ix_session_aktiv
    ON profil.session (spieler_id)
    WHERE widerrufen_am IS NULL;

CREATE TABLE profil.gast_slot (
                                  id            SMALLINT    PRIMARY KEY,
                                  belegt        BOOLEAN     NOT NULL DEFAULT FALSE,
                                  session_id    BIGINT,
                                  anzeige_name  VARCHAR(40) NOT NULL,
                                  belegt_seit   TIMESTAMPTZ,
                                  version       BIGINT      NOT NULL DEFAULT 0,
                                  CONSTRAINT fk_gast_slot_session FOREIGN KEY (session_id) REFERENCES profil.session (id),
                                  CONSTRAINT uq_gast_slot_session UNIQUE (session_id),
                                  CONSTRAINT ck_gast_slot_belegung CHECK (
                                      (belegt AND session_id IS NOT NULL AND belegt_seit IS NOT NULL)
                                          OR (NOT belegt AND session_id IS NULL)
                                      )
);

CREATE TABLE profil.passwort_reset (
                                       id                  BIGSERIAL   PRIMARY KEY,
                                       pin_hash            VARCHAR(72) NOT NULL,
                                       erstellt_am         TIMESTAMPTZ NOT NULL DEFAULT now(),
                                       gueltig_bis         TIMESTAMPTZ NOT NULL,
                                       versuche            SMALLINT    NOT NULL DEFAULT 0,
                                       verbraucht_am       TIMESTAMPTZ,
                                       angefordert_von_ip  VARCHAR(45) NOT NULL,
                                       CONSTRAINT ck_passwort_reset_versuche CHECK (versuche BETWEEN 0 AND 5)
);

CREATE INDEX ix_passwort_reset_ip ON profil.passwort_reset (angefordert_von_ip, erstellt_am);

CREATE TABLE profil.audit_log (
                                  id                  BIGSERIAL   PRIMARY KEY,
                                  zeitpunkt           TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  akteur_spieler_id   BIGINT,
                                  akteur_bezeichnung  VARCHAR(60) NOT NULL,
                                  aktion              VARCHAR(50) NOT NULL,
                                  entitaet            VARCHAR(40),
                                  entitaet_id         BIGINT,
                                  details             JSONB,
                                  CONSTRAINT fk_audit_log_spieler FOREIGN KEY (akteur_spieler_id) REFERENCES profil.spieler (id)
);

CREATE INDEX ix_audit_log_zeitpunkt ON profil.audit_log (zeitpunkt DESC);