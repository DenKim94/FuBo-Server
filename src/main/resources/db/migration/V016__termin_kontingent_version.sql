-- A15, Ergaenzung vom 22.10.2026: Das Generierungskontingent bekommt einen eigenen Zaehler.
--
-- Bis hierher war spieltag.termin.teilnehmer_version beides zugleich: der Beleg dafuer, dass
-- sich der Teilnehmerkreis geaendert hat (Veraltet-Kennzeichen einer Einteilung), und der
-- Schluessel, unter dem das Kontingent verbucht wird. Damit setzte jede Absage das Kontingent
-- zurueck - und ein Spieler, der absagt, verschaffte allen anderen weitere Laeufe.
--
-- Gewollt ist: Nur eine neue Zusage, eine Skillaenderung eines betroffenen Teilnehmers und die
-- Aenderung einer Gast-Stufe (beides durch den Admin) oeffnen das Kontingent wieder. Eine Absage
-- (auch das Sperren eines Profils, das Zusagen zuruecknimmt) tut es nicht. Das Veraltet-
-- Kennzeichen soll dagegen weiter bei JEDER Teilnehmeraenderung greifen - eine Einteilung, in
-- der ein Abgesagter steht, ist nicht mehr aktuell. Beides an einem Zaehler zu fuehren ist
-- nicht moeglich, deshalb zwei:
--
--   teilnehmer_version  steigt bei jeder Teilnehmeraenderung   -> Veraltet-Kennzeichen,
--                                                                 Gegenpruefung im Lauf
--   kontingent_version  steigt nur bei Zusage, Skill, Gast-Stufe -> Schluessel des Kontingents
--
-- kontingent_version wird nie ueber teilnehmer_version hinaus gezaehlt; sie ist eine
-- Teilmenge der Ausloeser, kein zweiter Stand desselben Zaehlers.

ALTER TABLE spieltag.termin
    ADD COLUMN kontingent_version INTEGER NOT NULL DEFAULT 0;

-- Bestehende Termine behalten ihren Kontingentstand: Die Schluessel in
-- spieltag.generierung_kontingent tragen bisher den Wert von teilnehmer_version. Ohne diesen
-- Schritt passte keine bestehende Zeile mehr, und jedes Kontingent stuende einmalig wieder
-- offen. version wird bewusst nicht fortgeschrieben: Die Spalte ist nicht an der Entity
-- gemappt, und im Betrieb laeuft waehrend der Migration kein Vorgang.
UPDATE spieltag.termin
   SET kontingent_version = teilnehmer_version;

-- Der Schluesselteil der Kontingentzeile heisst jetzt wie der Zaehler, aus dem er kommt.
-- Die Umbenennung ueberlebt in uq_kontingent: Ein Constraint verweist auf die Spalte, nicht
-- auf ihren Namen.
ALTER TABLE spieltag.generierung_kontingent
    RENAME COLUMN teilnehmer_version TO kontingent_version;
