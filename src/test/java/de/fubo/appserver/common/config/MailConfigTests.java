package de.fubo.appserver.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft die Absenderpruefung aus {@link MailConfig} (ergaenzt am 29.08.2026).
 *
 * <p><b>Ohne Spring-Kontext und ohne {@code FuboProperties}</b>: Die Pruefung ist eine
 * statische Methode und wird direkt aufgerufen. Das ist Absicht - {@code FuboProperties} von
 * Hand zu bauen koppelt eine Testklasse an jedes neue Feld des Records, und drei Klassen
 * ({@code SessionAuthFilterTests}, {@code SessionCookieFactoryTests},
 * {@code BruteForceServiceTests}) tragen diese Kopplung bereits. Eine vierte waere keine
 * Verbesserung.
 *
 * <p><b>Warum es diese Klasse gibt:</b> Am 29.08.2026 lehnte der SMTP-Anbieter den Versand
 * mit {@code 550 5.6.0 The from address could not be parsed} ab. Ursache waren
 * Anfuehrungszeichen um den Wert in der {@code .env} - die Datei wird als
 * Java-Properties-Datei gelesen, und dort sind sie Teil des Werts. Der Fehler zeigte sich
 * erst beim ersten echten Versand, also beim Passwort-Reset: dem einzigen Weg zurueck, wenn
 * das Adminpasswort vergessen ist.
 */
class MailConfigTests {

    /** Der Fall, der zu dieser Pruefung gefuehrt hat. */
    @Test
    void vollstaendigInAnfuehrungszeichenBrichtAbUndNenntDieUrsache() {
        assertThatThrownBy(() ->
                MailConfig.pruefeAbsenderformat("\"FuBo-App<NoReply@example.org>\""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Anfuehrungszeichen")
                .hasMessageContaining("Properties")
                .hasMessageContaining("SMTP_ABSENDER");
    }

    /**
     * <b>Die Gegenprobe, die den Test erst wertvoll macht:</b> Ein Anzeigename mit Komma
     * <i>muss</i> in Anfuehrungszeichen stehen. Wuerde die Pruefung jedes fuehrende
     * Anfuehrungszeichen ablehnen, waere sie schlimmer als das Problem - sie verboete eine
     * gueltige Form. Deshalb greift sie nur, wenn der Wert vorn <b>und</b> hinten eines
     * traegt.
     */
    @Test
    void anzeigenameInAnfuehrungszeichenIstErlaubt() {
        assertThatCode(() ->
                MailConfig.pruefeAbsenderformat("\"Kim, Denis\" <noreply@example.org>"))
                .doesNotThrowAnyException();
    }

    /** Die beiden Formen, die in {@code .env.example} beschrieben sind. */
    @Test
    void reineAdresseUndAnzeigenameSindErlaubt() {
        assertThatCode(() -> MailConfig.pruefeAbsenderformat("noreply@example.org"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MailConfig.pruefeAbsenderformat("FuBo-App <noreply@example.org>"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MailConfig.pruefeAbsenderformat("  FuBo-App <noreply@example.org>  "))
                .as("Randleerzeichen aus der .env duerfen nicht stoeren")
                .doesNotThrowAnyException();
    }

    /**
     * Mehrere Adressen sind keine Absenderangabe. {@code SimpleMailMessage#setFrom} kann nur
     * eine setzen; die zweite verschwaende sonst stillschweigend.
     */
    @Test
    void mehrereAdressenBrechenAb() {
        assertThatThrownBy(() ->
                MailConfig.pruefeAbsenderformat("a@example.org, b@example.org"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("genau eine Adresse");
    }

    /** Ein Wert ohne Domain ist keine Adresse. */
    @Test
    void adresseOhneDomainBrichtAb() {
        assertThatThrownBy(() -> MailConfig.pruefeAbsenderformat("FuBo-App"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_ABSENDER");
    }
}
