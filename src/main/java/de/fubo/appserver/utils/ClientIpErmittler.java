package de.fubo.appserver.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Ermittelt die echte Client-IP hinter Cloudflare, nginx und dem Tunnel.
 *
 * <p>Ohne diese Aufloesung sieht die Anwendung im Betrieb immer die Adresse des
 * Reverse-Proxys. Der Brute-Force-Schutz wuerde dann nach wenigen Fehlversuchen
 * <b>alle</b> Nutzer gleichzeitig sperren.
 *
 * <p><b>Sicherheitshinweis:</b> Ein Header ist nur so vertrauenswuerdig wie der Weg, auf
 * dem er ankommt. Das traegt hier, weil das Backend ausschliesslich ueber das interne
 * Docker-Netz erreichbar ist und jeder Aufruf zwingend ueber {@code cloudflared} und nginx
 * laeuft. Waere der Port veroeffentlicht, koennte ein Angreifer die Sperre umgehen, indem
 * er den Header selbst setzt.
 */
public final class ClientIpErmittler {

    /**
     * Cloudflare setzt diesen Header mit genau einem Eintrag. Er ist verlaesslicher als
     * {@code X-Forwarded-For}, an den jede Zwischenstation anhaengt.
     */
    private static final String HEADER_CLOUDFLARE = "CF-Connecting-IP";

    /** Ersatzwert, falls weder Header noch Socket eine Adresse liefern. */
    private static final String UNBEKANNT = "unbekannt";

    private ClientIpErmittler() {
    }

    /**
     * Liefert die Client-IP: bevorzugt aus {@code CF-Connecting-IP}, sonst aus der
     * Socket-Adresse. {@code getRemoteAddr()} traegt bereits die Aufloesung von
     * {@code X-Forwarded-For}, weil {@code server.forward-headers-strategy=NATIVE} gesetzt
     * ist - Tomcat wertet den Header dann selbst aus.
     */
    public static String ermitteln(HttpServletRequest anfrage) {
        String cloudflare = anfrage.getHeader(HEADER_CLOUDFLARE);
        if (cloudflare != null && !cloudflare.isBlank()) {
            return cloudflare.trim();
        }
        String socketAdresse = anfrage.getRemoteAddr();
        return (socketAdresse != null && !socketAdresse.isBlank()) ? socketAdresse : UNBEKANNT;
    }
}
