package de.fubo.appserver.service.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.domain.push.PushAbo;
import de.fubo.appserver.domain.push.PushAntwort;
import de.fubo.appserver.domain.push.PushNutzlast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Der Versandadapter zu den Push-Diensten der Browserhersteller (RFC 8030; A25b, S8
 * Abschnitt 6).
 *
 * <h2>Rein ausgehend</h2>
 * Der Server spricht {@code fcm.googleapis.com}, {@code *.push.services.mozilla.com} und
 * {@code web.push.apple.com} ueber ausgehendes HTTPS an. <b>Es entsteht kein neuer
 * eingehender Endpunkt</b> - an Nginx und am Cloudflared-Tunnel aendert sich nichts. Der
 * Server nimmt auch keinen Versandauftrag entgegen: Der Client legt sein Abonnement an und
 * widerruft es, mehr nicht.
 *
 * <h2>Diese Klasse entscheidet nichts</h2>
 * Sie verschluesselt, schickt und uebersetzt die Antwort - <b>die Zuordnung von Statuscode zu
 * Reaktion liegt in {@code PushAntwort#von}</b>, das Schreiben in die Datenbank beim
 * aufrufenden Dienst. So gibt es genau eine Stelle, an der "was bedeutet ein 410" steht.
 *
 * <h2>Sie gibt nie eine Ausnahme heraus</h2>
 * Auch keine synchrone: Ein unbrauchbarer {@code p256dh}, eine Endpoint-Adresse ohne Host
 * oder ein Fehler in der Serialisierung enden als {@link PushAntwort} und nicht als Abbruch.
 * <b>Der Grund liegt zwei Ebenen hoeher:</b> Der Listener der Terminabsage laeuft in einem
 * {@code AFTER_COMMIT}-Callback, und eine Ausnahme von dort propagiert zum Aufrufer, obwohl
 * der Commit schon durch ist - der Admin bekaeme einen {@code 500} fuer eine Absage, die
 * gespeichert wurde, und druecke ein zweites Mal.
 *
 * <h2>Zur Zeitgrenze je Aufruf</h2>
 * Sie ist derselbe Wert wie die Gesamtfrist des Laufs
 * ({@code fubo.push.versand-frist-millis}), und das ist Absicht: <b>Kein einzelner Aufruf
 * darf laenger dauern als der ganze Lauf.</b> Der Aufrufer bricht nach Ablauf der Gesamtfrist
 * ohnehin ab; diese Grenze hier ist der Riegel fuer den Fall, dass ein Abbruch den laufenden
 * Austausch nicht erreicht - ein weiterlaufender Aufruf schriebe sein Ergebnis sonst in einen
 * Vorgang, den es nicht mehr gibt.
 */
@Component
public class WebPushVersender implements PushVersender {

    private static final Logger LOG = LoggerFactory.getLogger(WebPushVersender.class);

    /**
     * Wie lange der Push-Dienst die Nachricht aufbewahrt, wenn das Geraet offline ist
     * (RFC 8030, Kopf {@code TTL}).
     *
     * <p>24 Stunden: Beide Anlaesse verlieren danach ihren Sinn - eine Erinnerung an ein
     * Training, das schon lief, und eine Absage, von der der Empfaenger inzwischen anders
     * erfahren hat. <b>Ein fehlender {@code TTL}-Kopf ist kein Standardwert, sondern ein
     * Fehler</b>: RFC 8030 macht ihn zur Pflicht, und manche Dienste lehnen die Nachricht
     * ohne ihn ab.
     */
    private static final String TTL_SEKUNDEN = "86400";

    /**
     * Dringlichkeit (RFC 8030).
     *
     * <p>{@code normal} heisst: zustellen, sobald das Geraet erreichbar ist, aber es nicht
     * dafuer aufwecken. {@code high} waere fuer eine Rueckmeldeaufforderung
     * unverhaeltnismaessig und kostet auf dem Empfaengergeraet Batterie.
     */
    private static final String DRINGLICHKEIT = "normal";

    /** Hoechstlaenge eines protokollierten Antwortkoerpers; er dient nur der Fehlersuche. */
    private static final int MAX_LOG_KOERPER = 200;

    private final HttpClient client;
    private final VapidJwtErzeuger jwtErzeuger;
    private final Nutzlastverschluesselung verschluesselung;
    private final ObjectMapper serialisierer;
    private final Duration zeitgrenze;

    public WebPushVersender(HttpClient client,
                            VapidJwtErzeuger jwtErzeuger,
                            Nutzlastverschluesselung verschluesselung,
                            ObjectMapper serialisierer,
                            FuboProperties eigenschaften) {
        this.client = client;
        this.jwtErzeuger = jwtErzeuger;
        this.verschluesselung = verschluesselung;
        this.serialisierer = serialisierer;
        this.zeitgrenze = Duration.ofMillis(eigenschaften.push().versandFristMillis());
    }

    /**
     * Baut die Anfrage und schickt sie ab.
     *
     * <p><b>Die Nutzlast wird je Empfaenger neu verschluesselt</b>, nicht je Lauf einmal:
     * RFC 8291 bindet die Verschluesselung an die Schluessel des Abonnements. Der Klartext ist
     * fuer alle derselbe, das Chiffrat nie.
     *
     * <p><b>Die Groessenpruefung steht hier und nicht erst in der Verschluesselung</b>, obwohl
     * sie dort ein zweites Mal vorkommt: Hier entsteht daraus eine benennbare Antwort
     * ({@code NUTZLAST_ZU_GROSS}), die das Abonnement unangetastet laesst - dort ein Abbruch.
     * Ein {@code 413} des Dienstes waere dasselbe Ergebnis, nur eine Netzrunde spaeter und mit
     * einer fremden Grenze.
     */
    @Override
    public CompletableFuture<PushAntwort> versende(PushAbo abo, PushNutzlast nutzlast) {
        byte[] klartext;
        try {
            klartext = serialisierer.writeValueAsBytes(nutzlast);
        } catch (JsonProcessingException e) {
            // Ein Anwendungsfehler, kein Abonnementfehler: Er traefe jeden Empfaenger gleich.
            LOG.error("Push-Nutzlast liess sich nicht serialisieren (Termin {}).",
                    nutzlast.terminId(), e);
            return CompletableFuture.completedFuture(PushAntwort.nutzlastZuGross());
        }

        if (klartext.length > Nutzlastverschluesselung.MAX_KLARTEXT_BYTES) {
            LOG.error("Push-Nutzlast mit {} Byte ueberschreitet die Grenze von {} Byte "
                            + "(Termin {}); es wird nichts versendet.",
                    klartext.length, Nutzlastverschluesselung.MAX_KLARTEXT_BYTES,
                    nutzlast.terminId());
            return CompletableFuture.completedFuture(PushAntwort.nutzlastZuGross());
        }

        HttpRequest anfrage;
        try {
            anfrage = HttpRequest.newBuilder(URI.create(abo.endpoint()))
                    .timeout(zeitgrenze)
                    .header("Authorization", jwtErzeuger.autorisierungskopf(abo.endpoint()))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", TTL_SEKUNDEN)
                    .header("Urgency", DRINGLICHKEIT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            verschluesselung.verschluesseln(abo, klartext)))
                    .build();
        } catch (Exception e) {
            // Bewusst breit: unbrauchbarer p256dh oder auth (GeneralSecurityException,
            // IllegalArgumentException aus dem base64url-Dekodierer), eine Endpoint-Adresse
            // ohne Schema oder Host, ein fehlendes Krypto-Verfahren. Alle enden als
            // Fehlversuch dieses einen Abonnements - und keiner davon darf den Lauf
            // abbrechen, in dem noch neunundzwanzig andere warten.
            LOG.warn("Push-Anfrage fuer Abonnement {} liess sich nicht aufbauen: {}",
                    abo.id(), e.toString());
            return CompletableFuture.completedFuture(PushAntwort.ohneAntwort());
        }

        return client.sendAsync(anfrage, HttpResponse.BodyHandlers.ofString())
                .thenApply(antwort -> auswerten(abo, antwort))
                .exceptionally(fehler -> {
                    // Netzfehler, Zeitgrenze, Abbruch nach Ablauf der Gesamtfrist. Die
                    // Meldung bleibt kurz: Ein dauerhaft unerreichbarer Dienst erzeugt sie
                    // je Empfaenger und Lauf.
                    LOG.warn("Push an Abonnement {} fehlgeschlagen: {}", abo.id(),
                            fehler.getMessage());
                    return PushAntwort.ohneAntwort();
                });
    }

    /**
     * Uebersetzt die HTTP-Antwort und protokolliert, was zur Fehlersuche taugt.
     *
     * <p><b>Der Antwortkoerper wird nur bei einem Fehlschlag protokolliert</b>, gekuerzt: Bei
     * einem {@code 401} nennt er die Ursache - falscher Schluessel, unbrauchbarer
     * {@code sub} -, und genau dieser Fall ist ohne ihn kaum aufzuklaeren. Bei Erfolg ist er
     * leer und traegt nichts bei.
     *
     * <p><b>Kein Log je erfolgreicher Nachricht:</b> Bei dreissig Empfaengern waeren das
     * dreissig Zeilen je Termin. Die Zahl steht im Audit-Eintrag des Laufs.
     */
    private static PushAntwort auswerten(PushAbo abo, HttpResponse<String> antwort) {
        PushAntwort ergebnis = PushAntwort.von(antwort.statusCode());

        switch (ergebnis.ergebnis()) {
            case ZUGESTELLT -> {
                // Bewusst stumm.
            }
            case ERLOSCHEN -> LOG.info(
                    "Abonnement {} ist beim Push-Dienst erloschen ({}); es wird deaktiviert. "
                            + "Meldet der Browser es erneut an, wird es wieder aktiv.",
                    abo.id(), antwort.statusCode());
            case SCHLUESSEL_ABGELEHNT -> LOG.error(
                    "Der Push-Dienst hat unsere Berechtigung abgelehnt ({}): {}. Die Ursache "
                            + "liegt beim VAPID-Zugang - falsches Schluesselpaar, unbrauchbarer "
                            + "sub-Anspruch oder eine Signatur in DER-Form. Das Abonnement {} "
                            + "bleibt unangetastet.",
                    antwort.statusCode(), gekuerzt(antwort.body()), abo.id());
            case NUTZLAST_ZU_GROSS -> LOG.error(
                    "Der Push-Dienst hat die Nutzlast als zu gross abgelehnt ({}): {}. Das ist "
                            + "ein Anwendungsfehler; Abonnement {} bleibt unangetastet.",
                    antwort.statusCode(), gekuerzt(antwort.body()), abo.id());
            case FEHLVERSUCH -> LOG.warn(
                    "Der Push-Dienst hat Abonnement {} nicht bedient ({}): {}. Diese Nachricht "
                            + "wird nicht wiederholt.",
                    abo.id(), antwort.statusCode(), gekuerzt(antwort.body()));
        }
        return ergebnis;
    }

    /** Kuerzt einen Antwortkoerper auf ein Mass, das in eine Logzeile passt. */
    private static String gekuerzt(String koerper) {
        if (koerper == null || koerper.isBlank()) {
            return "(ohne Inhalt)";
        }
        String einzeilig = koerper.strip().replaceAll("\\s+", " ");
        return einzeilig.length() <= MAX_LOG_KOERPER
                ? einzeilig
                : einzeilig.substring(0, MAX_LOG_KOERPER) + "...";
    }
}
