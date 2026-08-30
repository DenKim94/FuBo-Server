-- A23: Die Absagevorlage stand seit V004 auf NULL. Ein leeres Textfeld im Adminformular
-- ist eine schlechte Vorgabe - es verlangt, dass sich jemand unter Zeitdruck einen
-- hoeflichen Absagebrief ausdenkt, und zwar genau dann, wenn ohnehin gerade etwas
-- schiefgegangen ist. Ein brauchbarer Ausgangstext ist besser als keiner.
--
-- Der Text traegt echte Umlaute, anders als die Kommentare in dieser Datei: Er geht als
-- E-Mail an einen Aussenstehenden, und dort waere "muessen wir" ein Schreibfehler. V007
-- haelt es genauso. Die Spalte ist TEXT in einer UTF-8-Datenbank, Flyway liest UTF-8.
--
-- Die Spalte bleibt NULL-faehig. Das Leeren muss moeglich bleiben: Es ist die
-- Begruendung, mit der /admin/config/aendern ueberhaupt als Voll-Update angelegt wurde.

ALTER TABLE configs.app_config
    ALTER COLUMN halle_absage_vorlage SET DEFAULT
'Sehr geehrte Damen und Herren,

leider müssen wir unseren gebuchten Hallentermin absagen.
Wir bitten Sie, die Buchung zu stornieren, und entschuldigen uns für die Unannehmlichkeiten.

Mit freundlichen Grüßen

--- Dies ist eine automatisch generierte Nachricht. Bitte nicht antworten. ---';

-- SET ... = DEFAULT statt einer zweiten Textkopie: Der Wortlaut steht damit genau einmal
-- in dieser Datei. Zwei Kopien koennten bei einer spaeteren Korrektur auseinanderlaufen,
-- und die Abweichung faele niemandem auf - eine frische Installation bekaeme dann einen
-- anderen Text als eine bestehende.
--
-- WHERE ... IS NULL ist die eigentliche Absicherung: Hat ein Admin bereits einen Text
-- geschrieben, bleibt er unangetastet. Eine Migration, die eine gepflegte Eingabe
-- ueberschreibt, waere Datenverlust ohne Vorwarnung.
UPDATE configs.app_config
   SET halle_absage_vorlage = DEFAULT
 WHERE id = 1
   AND halle_absage_vorlage IS NULL;

-- Der Text nennt Datum, Uhrzeit und Ort bewusst nicht (Entscheidung vom 30.08.2026).
-- Diese Angaben schreibt S7 in den Betreff und einen Datenblock ueber die Vorlage. Mit
-- Platzhaltern im Text braeuchte es eine Ersetzung samt festgeschriebener Syntax, und bis
-- die existiert, stuenden die Klammern woertlich in der Mail beim Hallenbetreiber.
--
-- Die eckige Klammer in der Signatur bleibt sichtbar. Ein erfundener Vereinsname waere
-- ein installationsabhaengiger Wert in einer Migration - genau das, was die Regel
-- "Migrationen enthalten keine installationsabhaengigen Daten" ausschliesst.
