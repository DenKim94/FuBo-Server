package de.fubo.appserver.service.push;

import de.fubo.appserver.common.config.PushConfig;
import de.fubo.appserver.common.config.VapidSchluessel;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Erzeugt den {@code Authorization}-Kopf einer Push-Anfrage (VAPID, RFC 8292; A25b, S8
 * Abschnitt 5.1).
 *
 * <h2>Wozu der Kopf da ist</h2>
 * Er weist den Absender gegenueber dem Push-Dienst aus - nicht gegenueber dem Empfaenger.
 * <b>Eine Registrierung bei Google, Mozilla oder Apple entfaellt damit</b>; ein Schluesselpaar
 * genuegt fuer alle Hersteller. Die Form ist
 * {@code Authorization: vapid t=<JWT>, k=<oeffentlicher Schluessel base64url>}.
 *
 * <h2>Der klassische Fehler dieses Verfahrens</h2>
 * <b>Signiert wird mit {@link PushConfig#SIGNATURVERFAHREN}, nicht mit
 * {@code SHA256withECDSA}.</b> Die Standardvariante liefert die <i>DER</i>-Form aus zwei
 * Ganzzahlen mit Laengenangaben; JWS verlangt die feste Form aus 64 Byte, erst R und dann S.
 * Eine DER-Signatur wird von den Push-Diensten mit {@code 401} abgelehnt - immerhin laut,
 * anders als ein Fehler in der Nutzlastverschluesselung.
 *
 * <p><b>Der Verfahrensname steht deshalb genau einmal</b>, naemlich als Konstante in
 * {@code PushConfig}, und wird von dort geholt. Ein zweiter Namensstring liefe auseinander,
 * und das Auseinanderlaufen zeigte sich wieder nur am {@code 401} eines fremden Dienstes.
 * Zugleich belegt die Startpruefung in {@code PushConfig} bereits, dass dieses Verfahren auf
 * der Laufzeitumgebung vorhanden ist.
 *
 * <h2>{@code sub} ist nach RFC 8292 optional - und trotzdem Pflicht</h2>
 * Der Wortlaut des RFC ist hier irrefuehrend. <b>Apple lehnt ein JWT ohne oder mit unsauber
 * formatiertem {@code sub} mit {@code BadJwtToken} ab</b>, bei einem ansonsten gueltigen
 * Token, und dokumentiert das nicht. Google und Mozilla nehmen die Nachricht auch ohne an -
 * <b>der Fehler faellt also nur auf einem iPhone auf</b>, und ein Probeversand in Chrome
 * belegt nichts. Der Wert ist eine URI mit Schema ({@code mailto:} oder {@code https:});
 * {@code PushConfig} prueft das beim Start und schaltet Push sonst ab.
 *
 * <h2>Ein Token je Anfrage, nicht je Lauf</h2>
 * Der {@code aud}-Anspruch ist der <b>Origin des Endpoints</b> - er unterscheidet sich je
 * Browserhersteller und damit je Empfaenger. Ein Token fuer alle Empfaenger eines Laufs
 * gaebe es nur, wenn alle beim selben Dienst liegen; die Ersparnis waere eine
 * Signaturberechnung, der Preis ein Token, das bei gemischten Empfaengern von der Haelfte
 * der Dienste abgelehnt wird.
 */
@Component
public class VapidJwtErzeuger {

    /**
     * Lebensdauer des Tokens.
     *
     * <p>RFC 8292 erlaubt hoechstens 24 Stunden; zwoelf sind der ueblich verwendete Wert und
     * lassen Raum fuer eine Uhr, die um Minuten abweicht. <b>Laenger waere kein Gewinn</b> -
     * das Token wird je Anfrage neu erzeugt und nie zwischengespeichert.
     */
    private static final Duration GUELTIGKEIT = Duration.ofHours(12);

    /** Fester Kopf des JWT: {@code ES256} ist das Verfahren, das RFC 8292 vorschreibt. */
    private static final String KOPF_JSON = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}";

    /** base64url <b>ohne</b> Polsterung - JWS laesst Polsterzeichen nicht zu. */
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final VapidSchluessel schluessel;
    private final Clock uhr;

    public VapidJwtErzeuger(VapidSchluessel schluessel, Clock uhr) {
        this.schluessel = schluessel;
        this.uhr = uhr;
    }

    /**
     * Baut den Wert des {@code Authorization}-Kopfes fuer einen Endpoint.
     *
     * @param endpoint Adresse des Abonnements beim Push-Dienst
     * @return {@code vapid t=<JWT>, k=<oeffentlicher Schluessel>}
     * @throws GeneralSecurityException wenn die Signatur nicht erzeugt werden kann
     * @throws IllegalStateException    wenn Push nicht eingerichtet ist; der Aufrufer prueft
     *                                 das ueber {@link VapidSchluessel#eingerichtet()}, bevor
     *                                 er ueberhaupt versendet
     */
    public String autorisierungskopf(String endpoint) throws GeneralSecurityException {
        if (!schluessel.eingerichtet()) {
            throw new IllegalStateException(
                    "Push ist nicht eingerichtet; es darf kein VAPID-Kopf erzeugt werden.");
        }
        return "vapid t=%s, k=%s".formatted(jwt(origin(endpoint)),
                schluessel.oeffentlichBase64Url());
    }

    /**
     * Erzeugt das signierte JWT.
     *
     * <p>Die Reihenfolge der Ansprueche im Rumpf ist ohne Bedeutung; sie steht fest, damit
     * zwei Laeufe mit derselben Uhr dasselbe Token ergeben und ein Testfall die Form
     * vergleichen kann.
     */
    private String jwt(String origin) throws GeneralSecurityException {
        long ablauf = Instant.now(uhr).plus(GUELTIGKEIT).getEpochSecond();

        String kopf = base64url(KOPF_JSON);
        String rumpf = base64url("{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}"
                .formatted(origin, ablauf, maskiere(schluessel.subject())));

        // Der Signaturgegenstand ist die ASCII-Form von "<Kopf>.<Rumpf>" - nicht die
        // dekodierten Bytes. Wer hier die Rohdaten signiert, bekommt ein Token, das keine
        // Gegenseite pruefen kann.
        byte[] eingabe = (kopf + "." + rumpf).getBytes(StandardCharsets.US_ASCII);

        Signature signierer = Signature.getInstance(PushConfig.SIGNATURVERFAHREN);
        signierer.initSign(schluessel.privat());
        signierer.update(eingabe);

        return kopf + "." + rumpf + "." + BASE64URL.encodeToString(signierer.sign());
    }

    /**
     * Der {@code aud}-Anspruch: der <b>Origin</b> des Endpoints, nicht seine vollstaendige
     * Adresse.
     *
     * <p>Also Schema und Host - und den Port nur, wenn er ausdruecklich dabeisteht. Die
     * vollstaendige Adresse enthaelt einen abonnementbezogenen Pfad; ein Token darauf waere
     * an ein einzelnes Geraet gebunden, und der Dienst lehnte es ab.
     *
     * @param endpoint Adresse des Abonnements
     * @return der Origin
     * @throws IllegalArgumentException wenn die Adresse keine brauchbare URI ist. Sie kommt
     *                                 aus der Datenbank und ist beim Anlegen geprueft worden;
     *                                 der Fall bleibt ein Fehlversuch und kein Abbruch des
     *                                 ganzen Laufs
     */
    private static String origin(String endpoint) {
        URI adresse = URI.create(endpoint);
        if (adresse.getScheme() == null || adresse.getHost() == null) {
            throw new IllegalArgumentException(
                    "Endpoint ohne Schema oder Host, Origin nicht bestimmbar.");
        }
        String origin = adresse.getScheme() + "://" + adresse.getHost();
        return adresse.getPort() == -1 ? origin : origin + ":" + adresse.getPort();
    }

    /** base64url einer Zeichenkette, UTF-8, ohne Polsterung. */
    private static String base64url(String text) {
        return BASE64URL.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Maskiert die beiden Zeichen, die einen JSON-Text zerreissen wuerden.
     *
     * <p><b>Von Hand und nicht ueber die Serialisierungsbibliothek:</b> Der Rumpf hat drei
     * feste Felder, zwei davon erzeugt der Server selbst. Eine Bibliothek dafuer
     * einzuspannen brauchte eine weitere Abhaengigkeit in dieser Klasse - und die Reihenfolge
     * der Felder waere dann nicht mehr festgelegt.
     *
     * <p>Betroffen ist allein {@code sub}: Er kommt aus der Umgebung. {@code PushConfig}
     * verlangt bereits ein Schema am Anfang; ein Anfuehrungszeichen darin waere pathologisch,
     * aber es wuerde ein unbrauchbares Token erzeugen, und der Fehler zeigte sich nur als
     * {@code 401}.
     */
    private static String maskiere(String wert) {
        return wert.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
