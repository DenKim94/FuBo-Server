package de.fubo.appserver.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Stellt die Systemuhr als Bean bereit.
 *
 * <p>Zeitlogik, die nicht in der Datenbank stattfindet, holt sich die aktuelle Zeit ueber
 * diese Bean statt ueber {@code Instant.now()}. Damit laesst sie sich im Test ohne
 * {@code Thread.sleep} pruefen - eine Sperrdauer von 15 Minuten ist sonst nicht
 * testbar. Betroffen ist derzeit der {@code BruteForceService}.
 *
 * <p>Zeitpunkte, die in der Datenbank stehen (Sitzungsablauf, Audit-Log), werden weiterhin
 * gegen {@code now()} der Datenbank geprueft. Zwei Uhren fuer denselben Sachverhalt waeren
 * eine Fehlerquelle; die Trennung verlaeuft entlang der Frage, wo der Wert entsteht.
 */
@Configuration
public class ZeitConfig {

    /** UTC, nicht die Zonenzeit: Der Wert wird nur fuer Differenzen genutzt. */
    @Bean
    Clock uhr() {
        return Clock.systemUTC();
    }
}
