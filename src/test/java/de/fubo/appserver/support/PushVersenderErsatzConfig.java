package de.fubo.appserver.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stellt den {@link PushVersenderErsatz} anstelle des echten Versandadapters bereit.
 *
 * <p><b>Ausdruecklich zu importieren</b> ({@code @Import(PushVersenderErsatzConfig.class)}) -
 * dieselbe Falle wie bei {@link MailErsatzConfig}: <b>Wer den Import vergisst, bekommt keinen
 * Fehler.</b> Die Anwendung nimmt dann den {@code WebPushVersender} aus dem Komponentenscan und
 * versucht, eine Nachricht an einen Push-Dienst zu schicken. Im Testlauf endet das nicht in einer
 * roten Zusicherung, sondern in einem Fehlversuch nach Ablauf der Frist - der Fall ist dann rot,
 * aber mit einer Begruendung, die nichts mit ihm zu tun hat.
 *
 * <p><b>{@code @Primary} muss mitwandern.</b> Der {@code WebPushVersender} ist ebenfalls ein
 * {@code PushVersender}; ohne den Vorrang waere die Einspeisung in {@code PushVersandService}
 * mehrdeutig und der Kontext startete gar nicht.
 */
@TestConfiguration
public class PushVersenderErsatzConfig {

    @Bean
    @Primary
    PushVersenderErsatz pushVersenderErsatz() {
        return new PushVersenderErsatz();
    }
}
