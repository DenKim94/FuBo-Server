package de.fubo.appserver.common.config;

import de.fubo.appserver.common.security.SessionAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

@Configuration
public class CorsConfig {

    /** CORS-Allowlist. Ohne allowCredentials sendet der Browser das Cookie nicht mit. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(FuboProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().allowedOrigins());   // Allowlist, kein "*"
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
}
