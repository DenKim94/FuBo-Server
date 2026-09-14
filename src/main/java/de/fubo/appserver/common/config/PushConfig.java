package de.fubo.appserver.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Bereitet das VAPID-Schluesselpaar beim Start auf (A25b, S8).
 *
 * <h2>Vorbild {@link MailConfig}, mit einer begruendeten Abweichung</h2>
 * Das Muster ist dasselbe: Ein externer Zugang wird unter {@code fubo.*} gebunden, die Bean
 * entsteht von Hand, und die Werte werden beim Start geprueft - nicht erst beim ersten
 * Versand. <b>Der Unterschied liegt in der Reaktion:</b> {@code MailConfig} bricht den Start
 * ab, hier laeuft er weiter. Der Grund ist der Handel dahinter - ohne SMTP gibt es keinen Weg
 * zurueck, wenn das Adminpasswort vergessen ist; ohne Push laeuft der Kernbetrieb
 * vollstaendig. Ein Startabbruch waere hier unverhaeltnismaessig, eine stillschweigend
 * kaputte Konfiguration aber auch nicht hinnehmbar. Deshalb: eine Warnung, die die
 * Umgebungsvariable benennt, und der Zustand "nicht eingerichtet".
 *
 * <h2>Drei Pruefungen, und jede faengt einen anderen Fehler</h2>
 * <ol>
 *   <li><b>Leer oder unaufgeloester Platzhalter.</b> Spring Boots {@code Binder} reicht ein
 *       {@code ${...}} <b>woertlich</b> durch. Ohne diese Pruefung liefe der Server mit dem
 *       oeffentlichen Schluessel <code>"${FUBO_VAPID_PUBLIC_KEY}"</code>, und der Push-Dienst
 *       lehnte jede Nachricht mit einer Meldung ab, die niemanden zur {@code .env} fuehrt.
 *       <b>Merkregel: Ein {@code $}-Zeichen mit geschweifter Klammer in einer Meldung bedeutet
 *       immer fehlende Aufloesung, nie einen falschen Wert.</b></li>
 *   <li><b>Form und Laenge.</b> Der oeffentliche Schluessel ist ein unkomprimierter P-256-Punkt:
 *       65 Byte, beginnend mit {@code 0x04}. Der private ist der Skalar: genau 32 Byte.</li>
 *   <li><b>Ob die beiden zueinander gehoeren</b> - die wichtigste der drei, siehe unten.</li>
 * </ol>
 *
 * <h2>Warum die Gegenprobe mit Signatur und Pruefung laeuft</h2>
 * Der private Skalar steht in der DER-Struktur eines EC-Schluessels am <b>Anfang</b>, nicht am
 * Ende - die letzten 32 Byte sind die Y-Koordinate des <i>oeffentlichen</i> Punktes. Wer beim
 * Erzeugen {@code tail -c 32} statt {@code tail -c +8 | head -c 32} schreibt, bekommt einen
 * Wert, der <b>genauso lang ist und genauso aussieht</b> - und oeffentlich bekannt ist. Eine
 * Laengenpruefung faengt das nicht ab, und im Betrieb zeigte es sich als {@code 401} vom
 * Push-Dienst, Wochen spaeter und ohne Hinweis auf die Ursache.
 *
 * <p>Eine Signatur, die mit dem privaten Schluessel entsteht und mit dem oeffentlichen geprueft
 * wird, beantwortet die Frage abschliessend. <b>Sie prueft nebenbei noch etwas Zweites:</b> Der
 * Algorithmusname {@code SHA256withECDSAinP1363Format} ist derselbe, den das VAPID-JWT
 * braucht - die Standardvariante {@code SHA256withECDSA} lieferte die DER-Form, die von den
 * Push-Diensten mit {@code 401} abgelehnt wird. Ist der Name auf dieser Laufzeitumgebung nicht
 * verfuegbar, faellt das hier auf und nicht beim ersten Versand.
 */
@Configuration
public class PushConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PushConfig.class);

    /** Kennzeichen eines Platzhalters, den Spring nicht aufloesen konnte. */
    private static final String UNAUFGELOESTER_PLATZHALTER = "${";

    /** Benannte Kurve des Web-Push-Verfahrens (RFC 8291, RFC 8292): P-256. */
    private static final String KURVE = "secp256r1";

    /**
     * Signaturverfahren des VAPID-JWT (RFC 8292, {@code alg = ES256}).
     *
     * <p><b>Nicht {@code SHA256withECDSA}.</b> Die Standardvariante liefert die DER-Form; JWS
     * verlangt die feste Form aus 64 Byte, erst R und dann S. Das ist der klassische Fehler
     * dieses Verfahrens - immerhin ein lauter, denn die Push-Dienste antworten darauf mit
     * {@code 401}.
     *
     * <p><b>Die Konstante ist oeffentlich, weil der JWT-Erzeuger dasselbe Verfahren braucht.</b>
     * Ein zweiter Namensstring dort waere eine zweite Wahrheit - und eine, deren Abweichen sich
     * erst am {@code 401} eines fremden Dienstes zeigte.
     */
    public static final String SIGNATURVERFAHREN = "SHA256withECDSAinP1363Format";

    /** Laenge des unkomprimierten Punktes: ein Kennbyte und zwei Koordinaten zu 32 Byte. */
    private static final int LAENGE_PUNKT = 65;

    /** Laenge des privaten Skalars auf P-256. */
    private static final int LAENGE_SKALAR = 32;

    /** Kennbyte eines unkomprimierten Punktes nach SEC 1. */
    private static final byte PUNKT_UNKOMPRIMIERT = 0x04;

    /**
     * Liest die drei Werte, dekodiert das Paar und prueft es gegen sich selbst.
     *
     * <p>Bricht nie ab: Jeder Fehler endet in einer Warnung und dem Zustand "nicht
     * eingerichtet". Aufrufer erkennen ihn an {@link VapidSchluessel#eingerichtet()}.
     *
     * @param eigenschaften gebundene Anwendungskonfiguration
     * @return das aufbereitete Paar, oder der Zustand "nicht eingerichtet"
     */
    @Bean
    VapidSchluessel vapidSchluessel(FuboProperties eigenschaften) {
        FuboProperties.Push konfiguration = eigenschaften.push();

        if (fehlt(konfiguration.vapidPublicKey(), "fubo.push.vapid-public-key", "FUBO_VAPID_PUBLIC_KEY")
                | fehlt(konfiguration.vapidPrivateKey(), "fubo.push.vapid-private-key", "FUBO_VAPID_PRIVATE_KEY")
                | fehlt(konfiguration.vapidSubject(), "fubo.push.vapid-subject", "FUBO_VAPID_SUBJECT")) {
            LOG.warn("Push-Benachrichtigungen sind nicht eingerichtet - die Anwendung laeuft "
                    + "ohne sie weiter. /api/v1/push/schluessel/lesen antwortet mit 503, und der "
                    + "Erinnerungsauftrag versendet nichts.");
            return VapidSchluessel.nichtEingerichtet();
        }

        // Alle drei Meldungen werden erzeugt, nicht nur die erste: Wer zwei Variablen
        // vergessen hat, soll beide Namen in einem Durchgang sehen. Deshalb steht oben ein
        // einfaches | und kein ||.

        String subject = konfiguration.vapidSubject().trim();
        if (!subject.startsWith("mailto:") && !subject.startsWith("https:")) {
            LOG.warn("fubo.push.vapid-subject ist keine URI: '{}'. RFC 8292 verlangt eine "
                    + "Kontaktadresse mit Schema, ueblich ist mailto:adresse@domain "
                    + "(FUBO_VAPID_SUBJECT). Eine nackte Adresse ohne Praefix genuegt nicht - "
                    + "Apple lehnt das JWT dann mit BadJwtToken ab, und der Fehler faellt NUR "
                    + "auf einem iPhone auf. Push bleibt abgeschaltet.", subject);
            return VapidSchluessel.nichtEingerichtet();
        }

        try {
            ECParameterSpec kurve = kurvenParameter();

            byte[] punkt = dekodiere(konfiguration.vapidPublicKey());
            if (punkt.length != LAENGE_PUNKT || punkt[0] != PUNKT_UNKOMPRIMIERT) {
                LOG.warn("fubo.push.vapid-public-key hat nicht die erwartete Form: {} Byte statt "
                                + "{}, erstes Byte 0x{}. Erwartet wird der unkomprimierte P-256-Punkt "
                                + "(87 Zeichen base64url, beginnend mit 'B'). Push bleibt "
                                + "abgeschaltet.",
                        punkt.length, LAENGE_PUNKT, String.format("%02x", punkt.length == 0 ? 0 : punkt[0] & 0xff));
                return VapidSchluessel.nichtEingerichtet();
            }

            byte[] skalar = dekodiere(konfiguration.vapidPrivateKey());
            if (skalar.length != LAENGE_SKALAR) {
                LOG.warn("fubo.push.vapid-private-key hat {} Byte statt {}. Erwartet wird der "
                                + "private Skalar (43 Zeichen base64url). Push bleibt abgeschaltet.",
                        skalar.length, LAENGE_SKALAR);
                return VapidSchluessel.nichtEingerichtet();
            }

            KeyFactory fabrik = KeyFactory.getInstance("EC");
            ECPublicKey oeffentlich = (ECPublicKey) fabrik.generatePublic(new ECPublicKeySpec(
                    new ECPoint(koordinate(punkt, 1), koordinate(punkt, 1 + LAENGE_SKALAR)), kurve));
            ECPrivateKey privat = (ECPrivateKey) fabrik.generatePrivate(
                    new ECPrivateKeySpec(new BigInteger(1, skalar), kurve));

            if (!passenZueinander(oeffentlich, privat)) {
                LOG.warn("Das VAPID-Schluesselpaar passt nicht zusammen: Eine mit "
                        + "FUBO_VAPID_PRIVATE_KEY erzeugte Signatur laesst sich mit "
                        + "FUBO_VAPID_PUBLIC_KEY nicht pruefen. Haeufigste Ursache ist ein falsch "
                        + "ausgelesener privater Schluessel - der Skalar steht am ANFANG der "
                        + "DER-Struktur, nicht am Ende; die letzten 32 Byte sind die "
                        + "Y-Koordinate des oeffentlichen Punktes und damit oeffentlich bekannt. "
                        + "Ein so entstandener Wert ist genauso lang wie ein richtiger. Push "
                        + "bleibt abgeschaltet.");
                return VapidSchluessel.nichtEingerichtet();
            }

            LOG.info("Push-Benachrichtigungen sind eingerichtet (VAPID, P-256); Kontaktadresse {}.",
                    subject);
            return new VapidSchluessel(oeffentlich, privat,
                    konfiguration.vapidPublicKey().trim(), subject, true);

        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // IllegalArgumentException kommt aus dem base64url-Dekodierer, wenn der Wert
            // Zeichen ausserhalb des Alphabets traegt - etwa '+' und '/' aus der
            // Standardvariante von Base64 oder ein Polsterzeichen.
            LOG.warn("Das VAPID-Schluesselpaar liess sich nicht aufbereiten: {}. Erwartet wird "
                    + "base64url ohne Polsterung. Push bleibt abgeschaltet.", e.toString());
            return VapidSchluessel.nichtEingerichtet();
        }
    }

    /**
     * Meldet einen fehlenden oder unaufgeloesten Wert und gibt zurueck, ob er fehlt.
     *
     * @param wert       gebundener Konfigurationswert
     * @param schluessel Name der Eigenschaft, fuer die Meldung
     * @param variable   zugehoerige Umgebungsvariable, fuer die Meldung
     * @return {@code true}, wenn der Wert unbrauchbar ist
     */
    private static boolean fehlt(String wert, String schluessel, String variable) {
        if (wert == null || wert.isBlank()) {
            LOG.warn("{} ist nicht gesetzt ({} fehlt in der Umgebung bzw. der .env).",
                    schluessel, variable);
            return true;
        }
        if (wert.contains(UNAUFGELOESTER_PLATZHALTER)) {
            LOG.warn("{} enthaelt einen unaufgeloesten Platzhalter - die Variable {} fehlt in "
                    + "der Umgebung bzw. der .env. Spring reicht solche Platzhalter woertlich "
                    + "durch; ohne diese Pruefung liefe die Anwendung mit dem Platzhalter als "
                    + "Schluessel weiter.", schluessel, variable);
            return true;
        }
        return false;
    }

    /**
     * Die Parameter der Kurve P-256.
     *
     * <p>Sie kommen aus der Laufzeitumgebung und stehen nicht als Zahlen im Code: Eine
     * abgeschriebene Kurvendefinition waere eine zweite Wahrheit, die niemand nachrechnet.
     */
    private static ECParameterSpec kurvenParameter() throws GeneralSecurityException {
        AlgorithmParameters parameter = AlgorithmParameters.getInstance("EC");
        parameter.init(new ECGenParameterSpec(KURVE));
        return parameter.getParameterSpec(ECParameterSpec.class);
    }

    /** Liest eine 32-Byte-Koordinate ab der angegebenen Stelle, vorzeichenlos. */
    private static BigInteger koordinate(byte[] punkt, int ab) {
        return new BigInteger(1, Arrays.copyOfRange(punkt, ab, ab + LAENGE_SKALAR));
    }

    /** base64url ohne Polsterung, wie RFC 8291 und RFC 8292 es durchgaengig verwenden. */
    private static byte[] dekodiere(String wert) {
        return Base64.getUrlDecoder().decode(wert.trim());
    }

    /**
     * Prueft, ob der private Schluessel zum oeffentlichen gehoert.
     *
     * <p>Signieren und Pruefen mit demselben Verfahren, das spaeter das VAPID-JWT traegt. Die
     * Nachricht ist beliebig; sie muss nur bei beiden Schritten dieselbe sein und nicht
     * vorhersagbar, damit die Pruefung auch dann etwas aussagt, wenn eine Umsetzung
     * Zwischenergebnisse zwischenspeichert.
     */
    private static boolean passenZueinander(ECPublicKey oeffentlich, ECPrivateKey privat)
            throws GeneralSecurityException {
        byte[] probe = new byte[32];
        new SecureRandom().nextBytes(probe);

        Signature signierer = Signature.getInstance(SIGNATURVERFAHREN);
        signierer.initSign(privat);
        signierer.update(probe);
        byte[] signatur = signierer.sign();

        Signature pruefer = Signature.getInstance(SIGNATURVERFAHREN);
        pruefer.initVerify(oeffentlich);
        pruefer.update(probe);
        return pruefer.verify(signatur);
    }
}
