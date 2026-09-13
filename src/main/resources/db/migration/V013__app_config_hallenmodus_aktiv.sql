-- A23, Ergaenzung vom 13.09.2026: Der Hallenmodus bekommt einen Hauptschalter.
--
-- Bisher liess sich nur ableiten, ob er in Gebrauch ist - etwa daran, ob eine halle_email
-- hinterlegt ist. Das ist keine Aussage, sondern ein Nebeneffekt: Wer die Adresse zum
-- Nachschlagen stehen lassen will, obwohl gerade keine Halle gebucht ist, haette sie loeschen
-- muessen. Ein eigenes Flag trennt "wir spielen zurzeit in einer Halle" von "wir wissen, an
-- wen wir uns wenden".
--
-- NOT NULL DEFAULT false: Die Einstellung ist aus, bis der Admin sie einschaltet. Eine
-- bestehende Installation bekommt damit denselben Ausgangszustand wie eine frische - und das
-- ist die sichere Richtung, denn der Endpunkt verschickt eine Mail an einen Aussenstehenden,
-- die sich nicht zuruecknehmen laesst. Ein Vorgabewert true haette den Hallenmodus bei allen
-- eingeschaltet, die ihn nie angefordert haben.
--
-- Nullbar waere hier falsch, anders als bei halle_email und halle_absage_vorlage: Ein
-- dreiwertiger Schalter kennt ein "unbekannt", das niemand beantworten koennte, und jede
-- Pruefung muesste sich entscheiden, wie sie NULL liest. A23 kennt nur an oder aus.
--
-- Der Name folgt der Vorgabe des Haupt-Entwicklers (hallen_modus_aktiv) und weicht damit vom
-- Praefix halle_ der drei bestehenden Spalten ab. Bewusst beibehalten: Die drei anderen sind
-- Angaben ueber die Halle, dies ist eine Aussage ueber den Modus.

ALTER TABLE configs.app_config
    ADD COLUMN hallen_modus_aktiv BOOLEAN NOT NULL DEFAULT false;
