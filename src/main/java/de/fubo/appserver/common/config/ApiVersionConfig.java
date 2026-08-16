package de.fubo.appserver.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.RequestPath;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Versionierung der REST-Schnittstelle ueber ein Pfadsegment: {@code /api/v1/...}.
 *
 * <p>Umgesetzt mit der Bordausstattung von Spring Framework 7 (Spring Boot 4) - kein
 * selbst gebauter Mechanismus. Die Version wird von einem {@code ApiVersionResolver} aus
 * der Anfrage gelesen, von einem {@code ApiVersionParser} geparst und gegen die Liste der
 * unterstuetzten Versionen geprueft, bevor die Zuordnung zur Controller-Methode stattfindet.
 * Die Methoden tragen dazu ein {@code version}-Attribut an ihrer Mapping-Annotation.
 *
 * <h2>Warum das Praefix und nicht das Suffix</h2>
 * Die Pfadsegment-Strategie wird ueber einen <b>festen Segment-Index</b> konfiguriert, der
 * fuer die gesamte Anwendung gilt. Bei einem Praefix liegt die Version immer an Index 1,
 * unabhaengig davon, wie tief der Pfad darunter wird:
 *
 * <pre>
 * /api/v1/auth/users/lesen        -> Index 1 = "v1"
 * /api/v1/admin/spieler/12/lesen  -> Index 1 = "v1"   (S3, unveraendert)
 * </pre>
 *
 * Bei einem Suffix (<code>/api/auth/users/lesen/v1</code>) wandert der Index mit der
 * Pfadtiefe - er waere hier 4, beim Adminpfad aber 5. Ein fester Index kann beides nicht
 * gleichzeitig treffen; es braeuchte einen eigenen {@code ApiVersionResolver}. Deshalb das
 * Praefix.
 *
 * <h2>Warum ein Praedikat noetig ist</h2>
 * Ohne {@code Predicate} betrachtet der Pfad-Resolver <b>jede</b> Anfrage als versioniert
 * und wirft fuer alles, was an Index 1 keine Version traegt, eine
 * {@code InvalidApiVersionException}. Das traefe {@code /actuator/health} - und damit den
 * Container-Healthcheck. Das Praedikat grenzt die Versionierung auf {@code /api/} ein.
 *
 * <h2>Schreibweise der Version</h2>
 * Der voreingestellte {@code SemanticApiVersionParser} ueberspringt fuehrende
 * Nicht-Ziffern. {@code /api/v1/...} und {@code /api/1/...} werden deshalb beide als
 * Version {@code 1.0.0} gelesen. Nach aussen wird durchgaengig {@code v1} verwendet; die
 * Toleranz ist ein Nebeneffekt, keine zugesagte Eigenschaft.
 */
@Configuration
public class ApiVersionConfig implements WebMvcConfigurer {

    /**
     * Pfadpraefix aller versionierten Endpunkte. Compile-Zeit-Konstante, damit sie in
     * {@code @RequestMapping} verwendbar ist - so steht der vollstaendige Pfad im
     * Controller und muss nicht aus einer Praefix-Konfiguration erschlossen werden.
     */
    public static final String API_PRAEFIX = "/api/{version}";

    /** Aktuelle und derzeit einzige Version der Schnittstelle. */
    public static final String VERSION = "1";

    /** Position der Version im Pfad: {@code /api/<hier>/...}. */
    private static final int VERSIONS_SEGMENT = 1;

    /** Nur unterhalb dieses Praefixes wird ueberhaupt nach einer Version gesucht. */
    private static final String VERSIONIERTER_BEREICH = "/api/";

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .usePathSegment(VERSIONS_SEGMENT, ApiVersionConfig::istVersionierterPfad)
                // Ausdrueckliche Liste statt reiner Erkennung aus den Mappings: Eine
                // unbekannte Version wird damit mit 400 abgelehnt, statt in einem 404 zu
                // enden, und die Liste dokumentiert, was es gibt.
                .addSupportedVersions(VERSION)
                // Voreinstellung, hier zur Klarstellung gesetzt: Es gibt keine
                // Standardversion. Ein Aufruf muss sich festlegen - sonst aendert sich
                // beim Sprung auf v2 das Verhalten alter Aufrufe stillschweigend.
                .setVersionRequired(true);
    }

    /** Alles unterhalb von {@code /api/} ist versioniert, alles andere nicht. */
    private static boolean istVersionierterPfad(RequestPath pfad) {
        return pfad.pathWithinApplication().value().startsWith(VERSIONIERTER_BEREICH);
    }
}
