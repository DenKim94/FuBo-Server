package de.fubo.appserver.dto.push;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/push/abo/anlegen} (A25c).
 *
 * <h2>Die drei Werte kommen aus dem Browser</h2>
 * Sie stammen aus {@code PushSubscription} der Push-API: {@link #endpoint} ist die Adresse
 * beim Push-Dienst des Herstellers, {@link #p256dh} der oeffentliche Schluessel der
 * Browserinstallation und {@link #auth} ein Geheimnis, das RFC 8291 als Salz der ersten
 * HKDF-Stufe verwendet.
 *
 * <p><b>Eine Spieler-Id steht bewusst nicht darin.</b> Sie kommt aus der Sitzung - sonst
 * abonnierte jemand fuer einen anderen, und die Nachrichten gingen auf dessen Geraet. Dieselbe
 * Regel wie bei der Rueckmeldung aus S4.
 *
 * <p><b>Einen Anzeigenamen fuer das Geraet gibt es nicht.</b> Der Server bildet ihn aus dem
 * {@code User-Agent}-Kopf; ein frei waehlbarer Name waere eine vom Client bestimmte
 * Zeichenkette in einer Oberflaeche, ohne Gewinn.
 *
 * <h2>Der Aufruf ist idempotent und gehoert in jeden Anwendungsstart</h2>
 * Ueber den Hash der Adresse entsteht hoechstens eine Zeile je Browserinstallation, und ein
 * zuvor deaktiviertes Abonnement wird dabei wieder aktiv. <b>Das heilt genau den Fall, in dem
 * der Server nach einem {@code 410} deaktiviert hat, der Browser das Abonnement aber noch
 * fuehrt.</b>
 *
 * @param endpoint Adresse beim Push-Dienst. <b>Muss mit {@code https://} beginnen</b> - ohne
 *                 diese Pruefung liesse sich eine beliebige Adresse als Ziel hinterlegen, und
 *                 der Server schickte verschluesselte Nachrichten dorthin. Die Laenge ist in
 *                 RFC 8030 nicht begrenzt; 2048 Zeichen sind grosszuegig und begrenzen, was
 *                 in die Tabelle wandert
 * @param p256dh   oeffentlicher P-256-Schluessel als base64url. 65 Byte ergeben 88 Zeichen;
 *                 die Spanne 80 bis 120 laesst Raum fuer Polsterung und kuenftige Kodierungen,
 *                 ohne etwas offensichtlich Falsches durchzulassen
 * @param auth     Geheimnis des Abonnements als base64url. 16 Byte ergeben 22 Zeichen
 */
public record AboAnlegenRequest(
        @NotBlank(message = "Die Endpoint-Adresse darf nicht leer sein.")
        @Size(max = 2048, message = "Die Endpoint-Adresse ist zu lang.")
        @Pattern(regexp = "^https://.+", message = "Die Endpoint-Adresse muss mit https:// beginnen.")
        String endpoint,

        @NotBlank(message = "Der Schlüssel p256dh darf nicht leer sein.")
        @Size(min = 80, max = 120, message = "Der Schlüssel p256dh hat eine unzulässige Länge.")
        @Pattern(regexp = Base64Url.MUSTER, message = "Der Schlüssel p256dh ist kein base64url.")
        String p256dh,

        @NotBlank(message = "Das Geheimnis auth darf nicht leer sein.")
        @Size(min = 16, max = 32, message = "Das Geheimnis auth hat eine unzulässige Länge.")
        @Pattern(regexp = Base64Url.MUSTER, message = "Das Geheimnis auth ist kein base64url.")
        String auth) {

    /**
     * Entfernt Randleerzeichen aus der Endpoint-Adresse.
     *
     * <p>Die Auslegung des Anfragekoerpers gehoert an die API-Grenze und nicht in den Dienst -
     * dieselbe Regel wie bei {@code AdminLoginRequest#bereinigterAnmeldename()}. Ein
     * mitkopiertes Leerzeichen wuerde sonst den Hash veraendern und aus demselben Geraet ein
     * zweites machen.
     *
     * <p><b>Nur getrimmt, nicht normalisiert:</b> Die Adresse ist ein opakes Kennzeichen des
     * Push-Dienstes. Jede weitere Bearbeitung - Kleinschreibung, das Entfernen eines
     * abschliessenden Schraegstrichs - machte daraus eine andere Adresse.
     */
    public String bereinigterEndpoint() {
        return endpoint == null ? null : endpoint.trim();
    }
}
