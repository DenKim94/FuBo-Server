-- S2: In der Stufe PIN_VERIFIED ist noch keine Identitaet und damit auch keine Rolle
-- gewaehlt. V003 hat profil.session.rolle als NOT NULL angelegt; der zweistufige Login
-- aus A14 laesst sich damit nicht abbilden - das Anlegen einer Sitzung nach der
-- PIN-Pruefung wuerde an der Spalte scheitern.
--
-- ck_session_identitaet aus V003 sieht den Fall bereits vor (stage = 'PIN_VERIFIED'
-- ohne spieler_id und ohne gast_name); die Rolle wurde dort uebersehen.

ALTER TABLE profil.session
    ALTER COLUMN rolle DROP NOT NULL;

-- Hinweis: ck_session_rolle aus V003 bleibt unveraendert gueltig. Ein CHECK schlaegt
-- nur bei FALSE fehl, nicht bei UNKNOWN - und "NULL IN ('ADMIN','USER','GAST')" ergibt
-- UNKNOWN. Die bestehende Bedingung laesst NULL also bereits zu.

-- Umkehrung: Ab PROFILE_AUTHENTICATED ist die Rolle Pflicht. Ohne diese Bedingung
-- koennte eine Sitzung die zweite Stufe ohne Rolle erreichen; die Filterchain bildet
-- Rolle und Stufe auf eine Authority ab (S2_UMSETZUNG.md, Abschnitt 5.2) und haette
-- dann keinen Wert, aus dem sie ROLE_USER, ROLE_ADMIN oder ROLE_GAST ableiten kann.
ALTER TABLE profil.session
    ADD CONSTRAINT ck_session_rolle_stage CHECK (
        stage <> 'PROFILE_AUTHENTICATED' OR rolle IS NOT NULL
    );
