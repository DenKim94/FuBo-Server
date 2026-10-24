package de.fubo.appserver.common.config;

import de.fubo.appserver.common.security.SessionAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Configuration
public class CorsConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CorsConfig.class);

    /** Kennzeichen eines Platzhalters, den Spring nicht aufloesen konnte. */
    private static final String UNAUFGELOESTER_PLATZHALTER = "${";

    /** CORS-Allowlist. Ohne allowCredentials sendet der Browser das Cookie nicht mit. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(FuboProperties props) {
        List<String> origins = props.cors().allowedOrigins();
        origins.forEach(CorsConfig::pruefeOrigin);
        // Steht im Startlog, damit ein falscher, aber gueltig geformter Eintrag sichtbar ist -
        // den kann keine Pruefung erkennen, und der Browser meldet nur "Failed to fetch".
        LOG.info("CORS erlaubt fuer: {}", origins);

        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins);   // Allowlist, kein "*"
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // Jeder eigene Anfrageheader muss hier stehen. Fehlt er, beantwortet der Browser
        // schon den Preflight ablehnend - und zwar bevor die Anwendung den Aufruf sieht.
        cfg.setAllowedHeaders(List.of("Content-Type", SessionAuthFilter.HEADER_KEIN_REFRESH));
        // Antwortheader sind bei einer Cross-Origin-Antwort standardmaessig unsichtbar.
        // Retry-After (429 am PIN-Endpunkt) wird ausdruecklich freigegeben, damit das
        // Frontend die Wartezeit nicht aus dem Meldungstext lesen muss.
        cfg.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(Duration.ofHours(1));    // Preflight-Antwort zwischenspeichern

        UrlBasedCorsConfigurationSource quelle = new UrlBasedCorsConfigurationSource();
        quelle.registerCorsConfiguration("/**", cfg);
        return quelle;
    }

    /**
     * Bricht den Start ab, wenn ein Eintrag der Allowlist keine Origin ist (ergaenzt am
     * 24.10.2026, S9).
     *
     * <h2>Warum die Pruefung hier steht und nicht in {@code FuboProperties}</h2>
     * {@code @NotEmpty List<@NotBlank String>} faengt nur eine leere Liste ab. Fehlt
     * {@code FUBO_CORS_ALLOWED_ORIGINS}, reicht Spring Boots {@code Binder} den Platzhalter
     * <b>woertlich</b> durch ({@code ignoreUnresolvablePlaceholders = true}, derselbe
     * Fallstrick wie in {@link MailConfig}): Die Liste enthaelt dann
     * <code>"${FUBO_CORS_ALLOWED_ORIGINS}"</code>, ist nicht leer und besteht die Validierung.
     * Die Anwendung startete, und jeder Aufruf aus dem Browser scheiterte mit
     * {@code 403 Invalid CORS request} - waehrend Bruno und curl, die keinen
     * {@code Origin}-Header senden, weiter funktionierten. Der Fehler saehe aus wie ein Problem
     * im Client, in Cloudflare oder in nginx.
     *
     * <h2>Was zusaetzlich abgewiesen wird</h2>
     * Ein Eintrag, der nie passen kann: ohne Schema {@code http}/{@code https}, mit Pfad,
     * Abfrage, Fragment oder Benutzerangabe - und {@code *}, das zusammen mit
     * {@code allowCredentials=true} sonst erst bei der ersten Anfrage mit {@code 500}
     * scheiterte.
     *
     * <h2>Was bewusst NICHT abgewiesen wird</h2>
     * Ein abschliessender Schraegstrich und Grossbuchstaben: {@link CorsConfiguration} entfernt
     * den Schraegstrich selbst ({@code trimTrailingSlash}) und vergleicht ohne Ruecksicht auf
     * Gross- und Kleinschreibung. Ebenso {@code http}: Lokal und im Test ist
     * {@code http://localhost:5173} richtig. <b>Eine gueltig geformte, aber falsche Origin
     * erkennt keine Pruefung</b> - dafuer steht die Allowlist im Startlog.
     *
     * @param origin ein Eintrag aus {@code fubo.cors.allowed-origins}
     * @throws IllegalStateException wenn der Eintrag einen unaufgeloesten Platzhalter enthaelt
     *                               oder keine Origin ist - die Meldung nennt den Wert und
     *                               die Variable
     */
    static void pruefeOrigin(String origin) {
        if (origin.contains(UNAUFGELOESTER_PLATZHALTER)) {
            throw new IllegalStateException(
                    ("fubo.cors.allowed-origins enthaelt einen unaufgeloesten Platzhalter (%s) - "
                            + "die Variable FUBO_CORS_ALLOWED_ORIGINS fehlt in der Umgebung bzw. "
                            + "der .env. Spring reicht solche Platzhalter woertlich durch; ohne "
                            + "diese Pruefung liefe die Anwendung weiter, und jeder Aufruf aus dem "
                            + "Browser scheiterte an CORS.")
                            .formatted(origin));
        }

        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException e) {
            throw keineOrigin(origin, e);
        }

        String schema = uri.getScheme();
        String pfad = uri.getRawPath();
        boolean istOrigin = ("http".equalsIgnoreCase(schema) || "https".equalsIgnoreCase(schema))
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && (pfad == null || pfad.isEmpty() || "/".equals(pfad))
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        if (!istOrigin) {
            throw keineOrigin(origin, null);
        }
    }

    private static IllegalStateException keineOrigin(String origin, Exception ursache) {
        return new IllegalStateException(
                ("fubo.cors.allowed-origins: '%s' ist keine Origin. Erwartet wird Schema und Host, "
                        + "optional mit Port, ohne Pfad - etwa https://fubo-app.example.invalid oder "
                        + "http://localhost:5173. Mehrere Werte kommagetrennt. Der Wert stammt aus "
                        + "FUBO_CORS_ALLOWED_ORIGINS.")
                        .formatted(origin), ursache);
    }
}
