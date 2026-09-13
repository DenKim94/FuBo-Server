package de.fubo.appserver.service.mail;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Versendet die Nachrichten der Anwendung. Es sind zwei: die
 * Bestaetigungs-PIN beim Zuruecksetzen des Admin-Passworts (A22) und die Absage des
 * Hallentermins an den Betreiber (A23, S7).
 *
 * <p><b>Die zweite ist die erste, die das Projekt verlaesst.</b> Die Bestaetigungs-PIN geht an
 * den Admin; die Hallenabsage geht an einen Fremden, und sie laesst sich nicht zuruecknehmen.
 * Deshalb steht vor ihrem Versand jede Pruefung des {@code HallenService}, und deshalb traegt
 * ihr Text echte Umlaute.
 *
 * <h2>Warum {@link SimpleMailMessage} und kein HTML</h2>
 * Es gibt nichts zu formatieren, keine Bilder und keinen Grund, ein Mailprogramm eine
 * fuenfstellige Zahl in ein Layout setzen zu lassen, das sie unlesbar macht. Reiner Text
 * kommt ueberall gleich an.
 *
 * <h2>Warum eine PIN zum Abtippen und kein Link</h2>
 * Ein Link muesste ein Geheimnis in der URL tragen. URLs landen in Browserverlaeufen,
 * Proxy-Protokollen und Vorschaudiensten, die Links automatisch oeffnen - Letzteres
 * verbrauchte den Vorgang, bevor der Admin ihn ueberhaupt sieht. Ausserdem braeuchte ein
 * Link ein Frontend-Routing, das es fuer diesen Zweck nicht gibt.
 *
 * <h2>Zur Fehlerbehandlung</h2>
 * Ein Fehlschlag wird in einen {@link FachlicherFehler} mit
 * {@link Fehlercode#VERSAND_FEHLGESCHLAGEN} ({@code 503}) uebersetzt. Da der Aufrufer
 * innerhalb einer Transaktion arbeitet, rollt der gespeicherte Vorgang damit zurueck - es
 * bleibt keine PIN in der Datenbank, die niemand bekommen hat. Die urspruengliche Meldung
 * des Mailservers geht ins Log und nicht zum Aufrufer: Sie nennt Rechnernamen und
 * Kontodaten.
 */
@Service
public class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

    private static final String BETREFF_RESET = "FuBo – Zurücksetzen des Admin-Passworts";

    /** Wochentag in Langform; das Sprachkennzeichen steht ausdruecklich hier (S7, 4.3). */
    private static final DateTimeFormatter WOCHENTAG =
            DateTimeFormatter.ofPattern("EEEE", Locale.GERMAN);

    /** Datum in der Schreibweise, die ein deutschsprachiger Empfaenger erwartet. */
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Uhrzeit ohne Sekunden - sie stehen in der Spalte, sagen dem Empfaenger aber nichts. */
    private static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm");

    private final JavaMailSender versender;
    private final String absender;

    public MailService(JavaMailSender versender, FuboProperties eigenschaften) {
        this.versender = versender;
        this.absender = eigenschaften.mail().absender();
    }

    /**
     * Schickt die Bestaetigungs-PIN an die hinterlegte Adresse des Admins.
     *
     * <p>Der Text nennt ausdruecklich, was zu tun ist, wenn niemand den Reset angefordert
     * hat: Dann kennt jemand die zentrale PIN, der sie nicht kennen sollte - der Hinweis
     * ist der einzige Weg, wie der Admin davon erfaehrt.
     *
     * @param empfaenger         Zieladresse aus {@code admin_konto.email}
     * @param pin                fuenfstellige Bestaetigungs-PIN im Klartext; der einzige
     *                           Moment, in dem sie ausserhalb der Erzeugung existiert
     * @param gueltigkeitMinuten Lebensdauer des Vorgangs, fuer den Text
     * @throws FachlicherFehler {@code 503}, wenn der Versand scheitert
     */
    public void sendeBestaetigungsPin(String empfaenger, String pin, int gueltigkeitMinuten) {
        SimpleMailMessage nachricht = new SimpleMailMessage();
        nachricht.setFrom(absender);
        nachricht.setTo(empfaenger);
        nachricht.setSubject(BETREFF_RESET);
        nachricht.setText("""
                Hallo,
                
                Bestätigungs-PIN: %s

                Diese PIN ist %d Minuten gültig und gilt für genau einen Vorgang.
                Wenn Du das Zurücksetzen nicht angefordert hast, dann ignoriere diese
                Nachricht und prüfe, wer die zentrale PIN kennt.

                Freundliche Grüße

                -- Dies ist eine automatisch erzeugte Nachricht, bitte nicht antworten.
                """.formatted(pin, gueltigkeitMinuten));

        try {
            versender.send(nachricht);
        } catch (MailException e) {
            LOG.error("Versand der Bestaetigungs-PIN fehlgeschlagen.", e);
            throw new FachlicherFehler(Fehlercode.VERSAND_FEHLGESCHLAGEN);
        }

        // Bewusst ohne die PIN und ohne die Adresse: Beide gehoeren nicht ins Log.
        LOG.info("Bestaetigungs-PIN fuer das Zuruecksetzen des Admin-Passworts versendet.");
    }

    /**
     * Schickt die Absage des gebuchten Hallentermins an den Betreiber (A23, S7 Abschnitt 4).
     *
     * <h2>Der Aufbau der Nachricht</h2>
     * <pre>
     * Betreff:  Absage Hallentermin am 14.09.2026, 19:00 Uhr
     *
     * Termin:   Sonntag, 14.09.2026, 19:00 Uhr
     * Ort:      Sporthalle Musterstrasse
     *
     * &lt;Vorlage&gt;
     * </pre>
     *
     * <p><b>Der Betreff nennt Datum und Uhrzeit.</b> Er ist die einzige Zeile, die der
     * Hallenbetreiber in seiner Uebersicht sieht; "Absage Hallentermin" ohne Datum zwingt ihn,
     * jede Nachricht zu oeffnen.
     *
     * <p><b>Der Datenblock steht ueber der Vorlage</b> (Festlegung aus {@code V010}). Die
     * Vorlage endet mit Grussformel und Signatur; ein Datenblock darunter stuende hinter der
     * Unterschrift.
     *
     * <p><b>Der Wochentag gehoert dazu</b>, obwohl das Datum ihn enthaelt: Er ist die Angabe, an
     * der ein Mensch einen Terminirrtum bemerkt. Das Sprachkennzeichen steht ausdruecklich am
     * Formatierer - ohne es naehme Java die Voreinstellung des Rechners, und im Container ist
     * das nicht verlaesslich Deutsch.
     *
     * <p><b>Fehlt der Ort, entfaellt die Zeile ganz</b> - nicht "Ort: -" und nicht
     * "Ort: unbekannt". Der Hallenbetreiber weiss, um welche Halle es geht; er hat nur die eine.
     *
     * <p><b>Reiner Text, kein HTML</b> - dieselbe Begruendung wie bei der Bestaetigungs-PIN: Es
     * gibt nichts zu formatieren, und reiner Text kommt ueberall gleich an.
     *
     * @param empfaenger Adresse aus {@code configs.app_config.halle_email}; vom Aufrufer bereits
     *                   auf leer geprueft
     * @param datum      Datum des Termins in Ortszeit
     * @param uhrzeit    Uhrzeit des Termins in Ortszeit
     * @param ort        Spielort oder {@code null}
     * @param vorlage    der wirksame Fliesstext; der Aufrufer hat die Ersatzvorlage bereits
     *                   eingesetzt, dieser Dienst entscheidet darueber nicht
     * @throws FachlicherFehler {@code 503}, wenn der Versand scheitert - der Aufrufer laesst
     *                          damit seine Transaktion zurueckrollen, und der Absagevermerk
     *                          verschwindet mit ihr
     */
    public void sendeHallenabsage(String empfaenger, LocalDate datum, LocalTime uhrzeit,
                                  String ort, String vorlage) {

        String zeitpunkt = "%s, %s Uhr".formatted(datum.format(DATUM), uhrzeit.format(UHRZEIT));

        StringBuilder text = new StringBuilder();
        text.append("Termin:   ").append(datum.format(WOCHENTAG)).append(", ").append(zeitpunkt)
                .append('\n');
        if (ort != null && !ort.isBlank()) {
            text.append("Ort:      ").append(ort).append('\n');
        }
        text.append('\n').append(vorlage).append('\n');

        SimpleMailMessage nachricht = new SimpleMailMessage();
        nachricht.setFrom(absender);
        nachricht.setTo(empfaenger);
        nachricht.setSubject("Absage Hallentermin am " + zeitpunkt);
        nachricht.setText(text.toString());

        try {
            versender.send(nachricht);
        } catch (MailException e) {
            LOG.error("Versand der Hallenabsage fehlgeschlagen.", e);
            throw new FachlicherFehler(Fehlercode.VERSAND_FEHLGESCHLAGEN);
        }

        // Ohne die Adresse: Sie gehoert nicht ins Anwendungsprotokoll. Wer sie braucht, findet
        // sie im Audit-Log, das der Aufrufer in derselben Transaktion schreibt.
        LOG.info("Absage des Hallentermins am {} versendet.", zeitpunkt);
    }
}
