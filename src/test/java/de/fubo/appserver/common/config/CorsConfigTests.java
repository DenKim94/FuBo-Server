package de.fubo.appserver.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft die Startpruefung der CORS-Allowlist aus {@link CorsConfig} (ergaenzt am 24.10.2026).
 *
 * <p><b>Ohne Spring-Kontext und ohne {@code FuboProperties}</b>, aus demselben Grund wie
 * {@code MailConfigTests}: Die Pruefung ist eine statische Methode und wird direkt aufgerufen.
 *
 * <p><b>Warum es diese Klasse gibt:</b> Fehlt {@code FUBO_CORS_ALLOWED_ORIGINS}, reicht Spring
 * den Platzhalter woertlich durch, und die Allowlist besteht die Bean-Validierung. Die
 * Anwendung startete, Bruno und curl funktionierten, nur jeder Aufruf aus dem Browser scheiterte
 * an CORS. Der Kommentar in {@code application.yml} behauptete bis dahin einen Startabbruch,
 * den es nicht gab.
 */
class CorsConfigTests {

    /** Der Fall, der zu dieser Pruefung gefuehrt hat. */
    @Test
    void unaufgeloesterPlatzhalterBrichtAbUndNenntDieVariable() {
        assertThatThrownBy(() -> CorsConfig.pruefeOrigin("${FUBO_CORS_ALLOWED_ORIGINS}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Platzhalter")
                .hasMessageContaining("FUBO_CORS_ALLOWED_ORIGINS");
    }

    /**
     * <b>Die Gegenprobe:</b> die Werte aus Test, Entwicklung und Betrieb. Ein abschliessender
     * Schraegstrich und Grossbuchstaben muessen durchgehen - {@code CorsConfiguration} entfernt
     * den Schraegstrich selbst und vergleicht ohne Ruecksicht auf die Schreibweise. Eine
     * Pruefung, die sie abwiese, verboete gueltige Werte.
     */
    @Test
    void gueltigeOriginsSindErlaubt() {
        assertThatCode(() -> CorsConfig.pruefeOrigin("http://localhost:5173"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CorsConfig.pruefeOrigin("https://fubo-app.example.invalid"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CorsConfig.pruefeOrigin("https://fubo-app.example.invalid/"))
                .as("abschliessender Schraegstrich - entfernt Spring selbst")
                .doesNotThrowAnyException();
        assertThatCode(() -> CorsConfig.pruefeOrigin("https://FuBo-App.example.invalid"))
                .as("Grossbuchstaben - Spring vergleicht ohne Ruecksicht auf die Schreibweise")
                .doesNotThrowAnyException();
    }

    /** Der Browser sendet im Origin-Header nie einen Pfad - ein solcher Eintrag passt nie. */
    @Test
    void originMitPfadAbfrageOderFragmentBrichtAb() {
        assertThatThrownBy(() -> CorsConfig.pruefeOrigin("https://fubo-app.example.invalid/app"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keine Origin")
                .hasMessageContaining("FUBO_CORS_ALLOWED_ORIGINS");
        assertThatThrownBy(() -> CorsConfig.pruefeOrigin("https://fubo-app.example.invalid?x=1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CorsConfig.pruefeOrigin("https://fubo-app.example.invalid#x"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Ohne Schema ist der Wert ein Hostname, keine Origin; andere Schemata gibt es hier nicht. */
    @Test
    void ohneSchemaOderMitFremdemSchemaBrichtAb() {
        assertThatThrownBy(() -> CorsConfig.pruefeOrigin("fubo-app.example.invalid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keine Origin");
        assertThatThrownBy(() -> CorsConfig.pruefeOrigin("ftp://fubo-app.example.invalid"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * {@code *} waere mit {@code allowCredentials=true} unzulaessig - Spring meldete das aber
     * erst bei der ersten Anfrage, mit {@code 500}.
     */
    @Test
    void sternchenBrichtAb() {
        assertThatThrownBy(() -> CorsConfig.pruefeOrigin("*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keine Origin");
    }

    /** Ein Wert, den {@code URI} gar nicht lesen kann, bricht mit derselben Meldung ab. */
    @Test
    void unlesbarerWertBrichtAb() {
        assertThatThrownBy(() -> CorsConfig.pruefeOrigin("https://fubo app.example.invalid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keine Origin");
    }
}
