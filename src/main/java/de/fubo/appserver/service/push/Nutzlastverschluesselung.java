package de.fubo.appserver.service.push;

import de.fubo.appserver.domain.push.PushAbo;
import de.fubo.appserver.utils.P256;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * Verschluesselt eine Nutzlast fuer genau ein Abonnement (RFC 8291, {@code aes128gcm}; A25b,
 * S8 Abschnitt 5.2).
 *
 * <h2>Die eine Eigenschaft, die diesen Abschnitt gefaehrlich macht</h2>
 * <b>Ein Fehler hier faellt nicht auf.</b> Der Push-Dienst verschluesselt nichts und prueft
 * nichts - er nimmt die Bytes entgegen und antwortet {@code 201}. Beim Empfaenger wird die
 * Nachricht stillschweigend verworfen, und der Absender sieht Erfolg. Es gibt keine
 * Rueckmeldung, aus der sich ableiten liesse, ob die Umsetzung stimmt.
 *
 * <p><b>Deshalb ist der Testvektor aus RFC 8291, Anhang A Bedingung und nicht Empfehlung.</b>
 * Der Anhang nennt saemtliche Zwischenwerte - Salz, ephemeres Paar, gemeinsames Geheimnis,
 * abgeleiteten Schluessel, Nonce und das fertige Ergebnis. Ein Fall, der mit diesen Eingaben
 * genau die dort angegebene Ausgabe erzeugt, ist der einzige Beleg, dass es funktioniert; und
 * weil die Zwischenwerte einzeln dastehen, grenzt er einen Fehler zugleich <b>schrittweise</b>
 * ein.
 *
 * <p>Damit dieser Fall ueberhaupt moeglich ist, sind Salz und ephemeres Schluesselpaar von
 * aussen setzbar: {@link #verschluesseln(byte[], byte[], byte[], byte[], ECPrivateKey, byte[])}
 * nimmt beides entgegen, die oeffentliche Fassung zieht beides mit {@code SecureRandom} und
 * ruft sie auf. Dasselbe Muster wie bei {@code SeedQuelle} in S5.
 *
 * <h2>Keine Fremdbibliothek</h2>
 * Alles Noetige liegt im JDK 25: {@link KeyAgreement} fuer ECDH, {@link KDF} fuer
 * HKDF-SHA256, {@link Cipher} fuer AES-GCM. {@code nl.martijndwars:web-push} zoege
 * {@code bcprov-jdk15on} in einer abgekuendigten Artefaktlinie, zwei zusaetzliche
 * HTTP-Stacks und einen Kommandozeilen-Parser nach - keiner davon passend zu Spring Boot 4.1
 * und Java 25. <b>S8 fuegt {@code pom.xml} keine Abhaengigkeit hinzu.</b>
 *
 * <h2>Der Weg in fuenf Schritten</h2>
 * <ol>
 *   <li><b>ECDH</b> zwischen dem ephemeren privaten Schluessel und dem {@code p256dh} des
 *       Abonnements ergibt 32 Byte gemeinsames Geheimnis.</li>
 *   <li><b>Erste HKDF-Stufe</b> mit dem {@code auth}-Geheimnis des Abonnements als Salz und
 *       {@code "WebPush: info"} samt beiden oeffentlichen Schluesseln als {@code info} ergibt
 *       das Eingangsmaterial (32 Byte). <b>Diese Stufe ist die Zutat aus RFC 8291</b> - sie
 *       bindet die Ableitung an genau dieses Abonnement.</li>
 *   <li><b>Zweite HKDF-Stufe</b>, zweimal mit dem zufaelligen Salz: Inhaltsschluessel
 *       (16 Byte) und Nonce (12 Byte). Das ist RFC 8188 und mit jeder anderen
 *       {@code aes128gcm}-Umsetzung gemeinsam.</li>
 *   <li><b>AES-128-GCM</b> ueber den Klartext mit angehaengtem Abschlussbyte
 *       {@code 0x02}.</li>
 *   <li><b>Kopfblock voranstellen:</b> Salz, Datensatzgroesse, Laenge des Schluessels und der
 *       ephemere oeffentliche Schluessel. Er reist mit, weil der Empfaenger ihn fuer sein
 *       eigenes ECDH braucht.</li>
 * </ol>
 *
 * <h2>Warum eine Bean und keine Sammlung statischer Methoden</h2>
 * Sie haelt eine {@code SecureRandom}-Instanz - das Nachsaeen kostet beim ersten Aufruf
 * einmalig Zeit, und je Nachricht eine neue Instanz zu bauen hiesse, diesen Preis dauernd zu
 * zahlen. Dieselbe Ueberlegung wie bei {@code SeedQuelle}. Die zustandslosen Teile - Kurve
 * und Schluesselformat - liegen in {@link P256}.
 */
@Component
public class Nutzlastverschluesselung {

    /**
     * Obergrenze des Klartexts in Byte.
     *
     * <p>{@code aes128gcm} fuegt 86 Byte Kopfblock und 16 Byte Authentifizierungsanhang
     * hinzu, die Gesamtgrenze der Push-Dienste liegt bei 4 096 Byte. <b>3 000 ist mit
     * Abstand gewaehlt</b>, nicht ausgereizt: Die Felder von
     * {@code de.fubo.appserver.domain.push.PushNutzlast} sind klein, und ein {@code 413} im
     * Betrieb waere ein Anwendungsfehler, den niemand bemerkt - er sieht aus wie ein
     * Abonnementfehler.
     */
    public static final int MAX_KLARTEXT_BYTES = 3_000;

    /**
     * Datensatzgroesse im Kopfblock (RFC 8188, Feld {@code rs}).
     *
     * <p>Sie gibt die Groesse an, bis zu der ein Datensatz reichen <i>darf</i> - nicht seine
     * tatsaechliche Laenge. Es wird genau ein Datensatz erzeugt, und mit
     * {@link #MAX_KLARTEXT_BYTES} bleibt er weit darunter. 4 096 ist der Wert, den auch der
     * Testvektor aus RFC 8291, Anhang A verwendet; <b>ein abweichender Wert liefert ein
     * anderes Ergebnis</b> und liesse den Fall scheitern, obwohl die Rechnung stimmt.
     */
    private static final int DATENSATZGROESSE = 4_096;

    /**
     * Abschlussbyte des letzten Datensatzes (RFC 8188).
     *
     * <p>{@code 0x02} heisst "letzter Datensatz", {@code 0x01} hiesse "es folgt ein weiterer".
     * Fehlt das Byte, scheitert die Entschluesselung beim Empfaenger - und zwar stumm.
     */
    private static final byte ABSCHLUSS_LETZTER_DATENSATZ = 0x02;

    /** {@code info} der ersten HKDF-Stufe; das abschliessende Nullbyte gehoert dazu. */
    private static final byte[] INFO_SCHLUESSEL =
            "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);

    /** {@code info} fuer den Inhaltsschluessel (RFC 8188). */
    private static final byte[] INFO_INHALT =
            "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);

    /** {@code info} fuer die Nonce (RFC 8188). */
    private static final byte[] INFO_NONCE =
            "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    /** Laenge des Salzes im Kopfblock. */
    private static final int LAENGE_SALZ = 16;

    /** Laenge des Inhaltsschluessels: AES-128. */
    private static final int LAENGE_INHALTSSCHLUESSEL = 16;

    /** Laenge der Nonce fuer AES-GCM. */
    private static final int LAENGE_NONCE = 12;

    /** Laenge des Authentifizierungsanhangs von AES-GCM in Bit. */
    private static final int LAENGE_PRUEFSUMME_BIT = 128;

    /** base64url ohne Polsterung, wie RFC 8291 es durchgaengig verwendet. */
    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();

    /**
     * Eine Instanz fuer die Anwendung. {@code SecureRandom} ist threadsicher - was hier
     * zaehlt, weil der Versand nebenlaeufig laeuft.
     */
    private final SecureRandom zufall = new SecureRandom();

    /**
     * Verschluesselt den Klartext fuer ein Abonnement.
     *
     * <p>Salz und ephemeres Schluesselpaar entstehen hier - <b>je Nachricht neu</b>, nicht je
     * Lauf: RFC 8291 verlangt es, und mit wiederverwendetem Salz waere dieselbe Nachricht an
     * denselben Empfaenger byteweise gleich.
     *
     * @param abo      Abonnement mit {@code p256dh} und {@code auth} als base64url
     * @param klartext zu verschluesselnde Bytes, hoechstens {@link #MAX_KLARTEXT_BYTES}
     * @return der vollstaendige Rumpf der Push-Anfrage: Kopfblock und Chiffrat
     * @throws GeneralSecurityException wenn die Schluessel des Abonnements unbrauchbar sind
     *                                 oder die Laufzeitumgebung ein Verfahren nicht kennt
     * @throws IllegalArgumentException wenn der Klartext die Grenze ueberschreitet oder
     *                                 {@code p256dh}/{@code auth} kein base64url sind
     */
    public byte[] verschluesseln(PushAbo abo, byte[] klartext) throws GeneralSecurityException {
        KeyPair ephemer = P256.neuesPaar();
        byte[] salz = new byte[LAENGE_SALZ];
        zufall.nextBytes(salz);

        return verschluesseln(BASE64URL.decode(abo.p256dh()),
                BASE64URL.decode(abo.auth()),
                klartext,
                salz,
                (ECPrivateKey) ephemer.getPrivate(),
                P256.rohPunkt((ECPublicKey) ephemer.getPublic()));
    }

    /**
     * Dieselbe Rechnung mit vorgegebenem Salz und vorgegebenem ephemerem Paar.
     *
     * <p><b>Paketprivat und nur dafuer da, pruefbar zu sein.</b> Ohne sie liesse sich die
     * Umsetzung gegen RFC 8291, Anhang A nicht vergleichen - und damit ueberhaupt nicht
     * pruefen, denn der Push-Dienst antwortet auch auf eine falsch verschluesselte Nachricht
     * mit {@code 201}.
     *
     * <p><b>Der ephemere oeffentliche Schluessel wird uebergeben und nicht aus dem privaten
     * abgeleitet</b>, obwohl das moeglich waere: Der Anhang nennt beide, und beide
     * einzusetzen prueft zugleich, dass {@link P256#rohPunkt} dasselbe liefert.
     *
     * @param uaPunkt       oeffentlicher Schluessel des Browsers, 65 Byte
     * @param authGeheimnis {@code auth}-Geheimnis des Abonnements, 16 Byte; Salz der ersten
     *                      HKDF-Stufe
     * @param klartext      zu verschluesselnde Bytes
     * @param salz          16 Byte; sie stehen unverschluesselt im Kopfblock
     * @param ephemerPrivat privater Teil des ephemeren Paares
     * @param ephemerPunkt  oeffentlicher Teil des ephemeren Paares, 65 Byte
     * @return Kopfblock und Chiffrat
     * @throws GeneralSecurityException bei unbrauchbaren Schluesseln
     * @throws IllegalArgumentException wenn der Klartext die Grenze ueberschreitet
     */
    byte[] verschluesseln(byte[] uaPunkt, byte[] authGeheimnis, byte[] klartext, byte[] salz,
                          ECPrivateKey ephemerPrivat, byte[] ephemerPunkt)
            throws GeneralSecurityException {

        if (klartext.length > MAX_KLARTEXT_BYTES) {
            // Backstop, nicht die eigentliche Pruefung: Der Versandadapter lehnt eine zu
            // grosse Nutzlast ab, bevor er hierher kommt - dort entsteht daraus ein
            // benennbares Ergebnis und kein Abbruch. Hier steht sie, damit ein kuenftiger
            // Aufrufer nicht an ihr vorbeikommt.
            throw new IllegalArgumentException(
                    "Nutzlast mit %d Byte ueberschreitet die Grenze von %d Byte"
                            .formatted(klartext.length, MAX_KLARTEXT_BYTES));
        }

        byte[] gemeinsam = ecdh(ephemerPrivat, P256.oeffentlich(uaPunkt));

        // Erste HKDF-Stufe (RFC 8291): Salz ist das auth-Geheimnis des Abonnements, info
        // bindet beide oeffentlichen Schluessel ein. Die Reihenfolge ist vorgeschrieben -
        // erst der Browser, dann der Server; vertauscht ergibt sie einen anderen Schluessel,
        // und der Fehler zeigt sich nur darin, dass beim Empfaenger nichts erscheint.
        byte[] eingangsmaterial = hkdf(authGeheimnis, gemeinsam,
                verbinde(INFO_SCHLUESSEL, uaPunkt, ephemerPunkt), P256.LAENGE_SKALAR);

        // Zweite HKDF-Stufe (RFC 8188): zweimal aus demselben Eingangsmaterial und Salz,
        // unterschieden allein durch info.
        byte[] inhaltsschluessel = hkdf(salz, eingangsmaterial, INFO_INHALT,
                LAENGE_INHALTSSCHLUESSEL);
        byte[] nonce = hkdf(salz, eingangsmaterial, INFO_NONCE, LAENGE_NONCE);

        byte[] datensatz = Arrays.copyOf(klartext, klartext.length + 1);
        datensatz[klartext.length] = ABSCHLUSS_LETZTER_DATENSATZ;

        Cipher chiffrierer = Cipher.getInstance("AES/GCM/NoPadding");
        chiffrierer.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(inhaltsschluessel, "AES"),
                new GCMParameterSpec(LAENGE_PRUEFSUMME_BIT, nonce));
        byte[] chiffrat = chiffrierer.doFinal(datensatz);

        return ByteBuffer.allocate(LAENGE_SALZ + 4 + 1 + ephemerPunkt.length + chiffrat.length)
                .put(salz)
                .putInt(DATENSATZGROESSE)
                .put((byte) ephemerPunkt.length)
                .put(ephemerPunkt)
                .put(chiffrat)
                .array();
    }

    /**
     * Das gemeinsame Geheimnis aus ECDH.
     *
     * @param eigener privater Schluessel dieser Seite
     * @param fremder oeffentlicher Schluessel der anderen Seite
     * @return 32 Byte
     */
    private static byte[] ecdh(ECPrivateKey eigener, ECPublicKey fremder)
            throws GeneralSecurityException {
        KeyAgreement einigung = KeyAgreement.getInstance("ECDH");
        einigung.init(eigener);
        einigung.doPhase(fremder, true);
        return einigung.generateSecret();
    }

    /**
     * HKDF-SHA256 in einem Schritt: Extract und Expand.
     *
     * <p>Ueber {@link KDF} aus dem JDK - die Schluesselableitungs-Schnittstelle ist seit
     * JDK 25 endgueltig. <b>Sie ersetzt eine handgeschriebene HMAC-Kette</b>, und das ist
     * hier mehr als Bequemlichkeit: Bei Web Push laeuft HKDF dreimal mit verschiedenen
     * Salzen und {@code info}-Werten, und jeder Vertipper darin bleibt stumm.
     *
     * @param salz   Salz der Extract-Stufe
     * @param material Eingangsmaterial
     * @param info   Kontext der Expand-Stufe, einschliesslich abschliessendem Nullbyte
     * @param laenge gewuenschte Laenge in Byte
     * @return das abgeleitete Material
     */
    private static byte[] hkdf(byte[] salz, byte[] material, byte[] info, int laenge)
            throws GeneralSecurityException {
        return KDF.getInstance("HKDF-SHA256").deriveData(
                HKDFParameterSpec.ofExtract()
                        .addIKM(material)
                        .addSalt(salz)
                        .thenExpand(info, laenge));
    }

    /** Haengt Byte-Folgen aneinander. */
    private static byte[] verbinde(byte[]... teile) {
        ByteArrayOutputStream gesammelt = new ByteArrayOutputStream();
        for (byte[] teil : teile) {
            gesammelt.writeBytes(teil);
        }
        return gesammelt.toByteArray();
    }
}
