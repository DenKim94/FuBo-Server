package de.fubo.appserver.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        cfg.setAllowedHeaders(List.of("Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(Duration.ofHours(1));    // Preflight-Antwort zwischenspeichern

        UrlBasedCorsConfigurationSource quelle = new UrlBasedCorsConfigurationSource();
        quelle.registerCorsConfiguration("/**", cfg);
        return quelle;
    }
}