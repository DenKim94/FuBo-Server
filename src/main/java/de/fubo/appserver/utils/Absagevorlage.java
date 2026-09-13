package de.fubo.appserver.utils;

/**
 * Der Fliesstext der Hallenabsage und sein Ersatz (A23, S7 Abschnitt 4.2).
 *
 * <h2>Warum es einen Ersatz gibt</h2>
 * {@code configs.app_config.halle_absage_vorlage} ist nullbar <b>und bleibt es</b>: Das Leeren
 * muss moeglich sein, und genau daran haengt die Begruendung, aus der
 * {@code /admin/config/aendern} ueberhaupt ein Voll-Update ist. Ein Endpunkt, der die leere
 * Vorlage ablehnt, naehme diese Moeglichkeit stillschweigend zurueck.
 *
 * <p>Versendet wird trotzdem nie ohne Fliesstext (Vorgabe des Haupt-Entwicklers vom
 * 13.09.2026). <b>Eine Nachricht aus Betreff und Datenblock waere zwar eine vollstaendige
 * Absage</b> - sie nennt Termin, Ort und die Absicht -, aber sie geht an einen Aussenstehenden,
 * und dort ist die Hoeflichkeit kein Beiwerk. Der Ersatz springt ein, die gepflegte Vorlage hat
 * immer Vorrang.
 *
 * <h2>Der Text steht ein zweites Mal - das ist bekannt und abgesichert</h2>
 * Wortgleich liegt er als Spaltenvorgabe in {@code V010}, von wo ihn jede frische Installation
 * bekommt. <b>Zwei Kopien koennen auseinanderlaufen</b>, und die Abweichung faele niemandem auf:
 * Eine bestehende Installation schickte den einen Text, eine neue den anderen. Migrationen sind
 * unveraenderlich, der Wortlaut laesst sich dort also nicht nachziehen - und den Vorgabewert zur
 * Laufzeit aus {@code information_schema} zu lesen waere ein Datenbankzugriff fuer eine
 * Konstante.
 *
 * <p><b>Deshalb bewacht ein Testfall die Gleichheit</b> ({@code HallenmodusTests}): Er liest den
 * Spaltenvorgabewert aus der Datenbank und vergleicht ihn mit {@link #ERSATZ}. Wer den Text hier
 * aendert, ohne eine Migration nachzulegen, faellt damit auf.
 *
 * <h2>Warum in {@code utils}</h2>
 * Ein zustandsloser Helfer ohne Spring-Abhaengigkeit, und er wird an zwei Stellen gebraucht: im
 * Absagepfad und im Lesepfad der Konfiguration, wo das Feld {@code halleAbsageVorlageEffektiv}
 * dem Admin zeigt, welcher Text tatsaechlich hinausginge. <b>Beide muessen dieselbe Antwort
 * geben</b> - stuende die Entscheidung an zwei Orten, zeigte das Formular den einen Text und der
 * Betreiber bekaeme den anderen.
 */
public final class Absagevorlage {

    /**
     * Der Ersatztext, wortgleich zur Spaltenvorgabe aus {@code V010}.
     *
     * <p><b>Echte Umlaute</b>, anders als in Kommentaren und Commit-Nachrichten: Der Text geht
     * als E-Mail an einen Aussenstehenden, dort waere "muessen wir" ein Schreibfehler.
     *
     * <p>Er nennt Datum, Uhrzeit und Ort <b>nicht</b> (Festlegung vom 30.08.2026). Die schreibt
     * der Absagepfad in Betreff und Datenblock; Platzhalter im Text braeuchten eine
     * Ersetzungssyntax und stuenden bis dahin woertlich in der Mail beim Hallenbetreiber.
     *
     * <p>Der abschliessende Gegenschraegstrich nimmt die letzte Zeilenschaltung zurueck: Der
     * Spaltenvorgabewert endet ohne sie, und der bewachende Testfall vergleicht zeichengenau.
     */
    public static final String ERSATZ = """
            Sehr geehrte Damen und Herren,

            leider müssen wir unseren gebuchten Hallentermin absagen.
            Wir bitten Sie, die Buchung zu stornieren, und entschuldigen uns für die Unannehmlichkeiten.

            Mit freundlichen Grüßen

            --- Dies ist eine automatisch generierte Nachricht. Bitte nicht antworten. ---\
            """;

    /** Reine Konstantenklasse; es gibt nichts zu erzeugen. */
    private Absagevorlage() {
    }

    /**
     * Liefert den Fliesstext, der tatsaechlich verwendet wird.
     *
     * <p><b>{@code isBlank} und nicht {@code isEmpty}</b>: Eine Vorlage aus lauter Leerzeichen
     * ist fuer den Empfaenger dasselbe wie keine, und sie entsteht leicht - ein Textfeld, aus dem
     * jemand den Inhalt loescht, bleibt selten wirklich leer.
     *
     * @param gepflegt der gespeicherte Wert aus {@code configs.app_config}; darf {@code null} sein
     * @return der gepflegte Text oder {@link #ERSATZ}, wenn keiner hinterlegt ist
     */
    public static String wirksam(String gepflegt) {
        return gepflegt == null || gepflegt.isBlank() ? ERSATZ : gepflegt;
    }
}
