-- A22, Ergaenzung vom 04.10.2026: Die Aenderung des Admin-Anmeldenamens wird ueber eine
-- Bestaetigungs-PIN per E-Mail bestaetigt. Der Name wird erst uebernommen, wenn die PIN
-- eingeloest ist - bis dahin steht er hier, am Vorgang.
--
-- Warum ueberhaupt eine Bestaetigung: Der Anmeldename ist das einzige Anmeldemerkmal, das der
-- Passwort-Reset nicht zurueckholt. Wer an einer offenen Adminsitzung sitzt, koennte den Admin
-- sonst mit einer einzigen Umbenennung dauerhaft aussperren.

-- ---------------------------------------------------------------------------------------
-- profil.name_aenderung - offene und abgeschlossene Anforderungen
-- ---------------------------------------------------------------------------------------
--
-- Eine EIGENE Tabelle und nicht profil.passwort_reset mit einer Spalte "zweck" (Entscheidung
-- des Haupt-Entwicklers vom 04.10.2026). Mit gemeinsamer Tabelle braeuchten vier der sechs
-- Reset-Abfragen (entwerten, anlegen, Versuch zaehlen, drosseln) eine Bedingung auf den
-- Zweck; eine vergessene machte die PINs der beiden Vorgaenge austauschbar - eine
-- Reset-PIN loeste dann eine Umbenennung ein, und das Anfordern des einen entwertete den
-- offenen anderen. Getrennt ist das baulich ausgeschlossen, und der Reset-Pfad bleibt
-- unberuehrt.
--
-- Der Aufbau folgt profil.passwort_reset aus V003, mit einer zusaetzlichen Spalte:
-- neuer_name traegt den gewuenschten Namen. Die PIN bestaetigt GENAU ihn - die Nachricht
-- nennt ihn, und der Bestaetigungsaufruf nimmt keinen Namen entgegen.
--
-- Gespeichert wird ausschliesslich der BCrypt-Hash der PIN; ein Datenbankabzug verraet sie
-- nicht.
CREATE TABLE profil.name_aenderung (
                                       id                 BIGSERIAL   PRIMARY KEY,
                                       pin_hash           VARCHAR(72) NOT NULL,
                                       neuer_name         VARCHAR(60) NOT NULL,
                                       erstellt_am        TIMESTAMPTZ NOT NULL DEFAULT now(),
                                       gueltig_bis        TIMESTAMPTZ NOT NULL,
                                       versuche           SMALLINT    NOT NULL DEFAULT 0,
                                       verbraucht_am      TIMESTAMPTZ,
                                       angefordert_von_ip VARCHAR(45) NOT NULL,
                                       CONSTRAINT ck_name_aenderung_versuche CHECK (versuche BETWEEN 0 AND 5)
);

-- neuer_name hat die Breite von profil.spieler.name (V002). Breiter waere sinnlos: Der Name
-- muss beim Einloesen in diese Spalte passen. Schmaler waere ein Fehler: Ein am DTO
-- zugelassener Name scheiterte hier mit einem 500.
--
-- Kein Fremdschluessel auf profil.spieler: Es gibt genau ein Adminprofil, und der Vorgang
-- benennt es nicht - er benennt den kuenftigen Namen.
--
-- ck_name_aenderung_versuche bindet fubo.reset.max-versuche auch fuer diesen Vorgang an
-- hoechstens 5 - dieselbe Kopplung wie ck_passwort_reset_versuche. Der Zaehler steigt nur
-- unter "versuche < :maxVersuche"; ohne diese Bedingung liefe der sechste Versuch in eine
-- Constraint-Verletzung statt in eine saubere Ablehnung.
--
-- Kein version-Feld: Die Tabelle wird nur angehaengt und bedingt aktualisiert, nie geladen
-- und zurueckgeschrieben - dasselbe Bild wie bei passwort_reset.

-- Drosselung: hoechstens drei Anforderungen je Stunde und Adresse. Der Index deckt die
-- Zaehlabfrage genau ab, wie ix_passwort_reset_ip.
CREATE INDEX ix_name_aenderung_ip ON profil.name_aenderung (angefordert_von_ip, erstellt_am);
