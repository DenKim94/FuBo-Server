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

/**
 * Versendet die Nachrichten der Anwendung. Derzeit gibt es genau eine: die
 * Bestaetigungs-PIN beim Zuruecksetzen des Admin-Passworts (A22).
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
}
