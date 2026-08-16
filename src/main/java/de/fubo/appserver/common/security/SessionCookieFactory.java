package de.fubo.appserver.common.security;

import de.fubo.appserver.common.config.FuboProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookieFactory {

    private final FuboProperties.Session sessionCfg;

    public SessionCookieFactory(FuboProperties props) {
        this.sessionCfg = props.session();
    }

    /** Setzt den Token als Sitzungscookie (kein maxAge -> endet mit dem Browser). */
    public ResponseCookie setzen(String token) {
        return basis(token).build();
    }

    /** Loescht das Cookie beim Logout: gleicher Name und Pfad, leerer Wert, maxAge 0. */
    public ResponseCookie loeschen() {
        return basis("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder basis(String wert) {
        return ResponseCookie.from(sessionCfg.cookieName(), wert)
                .httpOnly(true)
                .secure(sessionCfg.cookieSecure())
                .sameSite(sessionCfg.cookieSameSite())
                .path("/");
        // Bewusst kein Domain-Attribut: Das Cookie bleibt host-only und geht
        // ausschliesslich an api.<domain> zurueck, nicht an jede Subdomain.
    }
}
