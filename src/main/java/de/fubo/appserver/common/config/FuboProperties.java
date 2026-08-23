package de.fubo.appserver.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "fubo")
public record FuboProperties(@NotNull Session session,
                             @NotNull Cors cors,
                             @DefaultValue @NotNull BruteForce bruteForce,
                             @DefaultValue @NotNull Audit audit,
                             @NotNull Mail mail,
                             @DefaultValue @NotNull Reset reset) {

    /** Attribute des Session-Cookies. */
    public record Session(@NotBlank String cookieName,
                          boolean cookieSecure,
                          @NotBlank String cookieSameSite) {}

    /** Erlaubte Frontend-Origins; leer ist unzulaessig. */
    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {}

    /**
     * Drosselung des PIN-Endpunkts (A3).
     *
     * <p>Alle Werte haben ueber {@link DefaultValue} eine Vorgabe. Fehlt der Block
     * {@code fubo.brute-force} in der Konfiguration vollstaendig, gelten diese Vorgaben -
     * der Schutz ist damit nie versehentlich abgeschaltet.
     *
     * @param maxVersucheIp      Fehlversuche je IP im Zeitfenster, bevor gesperrt wird
     * @param maxVersucheGlobal  Fehlversuche insgesamt im Zeitfenster; faengt verteilte
     *                           Angriffe ab, gegen die eine Sperre je IP wirkungslos ist
     * @param fensterMinuten     Zeitfenster, nach dem ein Zaehler ohne neuen Fehlversuch
     *                           verfaellt
     * @param sperrdauernMinuten Steigende Sperrdauern. Der erste Wert gilt fuer die erste
     *                           Sperre, danach jeweils der naechste; der letzte Wert
     *                           wiederholt sich. Bewusst nicht dauerhaft: Sonst koennte ein
     *                           Angreifer die echten Nutzer aussperren, indem er absichtlich
     *                           falsche PINs sendet.
     */
    public record BruteForce(@DefaultValue("5") @Min(1) int maxVersucheIp,
                             @DefaultValue("30") @Min(1) int maxVersucheGlobal,
                             @DefaultValue("15") @Min(1) int fensterMinuten,
                             @DefaultValue({"1", "5", "15"}) @NotEmpty List<@Min(1) Integer> sperrdauernMinuten) {}

    /**
     * Aufbewahrung des Audit-Logs.
     *
     * <p><b>Warum als Property und nicht in {@code configs.app_config}?</b> Die Frist ist
     * eine Betriebs- und Rechtsgroesse, keine fachliche Einstellung der Anwendung. Sie
     * gehoert zum Betrieb einer Installation und hat im Admin-Bereich (S3) nichts zu
     * suchen - ein Admin soll die Nachvollziehbarkeit seiner eigenen Aenderungen nicht per
     * Formular verkuerzen koennen. Als Property laesst sie sich je Umgebung setzen, ohne
     * dass eine Migration noetig waere.
     *
     * <p>Der Gegensatz dazu ist die Aufbewahrung abgelaufener Sitzungen: Sie steht als
     * Konstante im {@code SessionService}, weil dort gar kein Anlass zum Verstellen
     * besteht - eine Sitzung ist nach spaetestens einer Stunde ohnehin wertlos.
     *
     * @param aufbewahrungTage Tage, nach denen ein Eintrag geloescht wird (Vorgabe 90)
     */
    public record Audit(@DefaultValue("90") @Min(1) int aufbewahrungTage) {}

    /**
     * SMTP-Zugang fuer den Versand der Bestaetigungs-PIN (A22, S2b).
     *
     * <p><b>Warum ein eigener Block und nicht {@code spring.mail.*}?</b> Aus dieser
     * Konfiguration entsteht die {@code JavaMailSender}-Bean in {@link MailConfig}, und
     * dort laesst sich ein fehlender oder unaufgeloester Wert beim Start benennen. Ueber
     * {@code spring.mail.host} waere das nicht moeglich: Spring Boots {@code Binder} reicht
     * einen unaufloesbaren Platzhalter <b>woertlich</b> durch. Die Anwendung startete dann
     * mit dem Rechnernamen <code>"${SMTP_HOST}"</code> durch und faellt erst im Ernstfall auf -
     * derselbe Fallstrick, der in S1 zur Meldung
     * <code>password authentication failed for user "${DB_USER}"</code> gefuehrt hat.
     *
     * <p><b>Kein Vorgabewert fuer {@link #host}, {@link #benutzer}, {@link #passwort} und
     * {@link #absender}.</b> Ein Standardwert wie {@code localhost} erzeugte eine Anwendung,
     * die im Betrieb Nachrichten ins Leere schickt.
     *
     * @param host             SMTP-Server, aus {@code SMTP_HOST}
     * @param port             SMTP-Port; 587 ist der Standardport fuer STARTTLS
     * @param benutzer         Kontoname beim SMTP-Anbieter, aus {@code SMTP_USER_NAME}
     * @param passwort         Kennwort beim SMTP-Anbieter, aus {@code SMTP_PASSWORD}
     * @param absender         Absenderadresse, aus {@code SMTP_ABSENDER}. Sie muss zur beim
     *                         Anbieter freigegebenen Domain gehoeren, sonst lehnt dieser den
     *                         Versand ab. Erlaubt ist die reine Adresse oder die Form
     *                         {@code Anzeigename <adresse@domain>}.
     * @param zeitgrenzeMillis Zeitgrenze je Phase (Verbindung, Lesen, Schreiben). <b>Ohne
     *                         Zeitgrenzen haengt ein nicht erreichbarer SMTP-Server den
     *                         Aufruf unbegrenzt - und mit ihm die Datenbankverbindung</b>,
     *                         denn der Versand laeuft innerhalb der Transaktion. Fuenf
     *                         Sekunden je Phase sind die Obergrenze dessen, was eine offene
     *                         Transaktion vertragen sollte.
     */
    public record Mail(@NotBlank String host,
                       @DefaultValue("587") @Min(1) int port,
                       @NotBlank String benutzer,
                       @NotBlank String passwort,
                       @NotBlank String absender,
                       @DefaultValue("5000") @Min(1) int zeitgrenzeMillis) {}

    /**
     * Grenzen des Passwort-Resets (A22, S2b).
     *
     * <p>Fuenf Stellen sind fuer sich genommen wenig - 100 000 Moeglichkeiten. Tragfaehig
     * wird die Bestaetigungs-PIN erst durch die Summe dieser Grenzen zusammen mit dem
     * BCrypt-Hash und der Lage des Endpunkts hinter der zentralen PIN. Selbst wer eine
     * Stunde lang alle erlaubten Versuche ausschoepft, kommt auf 15 von 100 000 - etwa
     * 0,015 Prozent. <b>Keine dieser Grenzen darf entfallen</b>; jede einzelne traegt.
     *
     * @param gueltigkeitMinuten       Lebensdauer eines Vorgangs; danach ist die PIN
     *                                 wertlos und der Admin fordert neu an
     * @param maxVersuche              Rateversuche je Vorgang. Der Wert ist <b>an die
     *                                 Datenbank gekoppelt</b>: {@code
     *                                 ck_passwort_reset_versuche} laesst nur Werte von 0 bis
     *                                 5 zu, ein hoeherer Wert liefe in eine
     *                                 Constraint-Verletzung. Deshalb {@code @Max(5)}.
     * @param maxAnforderungenProStunde Anforderungen je Client-Adresse und Stunde. Begrenzt
     *                                 zugleich die Zahl der versendeten Nachrichten und die
     *                                 Zahl der Rateversuche pro Stunde.
     * @param aufbewahrungTage         Tage, nach denen ein abgeschlossener Vorgang geloescht
     *                                 wird (Vorgabe 30). Wie beim Audit-Log eine Betriebs-
     *                                 und Rechtsgroesse und deshalb Property, nicht
     *                                 {@code configs.app_config}: {@code angefordert_von_ip}
     *                                 ist personenbezogen, und die Tabelle waechst sonst
     *                                 unbegrenzt. <b>Kuerzer als die 90 Tage des
     *                                 Audit-Logs</b>, weil der fachliche Beleg dort steht -
     *                                 hier bleiben nur die technischen Vorgangsdaten.
     */
    public record Reset(@DefaultValue("15") @Min(1) int gueltigkeitMinuten,
                        @DefaultValue("5") @Min(1) @Max(5) int maxVersuche,
                        @DefaultValue("3") @Min(1) int maxAnforderungenProStunde,
                        @DefaultValue("30") @Min(1) int aufbewahrungTage) {}
}
