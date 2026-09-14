package de.fubo.appserver.utils;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

/**
 * Die Kurve P-256 und die Umwandlung ihrer Schluessel zwischen Rohbytes und Objekten
 * (A25, S8).
 *
 * <h2>Warum es diesen Helfer gibt</h2>
 * Web Push arbeitet an drei Stellen mit P-256-Schluesseln, und alle drei brauchen dieselbe
 * Umwandlung: das VAPID-Paar aus der Umgebung ({@code PushConfig}), der oeffentliche
 * Schluessel eines Abonnements ({@code p256dh} aus dem Browser) und das ephemere Paar je
 * Nachricht (RFC 8291). <b>Dreimal derselbe Handgriff waere dreimal dieselbe Gelegenheit,
 * das Format falsch zu lesen</b> - und genau dieser Fehler faellt bei Web Push nicht auf:
 * Der Push-Dienst nimmt die Nachricht an, beim Empfaenger kommt nichts Lesbares heraus.
 *
 * <p><b>Die Kurvendefinition steht deshalb genau einmal</b>, und sie steht nicht als Zahlen
 * im Code: {@link #kurve()} holt die Parameter aus der Laufzeitumgebung. Eine abgeschriebene
 * Kurvendefinition waere eine zweite Wahrheit, die niemand nachrechnet.
 *
 * <h2>Warum hier und nicht in {@code service/push}</h2>
 * Zustandsloser Helfer ohne Spring-Abhaengigkeit - dieselbe Einordnung wie
 * {@link TokenGenerator}. Er kennt weder Konfiguration noch Abonnement noch Nachricht; er
 * uebersetzt Bytes in Schluessel und zurueck. Die <i>Entscheidungen</i> darueber, welche
 * Schluessel gelten und was ein fehlender Wert bedeutet, liegen bewusst ausserhalb.
 *
 * <h2>Das Format, um das es geht</h2>
 * RFC 8291 und RFC 8292 tauschen oeffentliche Schluessel als <b>unkomprimierten Punkt</b>
 * nach SEC 1 aus: 65 Byte, beginnend mit dem Kennbyte {@code 0x04}, danach die beiden
 * Koordinaten zu je 32 Byte. Private Schluessel sind der nackte Skalar, 32 Byte. Beides
 * ohne DER-Huelle - wer hier eine erwartet oder erzeugt, bekommt von den Push-Diensten ein
 * {@code 401} und keinen Hinweis auf die Ursache.
 */
public final class P256 {

    /** Benannte Kurve des Web-Push-Verfahrens (RFC 8291, RFC 8292). */
    private static final String KURVE = "secp256r1";

    /** Laenge des unkomprimierten Punktes: ein Kennbyte und zwei Koordinaten zu 32 Byte. */
    public static final int LAENGE_PUNKT = 65;

    /** Laenge einer Koordinate und damit auch des privaten Skalars auf P-256. */
    public static final int LAENGE_SKALAR = 32;

    /** Kennbyte eines unkomprimierten Punktes nach SEC 1. */
    public static final byte PUNKT_UNKOMPRIMIERT = 0x04;

    private P256() {
    }

    /**
     * Die Parameter der Kurve P-256, aus der Laufzeitumgebung.
     *
     * @return Kurvenparameter
     * @throws GeneralSecurityException wenn die Laufzeitumgebung die Kurve nicht kennt
     */
    public static ECParameterSpec kurve() throws GeneralSecurityException {
        AlgorithmParameters parameter = AlgorithmParameters.getInstance("EC");
        parameter.init(new ECGenParameterSpec(KURVE));
        return parameter.getParameterSpec(ECParameterSpec.class);
    }

    /**
     * Baut einen oeffentlichen Schluessel aus dem unkomprimierten Punkt.
     *
     * <p><b>Form und Laenge werden geprueft</b>, und zwar hier und nicht beim Aufrufer: Ein
     * zu kurzer Wert ergaebe sonst stillschweigend eine falsche Koordinate. Der Aufrufer
     * entscheidet, was mit der Ausnahme geschieht - beim VAPID-Schluessel wird sie zur
     * Startwarnung, beim Abonnement zum Fehlversuch.
     *
     * @param rohPunkt 65 Byte, beginnend mit {@code 0x04}
     * @return der Schluessel als Objekt
     * @throws GeneralSecurityException bei falscher Form, falscher Laenge oder einem Punkt,
     *                                  der nicht auf der Kurve liegt
     */
    public static ECPublicKey oeffentlich(byte[] rohPunkt) throws GeneralSecurityException {
        if (rohPunkt.length != LAENGE_PUNKT || rohPunkt[0] != PUNKT_UNKOMPRIMIERT) {
            throw new java.security.spec.InvalidKeySpecException(
                    "Kein unkomprimierter P-256-Punkt: %d Byte, erstes Byte 0x%02x"
                            .formatted(rohPunkt.length,
                                    rohPunkt.length == 0 ? 0 : rohPunkt[0] & 0xff));
        }
        ECPoint punkt = new ECPoint(koordinate(rohPunkt, 1),
                koordinate(rohPunkt, 1 + LAENGE_SKALAR));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(punkt, kurve()));
    }

    /**
     * Baut einen privaten Schluessel aus dem nackten Skalar.
     *
     * @param skalar 32 Byte, vorzeichenlos gelesen
     * @return der Schluessel als Objekt
     * @throws GeneralSecurityException bei falscher Laenge oder unbrauchbarem Wert
     */
    public static ECPrivateKey privat(byte[] skalar) throws GeneralSecurityException {
        if (skalar.length != LAENGE_SKALAR) {
            throw new java.security.spec.InvalidKeySpecException(
                    "Kein P-256-Skalar: %d Byte statt %d".formatted(skalar.length, LAENGE_SKALAR));
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, skalar), kurve()));
    }

    /**
     * Schreibt einen oeffentlichen Schluessel als unkomprimierten Punkt.
     *
     * <p><b>Die Koordinaten werden rechtsbuendig in 32 Byte gelegt</b>, und das ist der
     * Grund, warum diese Methode existiert: {@code BigInteger#toByteArray} liefert fuer
     * einen Wert mit gesetztem hoechsten Bit <i>33</i> Byte - ein fuehrendes {@code 0x00}
     * als Vorzeichen - und fuer einen kleinen Wert <i>weniger</i> als 32. Ein direktes
     * Aneinanderhaengen ergaebe in beiden Faellen einen Punkt, den die Gegenseite nicht
     * entschluesseln kann, ohne dass jemand einen Fehler sieht.
     *
     * @param schluessel oeffentlicher P-256-Schluessel
     * @return 65 Byte, beginnend mit {@code 0x04}
     */
    public static byte[] rohPunkt(ECPublicKey schluessel) {
        byte[] roh = new byte[LAENGE_PUNKT];
        roh[0] = PUNKT_UNKOMPRIMIERT;
        schreibeKoordinate(schluessel.getW().getAffineX(), roh, 1);
        schreibeKoordinate(schluessel.getW().getAffineY(), roh, 1 + LAENGE_SKALAR);
        return roh;
    }

    /**
     * Erzeugt ein frisches Schluesselpaar auf P-256.
     *
     * <p>Gebraucht fuer das <b>ephemere</b> Paar, das RFC 8291 je Nachricht verlangt. Es
     * wird nie gespeichert; sein oeffentlicher Teil reist im Kopfblock der Nutzlast mit.
     *
     * @return ein neues Paar
     * @throws GeneralSecurityException wenn die Laufzeitumgebung die Kurve nicht kennt
     */
    public static KeyPair neuesPaar() throws GeneralSecurityException {
        KeyPairGenerator erzeuger = KeyPairGenerator.getInstance("EC");
        erzeuger.initialize(new ECGenParameterSpec(KURVE));
        return erzeuger.generateKeyPair();
    }

    /** Liest eine 32-Byte-Koordinate ab der angegebenen Stelle, vorzeichenlos. */
    private static BigInteger koordinate(byte[] punkt, int ab) {
        return new BigInteger(1, Arrays.copyOfRange(punkt, ab, ab + LAENGE_SKALAR));
    }

    /** Legt eine Koordinate rechtsbuendig in 32 Byte ab; siehe {@link #rohPunkt}. */
    private static void schreibeKoordinate(BigInteger wert, byte[] ziel, int ab) {
        byte[] bytes = wert.toByteArray();
        int quelle = Math.max(0, bytes.length - LAENGE_SKALAR);
        int laenge = bytes.length - quelle;
        System.arraycopy(bytes, quelle, ziel, ab + LAENGE_SKALAR - laenge, laenge);
    }
}
