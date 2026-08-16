package de.fubo.appserver.common.config;

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
                             @DefaultValue @NotNull Audit audit) {

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
}
