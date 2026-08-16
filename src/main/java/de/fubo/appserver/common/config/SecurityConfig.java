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
                        // 1) Offen
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/pin").permitAll()

                        // 2) Stufe PIN_VERIFIED: nur Namensliste lesen und Identitaet waehlen
                        .requestMatchers(HttpMethod.GET,  "/api/auth/users")
                        .hasAnyRole("PIN_VERIFIED", "USER", "ADMIN", "GAST")
                        .requestMatchers(HttpMethod.POST, "/api/auth/user").hasRole("PIN_VERIFIED")
                        .requestMatchers(HttpMethod.POST, "/api/auth/gast").hasRole("PIN_VERIFIED")

                        // 3) Adminbereich
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // 4) Rest: angemeldet in Stufe PROFILE_AUTHENTICATED
                        .anyRequest().hasAnyRole("USER", "ADMIN", "GAST"))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                authorizationExceptionHandler.schreibeFehlermeldung(res, Fehlercode.SESSION_UNGUELTIG))   // 401
                        .accessDeniedHandler((req, res, ex) ->
                                authorizationExceptionHandler.schreibeFehlermeldung(res, Fehlercode.KEINE_BERECHTIGUNG))) // 403
                .build();
    }
}
