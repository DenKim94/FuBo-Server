package de.fubo.appserver.domain.push;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Der Inhalt einer Push-Benachrichtigung (A25b, S8 Abschnitte 8.5 und 9.3).
 *
 * <h2>Warum das kein DTO ist</h2>
 * Der Record liegt in {@code domain/push} und nicht in {@code dto/push}: {@code dto}
 * beschreibt die Ein- und Ausgabe <b>an der API-Grenze</b>. Diese Nachricht verlaesst den
 * Server auf dem anderen Weg - als nach RFC 8291 verschluesselter Rumpf an einen fremden
 * Push-Dienst. Sie erscheint deshalb auch nicht in {@code fubo-api.json}.
 *
 * <h2>Sie muss aus sich heraus anzeigbar sein</h2>
 * Der Service Worker darf sie <b>nicht</b> ueber einen API-Aufruf ergaenzen. Die Erinnerung
 * geht rund 24 Stunden vor dem Termin hinaus; die Sitzung des Empfaengers ist dann mit
 * Sicherheit abgelaufen (gleitendes 15-Minuten-Fenster, harte Obergrenze eine Stunde), und
 * der Aufruf lieferte {@code 401}.
 *
 * <p><b>Deshalb traegt sie beides:</b> einen fertigen Text zum Anzeigen ({@link #titel} und
 * {@link #text}) und die strukturierten Felder darunter. Grund fuer den Rueckfalltext ist
 * {@code registerType: 'prompt'} im Client - ein Nutzer kann das Update tagelang aufschieben,
 * sein Service Worker kennt einen spaeter eingefuehrten {@link #typ} dann nicht. Ohne
 * Rueckfalltext zeigte er nichts; und weil der Client beim Abonnieren
 * {@code userVisibleOnly: true} zusagt, blendet der Browser dann von sich aus eine generische
 * Meldung ein. Der Rueckfalltext sichert eine eingegangene Zusage ab.
 *
 * <h2>Die Formulierung steht hier und nicht in {@code configs.app_config}</h2>
 * Anders als die Absagevorlage des Hallenmodus (A23), und der Unterschied ist der Adressat:
 * Jene geht an einen Aussenstehenden in einer Sache, die der Admin verantwortet - der
 * Wortlaut gehoert ihm. Diese ist Oberflaechentext fuer die eigenen Nutzer; in der
 * Konfiguration stuende sie an einem zweiten Ort neben dem Rueckfalltext im Code, und zwei
 * Wahrheiten laufen auseinander. Zudem erschiene ein frei editierbarer Text im Namen der
 * Anwendung auf fremden Sperrbildschirmen.
 *
 * <h2>Inhaltsschranken wie an der API-Grenze</h2>
 * Keine Skillwerte, keine Zugangsdaten, <b>keine Namen Dritter</b>. Die Nutzlast ist nach
 * RFC 8291 Ende-zu-Ende verschluesselt, laeuft aber ueber fremde Server - und landet auf
 * einem Sperrbildschirm. Es gilt dieselbe Sparsamkeit.
 *
 * @param typ       Anlass; der Service Worker waehlt danach seine Darstellung
 * @param titel     Kopfzeile der Benachrichtigung, immer gefuellt
 * @param text      Rueckfalltext, immer gefuellt
 * @param terminId  betroffener Termin
 * @param datum     Datum des Termins in Ortszeit
 * @param uhrzeit   Uhrzeit des Termins in Ortszeit
 * @param ort       Spielort oder {@code null} (A18). <b>Die Spalte ist
 *                  {@code VARCHAR(160)}</b>, eine Kuerzung ist deshalb nicht noetig; die
 *                  Gesamtgrenze prueft die Verschluesselung
 * @param url       Ziel, das der Klick auf die Benachrichtigung oeffnet
 */
public record PushNutzlast(PushTyp typ,
                           String titel,
                           String text,
                           Long terminId,
                           LocalDate datum,
                           LocalTime uhrzeit,
                           String ort,
                           String url) {

    /**
     * Wochentag in Langform.
     *
     * <p><b>Das Sprachkennzeichen steht ausdruecklich hier</b> - ohne es naehme Java die
     * Voreinstellung des Rechners, und im Container ist das nicht verlaesslich Deutsch.
     * Dieselbe Festlegung wie im {@code MailService} aus S7.
     */
    private static final DateTimeFormatter WOCHENTAG =
            DateTimeFormatter.ofPattern("EEEE", Locale.GERMAN);

    /** Uhrzeit ohne Sekunden - sie stehen in der Spalte, sagen dem Empfaenger aber nichts. */
    private static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Die Erinnerung an eine offene Rueckmeldung (Anlass 1).
     *
     * <p>Der Titel nennt Wochentag und Uhrzeit. <b>Der Wochentag gehoert dazu</b>, obwohl das
     * Datum ihn enthaelt: Er ist die Angabe, an der ein Mensch einen Terminirrtum bemerkt -
     * und auf einem Sperrbildschirm ist er das Einzige, was ohne Nachdenken einordnet, ob
     * dieser Termin gemeint ist.
     *
     * @param terminId betroffener Termin
     * @param datum    Datum in Ortszeit
     * @param uhrzeit  Uhrzeit in Ortszeit
     * @param ort      Spielort oder {@code null}
     * @return die fertige Nutzlast
     */
    public static PushNutzlast erinnerung(Long terminId, LocalDate datum, LocalTime uhrzeit,
                                          String ort) {
        return new PushNutzlast(PushTyp.ERINNERUNG,
                "Training am %s, %s Uhr".formatted(datum.format(WOCHENTAG), uhrzeit.format(UHRZEIT)),
                "Bitte um Rückmeldung",
                terminId, datum, uhrzeit, ort, ziel(terminId));
    }

    /**
     * Die Absage eines Termins (Anlass 2).
     *
     * <p><b>Der Titel nennt den Wochentag, nicht die Uhrzeit</b> - anders als bei der
     * Erinnerung. Wer absagt, sagt den Tag ab; die Uhrzeit steht in den strukturierten
     * Feldern, falls die Oberflaeche sie braucht.
     *
     * @param terminId betroffener Termin
     * @param datum    Datum in Ortszeit
     * @param uhrzeit  Uhrzeit in Ortszeit
     * @param ort      Spielort oder {@code null}
     * @return die fertige Nutzlast
     */
    public static PushNutzlast terminAbgesagt(Long terminId, LocalDate datum, LocalTime uhrzeit,
                                              String ort) {
        return new PushNutzlast(PushTyp.TERMIN_ABGESAGT,
                "Training am %s fällt aus".formatted(datum.format(WOCHENTAG)),
                "Der Termin wurde abgesagt",
                terminId, datum, uhrzeit, ort, ziel(terminId));
    }

    /**
     * Das Klickziel der Benachrichtigung.
     *
     * <p><b>Ein Pfad und keine vollstaendige Adresse.</b> Der Service Worker loest ihn gegen
     * seinen eigenen Ursprung auf; eine absolute Adresse braeuchte die Frontend-Domain in der
     * Serverkonfiguration - ein zweiter Ort neben der CORS-Allowlist, und einer, der bei
     * einem Umzug still falsch wird.
     */
    private static String ziel(Long terminId) {
        return "/termine/" + terminId;
    }
}
