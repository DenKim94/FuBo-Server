package de.fubo.appserver.common.config;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Baut den {@link JavaMailSender} fuer den Versand der Bestaetigungs-PIN (A22, S2b).
 *
 * <h2>Warum die Bean von Hand entsteht und nicht ueber {@code spring.mail.*}</h2>
 * Die Anleitung sah die Autokonfiguration von Spring Boot vor. Sie kann aber nicht
 * einloesen, was Abschnitt 1.1 von ihr erwartet: <b>"Fehlt die Variable, bricht der Start
 * ab."</b> Spring Boots {@code Binder} reicht einen unaufloesbaren Platzhalter naemlich
 * <b>woertlich</b> durch ({@code ignoreUnresolvablePlaceholders = true}). Ohne gesetzte
 * Umgebungsvariable entstuende ein Versender mit dem Rechnernamen <code>"${SMTP_HOST}"</code>,
 * der Start liefe durch, und der Fehler zeigte sich erst beim ersten Reset - also genau
 * dann, wenn niemand mehr an die Konfiguration denkt.
 *
 * <p>Es ist derselbe Fallstrick, der in S1 zur Meldung
 * <code>password authentication failed for user "${DB_USER}"</code> gefuehrt hat. <b>Merkregel:
 * Ein <code>${...}</code> in einer Fehlermeldung bedeutet immer fehlende Aufloesung, nie einen
 * falschen Wert.</b>
 *
 * <p>Die Werte kommen deshalb aus {@link FuboProperties.Mail} und werden hier geprueft.
 * Nebeneffekt: Die Mail-Konfiguration steht an derselben Stelle wie die uebrigen
 * Anwendungseinstellungen und nicht in einem zweiten, fremden Namensraum.
 *
 * <p>Die Autokonfiguration greift dadurch nicht mehr - sie ist an das Vorhandensein von
 * {@code spring.mail.host} gebunden, und dieser Schluessel wird bewusst nicht gesetzt.
 */
@Configuration
public class MailConfig {

    /** Kennzeichen eines Platzhalters, den Spring nicht aufloesen konnte. */
    private static final String UNAUFGELOESTER_PLATZHALTER = "${";

    /**
     * Erzeugt den Versender aus der geprueften Konfiguration.
     *
     * <p>Die drei Zeitgrenzen sind keine Feinheit, sondern die Bedingung dafuer, dass der
     * Versand innerhalb der Transaktion laufen darf: Ohne sie haengt ein nicht erreichbarer
     * SMTP-Server den Aufruf unbegrenzt - und mit ihm die Datenbankverbindung.
     *
     * @param eigenschaften gebundene Anwendungskonfiguration
     * @return einsatzbereiter Versender mit STARTTLS und Authentifizierung
     * @throws IllegalStateException wenn eine Angabe fehlt oder ein Platzhalter
     *                               unaufgeloest geblieben ist - der Start bricht dann mit
     *                               einer Meldung ab, die die betroffene Variable nennt
     */
    @Bean
    JavaMailSender javaMailSender(FuboProperties eigenschaften) {
        FuboProperties.Mail konfiguration = eigenschaften.mail();

        pruefe(konfiguration.host(), "fubo.mail.host", "SMTP_HOST");
        pruefe(konfiguration.benutzer(), "fubo.mail.benutzer", "SMTP_USER_NAME");
        pruefe(konfiguration.passwort(), "fubo.mail.passwort", "SMTP_PASSWORD");
        pruefe(konfiguration.absender(), "fubo.mail.absender", "SMTP_ABSENDER");
        pruefeAbsenderformat(konfiguration.absender());

        JavaMailSenderImpl versender = new JavaMailSenderImpl();
        versender.setHost(konfiguration.host());
        versender.setPort(konfiguration.port());
        versender.setUsername(konfiguration.benutzer());
        versender.setPassword(konfiguration.passwort());
        // Ohne diese Angabe waehlt JavaMail die Plattformkodierung; Umlaute im Betreff
        // kaemen dann je nach Rechner unterschiedlich an.
        versender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties smtp = versender.getJavaMailProperties();
        smtp.put("mail.smtp.auth", "true");
        smtp.put("mail.smtp.starttls.enable", "true");

        String zeitgrenze = Integer.toString(konfiguration.zeitgrenzeMillis());
        smtp.put("mail.smtp.connectiontimeout", zeitgrenze);
        smtp.put("mail.smtp.timeout", zeitgrenze);
        smtp.put("mail.smtp.writetimeout", zeitgrenze);

        // mail.debug bleibt aus. Die Beispielkonfiguration in Abschnitt 0.3 der Anleitung
        // setzt es auf true; das protokolliert den gesamten SMTP-Dialog einschliesslich der
        // Anmeldung. Zum Nachstellen eines Problems laesst es sich zeitweise einschalten,
        // im Betrieb gehoert es nicht ins Log.

        return versender;
    }

    /**
     * Bricht mit einer benennenden Meldung ab, wenn ein Wert fehlt oder ein Platzhalter
     * stehen geblieben ist.
     *
     * @param wert       gebundener Konfigurationswert
     * @param schluessel Name der Eigenschaft, fuer die Meldung
     * @param variable   zugehoerige Umgebungsvariable, fuer die Meldung
     */
    /**
     * Prueft, dass der Absender eine <b>einzelne, syntaktisch gueltige</b> Adresse ist
     * (ergaenzt am 29.08.2026).
     *
     * <h2>Warum das nicht in {@link #pruefe} passt</h2>
     * Die drei Pruefungen dort fangen einen <i>fehlenden</i> Wert ab. Hier ist der Wert da,
     * sieht plausibel aus - und der SMTP-Server lehnt ihn trotzdem ab. Das faellt ohne diese
     * Pruefung erst beim ersten echten Versand auf, also beim Passwort-Reset: ausgerechnet
     * dem einzigen Weg zurueck, wenn das Adminpasswort vergessen ist. Der Betreiber merkt es
     * in dem Moment, in dem er es am wenigsten gebrauchen kann.
     *
     * <h2>Der Fall, der dazu gefuehrt hat</h2>
     * In der {@code .env} stand
     * <pre>SMTP_ABSENDER="FuBo-App&lt;NoReply@example.org&gt;"</pre>
     * Die Anfuehrungszeichen wurden <b>Teil des Werts</b>: Die Datei wird ueber
     * {@code spring.config.import=optional:file:./.env[.properties]} als
     * <i>Java-Properties-Datei</i> gelesen, und dieses Format kennt keine Anfuehrungszeichen
     * als Begrenzer - alles nach dem ersten {@code =} ist der Wert. Maileroo antwortete mit
     * {@code 550 5.6.0 The from address could not be parsed}, die Anwendung mit
     * {@code 503 VERSAND_FEHLGESCHLAGEN}. <b>Merkregel: In der {@code .env} nie
     * Anfuehrungszeichen setzen</b> - das gilt fuer jede Variable, nicht nur fuer diese.
     *
     * <h2>Zwei Stufen, und warum die erste noetig ist</h2>
     * Ein Wert, der <b>mit einem Anfuehrungszeichen beginnt und endet</b>, ist genau der
     * Fall oben und wird eigens benannt - die Meldung soll die Ursache nennen, nicht nur das
     * Symptom. Wichtig ist die Bedingung <i>beides</i>: {@code "Kim, Denis" <a@b.de>} ist
     * eine voellig gueltige Form (ein Anzeigename mit Komma <b>muss</b> in Anfuehrungszeichen
     * stehen) und beginnt ebenfalls mit einem - sie endet aber mit {@code >} und geht
     * deshalb hier durch.
     *
     * <p>Alles Uebrige prueft {@link InternetAddress}. {@code parse(..., true)} verlangt
     * RFC-822-Syntax, {@code validate()} sieht zusaetzlich nach; genau eine Adresse muss es
     * sein, weil {@code SimpleMailMessage#setFrom} nur eine setzen kann.
     *
     * @param wert bereits auf leer und Platzhalter geprueft
     * @throws IllegalStateException wenn der Wert keine einzelne gueltige Adresse ist
     */
    static void pruefeAbsenderformat(String wert) {
        String bereinigt = wert.trim();

        if (bereinigt.length() > 1 && bereinigt.startsWith("\"") && bereinigt.endsWith("\"")) {
            throw new IllegalStateException(
                    ("fubo.mail.absender steht vollstaendig in Anfuehrungszeichen: %s. Die .env "
                            + "wird als Java-Properties-Datei gelesen - dort sind Anfuehrungszeichen "
                            + "keine Begrenzer, sondern Teil des Werts, und der SMTP-Server lehnt "
                            + "den Absender ab (550 5.6.0). SMTP_ABSENDER ohne Anfuehrungszeichen "
                            + "schreiben, etwa: SMTP_ABSENDER=FuBo-App <noreply@example.org>")
                            .formatted(bereinigt));
        }

        try {
            InternetAddress[] adressen = InternetAddress.parse(bereinigt, true);
            if (adressen.length != 1) {
                throw new AddressException(
                        "erwartet wird genau eine Adresse, gefunden: " + adressen.length);
            }
            adressen[0].validate();
        } catch (AddressException e) {
            throw new IllegalStateException(
                    ("fubo.mail.absender ist keine gueltige Absenderadresse: %s (%s). Erlaubt "
                            + "ist die reine Adresse oder die Form \"Anzeigename "
                            + "<adresse@domain>\". Der Wert stammt aus SMTP_ABSENDER.")
                            .formatted(bereinigt, e.getMessage()), e);
        }
    }

    private static void pruefe(String wert, String schluessel, String variable) {
        if (wert == null || wert.isBlank()) {
            throw new IllegalStateException(
                    ("%s ist nicht gesetzt - der Start wird abgebrochen. Bitte %s in der "
                            + "Umgebung bzw. der .env hinterlegen. Ein Standardwert waere hier "
                            + "keine Hilfe: Die Anwendung verschickte dann Nachrichten ins Leere.")
                            .formatted(schluessel, variable));
        }
        if (wert.contains(UNAUFGELOESTER_PLATZHALTER)) {
            throw new IllegalStateException(
                    ("%s enthaelt einen unaufgeloesten Platzhalter (%s) - die Variable %s fehlt "
                            + "in der Umgebung bzw. der .env. Spring reicht solche Platzhalter "
                            + "woertlich durch; ohne diese Pruefung liefe die Anwendung mit dem "
                            + "Wert '%s' weiter.")
                            .formatted(schluessel, UNAUFGELOESTER_PLATZHALTER, variable, wert));
        }
    }
}
