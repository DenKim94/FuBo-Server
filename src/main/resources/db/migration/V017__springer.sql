-- A20b, Aenderung vom 03.11.2026: Springer statt Auswechselspieler.
--
-- Bis hierher wies das groessere Team bei ungerader Teilnehmerzahl einen Auswechselspieler
-- aus - den schwaechsten (Vorgabe) oder den zuletzt angemeldeten. Die Wahl war eine
-- Anzeigeregel: Sie aenderte die Einteilung nicht und wurde deshalb nie gespeichert, sondern
-- beim Lesen mit dem *heute* eingestellten Modus neu bestimmt.
--
-- Jetzt gilt: Ein Springer wird VOR der Einteilung bestimmt - zufaellig (neue Vorgabe) oder
-- der zuletzt angemeldete -, die uebrigen Teilnehmer werden zu zwei gleich grossen Teams
-- ausgeglichen, und der Springer beginnt beim schwaecheren. Er wechselt das Team, sobald
-- sein aktuelles Team ein Tor schiesst. Damit haengt die Einteilung am Springer, und ein
-- beim Lesen neu bestimmter Springer passte nach einem Moduswechsel nicht mehr zu der
-- Einteilung, die ohne ihn gerechnet wurde. Er wird deshalb mit dem Lauf gespeichert.
--
-- Ein Thema, zwei Tabellen: Beide Schritte gehoeren zu derselben fachlichen Aenderung und
-- ergeben nur zusammen einen lauffaehigen Stand.

-- 1. Der Modus: SCHWAECHSTER_UEBERZAHL entfaellt, ZUFAELLIG ist die neue Vorgabe.
--    Erst die alte Pruefung entfernen, dann umschreiben, dann die neue setzen - in dieser
--    Reihenfolge, weil die alte Pruefung den neuen Wert ablehnt und die neue den alten.
--    version wird fortgeschrieben: Die Spalte ist an der Entity gemappt (@Version), und ein
--    vor der Migration geoeffnetes Formular soll nicht still ueber den neuen Stand schreiben.
ALTER TABLE configs.app_config
    DROP CONSTRAINT ck_app_config_auswechsel;

UPDATE configs.app_config
   SET auswechsel_modus = 'ZUFAELLIG',
       version          = version + 1
 WHERE auswechsel_modus = 'SCHWAECHSTER_UEBERZAHL';

ALTER TABLE configs.app_config
    ALTER COLUMN auswechsel_modus SET DEFAULT 'ZUFAELLIG';

ALTER TABLE configs.app_config
    ADD CONSTRAINT ck_app_config_auswechsel
        CHECK (auswechsel_modus IN ('ZUFAELLIG', 'ZULETZT_ANGEMELDET'));

-- 2. Der Springer eines Laufs. Er steht als gewoehnliche Zuteilung in dem Team, bei dem er
--    beginnt; die Spalte markiert ihn. Bestehende Zeilen bekommen false: Fuer Laeufe von vor
--    dieser Aenderung gibt es keinen Springer, und ein nachtraeglich geratener waere eine
--    Behauptung ueber ein Spiel, das anders eingeteilt wurde.
ALTER TABLE spieltag.team_zuteilung
    ADD COLUMN springer BOOLEAN NOT NULL DEFAULT FALSE;

-- Hoechstens ein Springer je Lauf. Ein partieller Unique-Index und kein CHECK: Die Bedingung
-- spannt mehrere Zeilen, und genau das kann nur ein Index durchsetzen.
CREATE UNIQUE INDEX uq_team_zuteilung_springer
    ON spieltag.team_zuteilung (generierung_id)
    WHERE springer;
