package de.fubo.appserver.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stellt den {@link MailErsatz} anstelle des echten Versenders bereit.
 *
 * <p><b>Ausdruecklich zu importieren</b> ({@code @Import(MailErsatzConfig.class)}). Als
 * verschachtelte Klasse in einer Testklasse fand Spring Boot sie von selbst; seit sie eine eigene
 * Datei ist, tut es das nicht mehr. <b>Wer den Import vergisst, bekommt keinen Fehler</b> - die
 * Anwendung nimmt dann den echten Versender aus {@code MailConfig} und versucht, eine Nachricht
 * zu verschicken.
 *
 * <p><b>{@code @Primary} muss mitwandern.</b> Die Bean aus {@code MailConfig} ist ebenfalls ein
 * {@code JavaMailSender}; ohne den Vorrang waere die Einspeisung mehrdeutig und der Kontext
 * startete gar nicht.
 */
@TestConfiguration
public class MailErsatzConfig {

    @Bean
    @Primary
    MailErsatz mailErsatz() {
        return new MailErsatz();
    }
}
