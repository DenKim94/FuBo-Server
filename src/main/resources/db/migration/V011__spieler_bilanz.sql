-- A21 (Ergaenzung des Haupt-Entwicklers vom 30.08.2026): Je Spieler wird mitgezaehlt, wie oft
-- er gewonnen, verloren und unentschieden gespielt hat. Umgesetzt wird der Zaehler in S6
-- zusammen mit der Ergebniserfassung; die Spalten entstehen jetzt, damit die Migration nicht
-- mitten in S6 zwischen zwei Arbeitspaketen liegt.
--
-- Bis dahin stehen ueberall Nullen. Das ist kein Zwischenzustand, den jemand reparieren
-- muesste: Es hat noch kein Spiel ein Ergebnis, weil es den Endpunkt dafuer nicht gibt.

ALTER TABLE profil.spieler
    ADD COLUMN anz_siege         INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN anz_niederlagen   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN anz_unentschieden INTEGER NOT NULL DEFAULT 0;

-- INTEGER und nicht SMALLINT: Die uebrigen Zaehler des Schemas sind SMALLINT, weil sie
-- Obergrenzen haben (22 Teilnehmer, 22 Gastplaetze). Dieser hier hat keine - er waechst mit
-- jedem gespielten Termin und ist nach oben offen. Bei woechentlichem Spiel reichte SMALLINT
-- ueber 600 Jahre, aber ein Zaehler, dessen Grenze man ausrechnen muss, ist die falsche Wahl.

-- Nicht-negativ, weil ein negativer Zaehler nur durch einen Fehler entstehen kann. Die
-- Umsetzung in S6 berechnet die Werte bei jeder Ergebnisaenderung neu, statt sie
-- fortzuschreiben (Entscheidung vom 30.08.2026) - ein Unterlauf ist damit ausgeschlossen und
-- die Bedingung ist ein Riegel gegen eine spaetere Umstellung auf +1/-1, nicht gegen den
-- geplanten Weg.
ALTER TABLE profil.spieler
    ADD CONSTRAINT ck_spieler_bilanz CHECK (
        anz_siege >= 0 AND anz_niederlagen >= 0 AND anz_unentschieden >= 0
    );

-- Bewusst KEINE Bedingung, die die Summe gegen die Zahl der Teilnahmen prueft. Sie waere
-- nicht als CHECK formulierbar (CHECK darf keine andere Tabelle lesen), und als Trigger
-- braeche sie bei jedem Zwischenschritt einer Neuberechnung.
--
-- Bewusst auch keine Spalten fuer Gaeste: Ein Gast hat keine Zeile in profil.spieler, er
-- erscheint in spieltag.teilnahme nur als gast_name. Ein Gastplatz wird ausserdem
-- wiederverwendet - ein Zaehler an gast_slot summierte die Ergebnisse verschiedener
-- Personen. Gastteilnahmen bleiben deshalb ohne Bilanz; die Ergebnisse der Termine sind
-- davon unberuehrt.
