-- A23, S7: Der Server braucht einen ablesbaren Zustand dafuer, ob die Absage an den
-- Hallenbetreiber schon hinaus ist. Er entscheidet drei Dinge auf einmal: den Schutz gegen
-- den Doppelversand, die Anzeige in der Einzelansicht des Termins und die
-- Nachvollziehbarkeit ueber die Loeschfrist des Audit-Logs hinaus.
--
-- Warum nicht das Audit-Log als Quelle: Es wird nach 30 Tagen geloescht
-- (fubo.audit.aufbewahrung-tage) - ein Termin, der weiter in der Zukunft liegt, verloere
-- seinen Zustand, waehrend er noch bevorsteht. Dazu kommt die Rollenverteilung: Ein Eintrag
-- belegt eine vollzogene Aenderung, er ist Beleg und nicht Zustand. Wer ihn als Zustand
-- liest, hat eine zweite Wahrheit ohne Constraint.
--
-- NULL heisst "nicht abgesagt" und ist der Normalzustand jedes Termins - deshalb nullbar und
-- ohne Vorgabewert. Dieselbe Form wie ergebnis.korrigiert_am aus V011, und aus demselben
-- Grund kein zusaetzlicher boolean: Der Zeitpunkt beantwortet beide Fragen, zwei Spalten
-- koennten auseinanderlaufen.
--
-- Kein Index: Die Spalte wird nur je Termin gelesen, nie gesucht.
--
-- Die Spalte ist kein Zaehler und kein Protokoll. Ein zweiter Versand - falls er je erlaubt
-- wuerde - ueberschriebe den Wert. Wer wann welchen Text verschickt hat, steht im Audit-Log:
-- Die Tabelle sagt, wie es ist, das Protokoll, wie es dazu kam.

ALTER TABLE spieltag.termin
    ADD COLUMN halle_abgesagt_am TIMESTAMPTZ;
