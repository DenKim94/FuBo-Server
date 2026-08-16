package de.fubo.appserver.common.security;

import de.fubo.appserver.common.config.FuboProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-Tests fuer die {@link SessionCookieFactory}: kein Spring-Kontext noetig.
 *
 * <p>Die Attribute sind sicherheitsrelevant und lassen sich im Betrieb nur muehsam pruefen
 * (Browser-Werkzeuge, Produktionsumgebung). Hier kosten sie nichts.
 */
class SessionCookieFactoryTests {

    private static SessionCookieFactory fabrik(boolean secure, String sameSite) {
        return new SessionCookieFactory(new FuboProperties(
                new FuboProperties.Session("FUBO_SESSION", secure, sameSite),
                new FuboProperties.Cors(List.of("http://localhost:5173")),
                // Fuer die Cookie-Fabrik ohne Bedeutung.
                new FuboProperties.BruteForce(5, 30, 15, List.of(1, 5, 15)),
                new FuboProperties.Audit(90)));
    }

    // ------------------------------------------------------------------ Setzen

    /** HttpOnly macht den Token fuer JavaScript unsichtbar - Schutz gegen XSS. */
    @Test
    void cookieIstHttpOnly() {
        assertThat(fabrik(true, "Lax").setzen("token").isHttpOnly()).isTrue();
    }

    /** Ohne maxAge entsteht ein Sitzungscookie, das mit dem Browser endet. */
    @Test
    void cookieHatKeinAblaufdatum() {
        ResponseCookie cookie = fabrik(true, "Lax").setzen("token");

        assertThat(cookie.getMaxAge().isNegative()).isTrue();
    }

    /**
     * Kein Domain-Attribut: Das Cookie bleibt host-only und geht ausschliesslich an
     * api.&lt;domain&gt; zurueck, nicht an jede Subdomain des Raspberry Pi.
     */
    @Test
    void cookieHatKeinDomainAttribut() {
        assertThat(fabrik(true, "Lax").setzen("token").getDomain()).isNull();
    }

    /** Der Pfad muss die gesamte API abdecken. */
    @Test
    void cookieGiltFuerDieGesamteAnwendung() {
        assertThat(fabrik(true, "Lax").setzen("token").getPath()).isEqualTo("/");
    }

    /** Name und Wert stammen aus der Konfiguration beziehungsweise vom Aufrufer. */
    @Test
    void nameUndWertWerdenUebernommen() {
        ResponseCookie cookie = fabrik(true, "Lax").setzen("k3Jd9x");

        assertThat(cookie.getName()).isEqualTo("FUBO_SESSION");
        assertThat(cookie.getValue()).isEqualTo("k3Jd9x");
    }

    /** Secure und SameSite kommen aus der Konfiguration und sind je Umgebung verschieden. */
    @Test
    void secureUndSameSiteFolgenDerKonfiguration() {
        assertThat(fabrik(true, "Lax").setzen("token").isSecure()).isTrue();
        assertThat(fabrik(true, "Lax").setzen("token").getSameSite()).isEqualTo("Lax");

        // Lokal muss Secure abschaltbar sein, sonst sendet der Browser ueber
        // http://localhost gar kein Cookie.
        assertThat(fabrik(false, "Lax").setzen("token").isSecure()).isFalse();
    }

    // ------------------------------------------------------------------ Loeschen

    /** Zum Loeschen genuegt maxAge 0 mit leerem Wert. */
    @Test
    void loeschenSetztMaxAgeAufNull() {
        ResponseCookie cookie = fabrik(true, "Lax").loeschen();

        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getValue()).isEmpty();
    }

    /**
     * Entscheidend: Ein Browser identifiziert ein Cookie ueber (Name, Domain, Path). Weichen
     * diese drei beim Loeschen ab, wird nicht geloescht, sondern ein zweites Cookie angelegt -
     * und der alte Token bliebe gueltig im Browser.
     */
    @Test
    void loeschenTrifftDasselbeCookieWieSetzen() {
        SessionCookieFactory fabrik = fabrik(true, "Lax");
        ResponseCookie gesetzt = fabrik.setzen("token");
        ResponseCookie geloescht = fabrik.loeschen();

        assertThat(geloescht.getName()).isEqualTo(gesetzt.getName());
        assertThat(geloescht.getPath()).isEqualTo(gesetzt.getPath());
        assertThat(geloescht.getDomain()).isEqualTo(gesetzt.getDomain());
        assertThat(geloescht.isSecure()).isEqualTo(gesetzt.isSecure());
        assertThat(geloescht.getSameSite()).isEqualTo(gesetzt.getSameSite());
    }

    /** Gegenprobe auf dem serialisierten Header, so wie ihn der Browser sieht. */
    @Test
    void headerEnthaeltAlleAttribute() {
        String header = fabrik(true, "Lax").setzen("k3Jd9x").toString();

        assertThat(header)
                .startsWith("FUBO_SESSION=k3Jd9x")
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Domain=")
                .doesNotContain("Max-Age");
    }
}
