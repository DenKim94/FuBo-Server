package de.fubo.appserver.common.security;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Prueft das Session-Cookie und setzt den SecurityContext. Laeuft vor jedem Controller. */
public class SessionAuthFilter extends OncePerRequestFilter {

    /**
     * Kennzeichnet einen Hintergrundaufruf, der das Leerlauf-Fenster <b>nicht</b>
     * verlaengern soll (Abschnitt 10.8, entschieden am 22.08.2026).
     *
     * <p>Das Frontend pollt den Belegtstatus der Namen (A6). Ohne diesen Schalter
     * verschoebe jeder dieser Aufrufe {@code gueltig_bis} nach hinten, und "15 Minuten
     * Inaktivitaet" wuerde den offenen Browser-Tab messen statt den Nutzer.
     *
     * <p><b>Der Header ist eine Bitte, kein Sicherheitsmerkmal.</b> Er wird vom Client
     * gesetzt und laesst sich nicht pruefen. Missbrauch schadet aber nur dem Absender: Wer
     * ihn an jeden Aufruf haengt, laesst seine eigene Sitzung frueher ablaufen. Die
     * umgekehrte Richtung - laenger gueltig als erlaubt - ist damit nicht erreichbar,
     * weil {@code absolut_gueltig_bis} unabhaengig davon gilt.
     *
     * <p>Der Header muss in der CORS-Allowlist stehen ({@code CorsConfig}), sonst lehnt
     * der Browser bereits den Preflight ab.
     */
    public static final String HEADER_KEIN_REFRESH = "X-FuBo-Kein-Refresh";

    private final SessionService sessionService;
    private final String cookieName;

    public SessionAuthFilter(SessionService sessionService, FuboProperties props) {
        this.sessionService = sessionService;
        this.cookieName = props.session().cookieName();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        boolean verlaengern = !istHintergrundaufruf(req);

        cookieLesen(req)
                .flatMap(token -> verlaengern
                        ? sessionService.pruefenUndVerlaengern(token)
                        : sessionService.pruefen(token))
                .ifPresent(this::kontextSetzen);

        chain.doFilter(req, res);   // Der Filter lehnt NIE selbst ab.
    }

    /**
     * Wertet {@link #HEADER_KEIN_REFRESH} aus. Nur der Wert {@code true} zaehlt; jede
     * andere Angabe und ein fehlender Header bedeuten "normaler Aufruf".
     *
     * <p>Bewusst diese Richtung: Der Standardfall bleibt die Verlaengerung. Ein Tippfehler
     * im Header fuehrt damit zum bisherigen Verhalten und nicht zu Sitzungen, die
     * unerwartet ablaufen.
     */
    private static boolean istHintergrundaufruf(HttpServletRequest req) {
        return "true".equalsIgnoreCase(req.getHeader(HEADER_KEIN_REFRESH));
    }

    /** Legt einen frischen SecurityContext an und traegt die Sitzung als Principal ein. */
    private void kontextSetzen(AktiveSitzung sitzung) {
        var authorities = List.of(new SimpleGrantedAuthority(authority(sitzung)));
        var auth = new UsernamePasswordAuthenticationToken(sitzung, null, authorities);

        SecurityContext kontext = SecurityContextHolder.createEmptyContext();
        kontext.setAuthentication(auth);
        SecurityContextHolder.setContext(kontext);
    }

    /** Bildet Stufe und Rolle auf genau eine Authority ab (siehe Tabelle 5.2). */
    private static String authority(AktiveSitzung sitzung) {
        if (sitzung.stage() == Stage.PIN_VERIFIED) {
            return "ROLE_PIN_VERIFIED";
        }
        // Ab PROFILE_AUTHENTICATED ist die Rolle nicht null - ck_session_rolle_stage (V008).
        return "ROLE_" + sitzung.rolle().name();
    }

    /** Liest den Wert des Session-Cookies aus der Anfrage. */
    private Optional<String> cookieLesen(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(req.getCookies())
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
