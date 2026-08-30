package de.fubo.appserver.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Stellt die Uhr der Anwendung als Bean bereit.
 *
 * <p>Zeitlogik, die nicht in der Datenbank stattfindet, holt sich die aktuelle Zeit ueber
 * diese Bean statt ueber {@code Instant.now()}. Damit laesst sie sich im Test ohne
 * {@code Thread.sleep} pruefen - eine Sperrdauer von 15 Minuten ist sonst nicht testbar.
 *
 * <p>Zeitpunkte, die in der Datenbank stehen (Sitzungsablauf, Audit-Log), werden weiterhin
 * gegen {@code now()} der Datenbank geprueft. Zwei Uhren fuer denselben Sachverhalt waeren
 * eine Fehlerquelle; die Trennung verlaeuft entlang der Frage, wo der Wert entsteht.
 *
 * <h2>Warum die Uhr seit S4 eine Zeitzone traegt</h2>
 * Bis S3 wurde sie ausschliesslich fuer <i>Differenzen</i> genutzt ({@code BruteForceService}),
 * und dafuer ist die Zone gleichgueltig - deshalb stand hier {@code Clock.systemUTC()}.
 * Mit S4 kommen {@code spieltag.termin.datum} und {@code .uhrzeit} dazu: {@code DATE} und
 * {@code TIME} <b>ohne</b> Zeitzone, also Ortszeit. Die Frage "liegt dieser Termin in der
 * Vergangenheit" ist damit erstmals eine Frage nach der Wanduhr, nicht nach einem Abstand.
 *
 * <p><b>Mit einer UTC-Uhr waere die Antwort im Sommer zwei Stunden falsch:</b> Ein Termin
 * heute um 18:00 Ortszeit liesse sich um 19:00 Ortszeit noch anlegen, weil
 * {@code LocalDateTime.now(uhr)} dann 17:00 lieferte. Der Fehler betraefe nur den
 * Zeitstreifen zwischen den beiden Uhren und faellt deshalb im Test nicht auf.
 *
 * <p><b>Und die Zone des Rechners zu nehmen genuegt nicht:</b> In einem Docker-Container ist
 * sie per Voreinstellung UTC, gleich wo der Rechner steht. Der Wert steht deshalb
 * ausdruecklich in der Konfiguration und wird beim Start protokolliert.
 *
 * <h2>Warum {@code @Value} und nicht {@code FuboProperties}</h2>
 * Die Regel "externe Zugaenge ueber {@code fubo.*} binden und beim Start pruefen" richtet
 * sich gegen Spring Boots {@code Binder}, der einen unaufloesbaren Platzhalter <b>woertlich</b>
 * durchreicht - die Anwendung liefe dann mit dem Rechnernamen {@code "${SMTP_HOST}"}. Hier
 * kann das nicht passieren: Der Wert hat einen Vorgabewert, und {@link ZoneId#of(String)}
 * bricht bei jeder Eingabe ab, die keine Zone ist. Der Startabbruch, den die Regel
 * herbeifuehren will, ist also schon da. Ein weiteres Feld in {@code FuboProperties} haette
 * dagegen einen bekannten Preis: Drei Testklassen bauen den Record von Hand, um ohne
 * Spring-Kontext auszukommen, und muessten jedes Mal nachgezogen werden.
 */
@Configuration
public class ZeitConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ZeitConfig.class);

    /**
     * Die Uhr der Anwendung, in der Zeitzone aus {@code fubo.zeitzone}.
     *
     * @param zone Bezeichner nach IANA, etwa {@code Europe/Berlin}
     * @return Systemuhr in dieser Zone
     * @throws java.time.zone.ZoneRulesException wenn der Bezeichner keine bekannte Zone ist -
     *                                           ein Startabbruch mit benennender Meldung ist
     *                                           hier richtig, weil die Alternative eine
     *                                           Anwendung waere, die still falsch rechnet
     */
    @Bean
    Clock uhr(@Value("${fubo.zeitzone:Europe/Berlin}") String zone) {
        ZoneId zoneId = ZoneId.of(zone);
        LOG.info("Zeitzone der Anwendung: {} (fubo.zeitzone). Datums- und Uhrzeitangaben ohne "
                + "Zeitzone werden so ausgelegt.", zoneId);
        return Clock.system(zoneId);
    }
}
