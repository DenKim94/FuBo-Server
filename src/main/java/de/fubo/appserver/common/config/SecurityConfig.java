package de.fubo.appserver.common.config;

import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.common.security.AuthorizationExceptionHandler;
import de.fubo.appserver.common.security.SessionAuthFilter;
import de.fubo.appserver.service.auth.SessionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(FuboProperties.class)
public class SecurityConfig {

    /** Der Filter wird hier erzeugt, nicht per @Component - sonst liefe er doppelt. */
    @Bean
    SessionAuthFilter sessionAuthFilter(SessionService sessionService, FuboProperties props) {
        return new SessionAuthFilter(sessionService, props);
    }

    /** BCrypt fuer die zentrale PIN (Abschnitt 6) und das Admin-Passwort (Abschnitt 9). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    SessionAuthFilter sessionFilter,
                                    AuthorizationExceptionHandler authorizationExceptionHandler) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)        // vertretbar wegen SameSite=Lax (5.5)
                .cors(Customizer.withDefaults())              // nutzt die CorsConfigurationSource-Bean
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Das Sternchen steht fuer genau ein Pfadsegment - hier die
                        // Version. Die Regeln gelten damit fuer jede Version; welche
                        // Versionen es gibt, entscheidet ApiVersionConfig, nicht die
                        // Filterchain. Eine unbekannte Version wird erst danach mit 400
                        // abgelehnt, nachdem die Autorisierung sie durchgelassen hat.

                        // 1) Offen. /actuator/health bleibt bewusst unversioniert - der
                        //    Container-Healthcheck ruft einen festen Pfad auf.
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/*/auth/pin/pruefen").permitAll()

                        // 2) Stufe PIN_VERIFIED: nur Namensliste lesen und Identitaet waehlen
                        .requestMatchers(HttpMethod.GET,  "/api/*/auth/users/lesen")
                        .hasAnyRole("PIN_VERIFIED", "USER", "ADMIN", "GAST")
                        .requestMatchers(HttpMethod.POST, "/api/*/auth/user/waehlen").hasRole("PIN_VERIFIED")
                        .requestMatchers(HttpMethod.POST, "/api/*/auth/gast/anmelden").hasRole("PIN_VERIFIED")
                        // Nicht zu verwechseln mit /api/*/admin/** weiter unten: Dieser
                        // Endpunkt *verleiht* die Adminrolle, jener *setzt sie voraus*.
                        .requestMatchers(HttpMethod.POST, "/api/*/auth/admin/anmelden").hasRole("PIN_VERIFIED")

                        // Passwort-Reset (S2b): ebenfalls nur in der Stufe PIN_VERIFIED.
                        // Der Endpunkt kann NICHT unter /api/*/admin/** liegen, obwohl es
                        // um das Adminpasswort geht - wer es vergessen hat, traegt die
                        // Rolle ADMIN gerade nicht. Zugleich liegt er bewusst nicht offen:
                        // Er verschickt E-Mails, und die zentrale PIN ist der aeussere Zaun
                        // aus A1. Preis: Wer Passwort UND zentrale PIN vergisst, braucht
                        // die Datenbank.
                        .requestMatchers(HttpMethod.POST, "/api/*/auth/passwort/zuruecksetzen")
                        .hasRole("PIN_VERIFIED")
                        .requestMatchers(HttpMethod.POST, "/api/*/auth/passwort/bestaetigen")
                        .hasRole("PIN_VERIFIED")

                        // 3) Sitzungsverwaltung: ab PIN_VERIFIED erlaubt, nicht erst ab
                        //    PROFILE_AUTHENTICATED. Nach einem Seitenneuladen zwischen
                        //    PIN-Eingabe und Namenswahl muss das Frontend erfahren, in
                        //    welcher Stufe es steht - mit 403 liefe es zurueck zur
                        //    PIN-Eingabe, obwohl die Sitzung gueltig ist. Und einen
                        //    angefangenen Login abzubrechen muss ebenfalls moeglich sein.
                        .requestMatchers(HttpMethod.GET,  "/api/*/auth/session/lesen")
                        .hasAnyRole("PIN_VERIFIED", "USER", "ADMIN", "GAST")
                        .requestMatchers(HttpMethod.POST, "/api/*/auth/session/erneuern")
                        .hasAnyRole("PIN_VERIFIED", "USER", "ADMIN", "GAST")
                        .requestMatchers(HttpMethod.POST, "/api/*/auth/session/beenden")
                        .hasAnyRole("PIN_VERIFIED", "USER", "ADMIN", "GAST")

                        // 4) Adminbereich
                        .requestMatchers("/api/*/admin/**").hasRole("ADMIN")

                        // 5) Rest: angemeldet in Stufe PROFILE_AUTHENTICATED
                        .anyRequest().hasAnyRole("USER", "ADMIN", "GAST"))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                authorizationExceptionHandler.schreibeFehlermeldung(res, Fehlercode.SESSION_UNGUELTIG))   // 401
                        .accessDeniedHandler((req, res, ex) ->
                                authorizationExceptionHandler.schreibeFehlermeldung(res, Fehlercode.KEINE_BERECHTIGUNG))) // 403
                .build();
    }
}
