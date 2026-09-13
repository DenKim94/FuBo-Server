package de.fubo.appserver.support;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Handgeschriebener Ersatz fuer den {@link JavaMailSender}.
 *
 * <p>Kein Mockito und keine zusaetzliche Abhaengigkeit - dasselbe Vorgehen wie beim
 * {@code SessionService}-Ersatz in {@code SessionAuthFilterTests}. Der Ersatz merkt sich die
 * Nachrichten und kann auf Wunsch scheitern.
 *
 * <h2>Warum er seit S7 hier liegt und nicht mehr in einer Testklasse</h2>
 * Bis S6 war er eine paketprivate, verschachtelte Klasse in
 * {@code controller.auth.PasswortResetControllerTests} - dort war er entstanden und dort wurde er
 * gebraucht. Mit dem Hallenmodus gibt es einen zweiten Versandweg und damit eine zweite
 * Testklasse in einem anderen Paket, die ihn braucht; paketprivat erreichte sie ihn nicht.
 *
 * <p><b>Eine zweite Kopie waere die kuerzere und falsche Antwort:</b> Zwei Doppel desselben
 * {@code JavaMailSender} liefen auseinander, sobald eines von beiden eine Methode dazubekommt -
 * und die Testklasse, die das aeltere benutzt, prueft dann etwas anderes als sie glaubt.
 *
 * <p>Nur {@link #send(SimpleMailMessage)} wird gebraucht; alles Uebrige gehoert zum MIME-Teil der
 * Schnittstelle, den die Anwendung nicht benutzt. Diese Methoden werfen deshalb ausdruecklich,
 * statt still nichts zu tun: Griffe die Anwendung eines Tages doch darauf zu, soll das auffallen.
 */
public class MailErsatz implements JavaMailSender {

    private final List<SimpleMailMessage> nachrichten = new ArrayList<>();
    private boolean scheitert;

    /** Die bisher angenommenen Nachrichten, in Versandreihenfolge. */
    public List<SimpleMailMessage> nachrichten() {
        return nachrichten;
    }

    /** Leert die Liste und nimmt einen eingestellten Fehlschlag zurueck; gehoert ins Aufbauen. */
    public void zuruecksetzen() {
        nachrichten.clear();
        scheitert = false;
    }

    /**
     * Stellt den Versandfehler nach, den die Anwendung mit {@code 503} beantwortet.
     *
     * @param scheitert {@code true}, wenn der naechste Versand scheitern soll
     */
    public void laesstScheitern(boolean scheitert) {
        this.scheitert = scheitert;
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) {
        if (scheitert) {
            throw new MailSendException("Versand im Test absichtlich fehlgeschlagen.");
        }
        nachrichten.add(simpleMessage);
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) {
        for (SimpleMailMessage nachricht : simpleMessages) {
            send(nachricht);
        }
    }

    @Override
    public MimeMessage createMimeMessage() {
        throw new UnsupportedOperationException("Die Anwendung versendet ausschliesslich einfachen Text.");
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) {
        throw new UnsupportedOperationException("Die Anwendung versendet ausschliesslich einfachen Text.");
    }

    @Override
    public void send(MimeMessage mimeMessage) {
        throw new UnsupportedOperationException("Die Anwendung versendet ausschliesslich einfachen Text.");
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
        throw new UnsupportedOperationException("Die Anwendung versendet ausschliesslich einfachen Text.");
    }
}
