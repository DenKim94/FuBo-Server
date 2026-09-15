package de.fubo.appserver.service.push;

import de.fubo.appserver.common.config.PushConfig;
import de.fubo.appserver.common.config.VapidSchluessel;
import de.fubo.appserver.domain.push.PushAbo;
import de.fubo.appserver.domain.push.PushNutzlast;
import de.fubo.appserver.utils.P256;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft die Verschluesselung der Nutzlast (RFC 8291) und das VAPID-JWT (RFC 8292);
 * S8 Abschnitt 5, Pruefpunkte 1 bis 3 aus Abschnitt 12.3.
 *
 * <h2>Diese Klasse ist der wichtigste Testfall des Meilensteins</h2>
 * <b>Eine falsche Verschluesselung faellt sonst nirgends auf.</b> Der Push-Dienst nimmt auch eine
 * fehlerhaft verschluesselte Nachricht an und antwortet {@code 201}; der Browser bekommt sie,
 * kann sie nicht oeffnen und zeigt nichts an. Es gibt keinen Fehlerkanal, kein Protokoll und
 * keinen Statuscode, der das meldet. <b>Ohne die Testvektoren aus RFC 8291, Anhang A gibt es
 * keinerlei Rueckmeldung darueber, ob der Versand funktioniert</b> - ausser dem Blick auf ein
 * echtes Geraet.
 *
 * <h2>Fuenfte Klasse ohne Spring-Kontext</h2>
 * Neben {@code SessionAuthFilterTests}, {@code SessionCookieFactoryTests},
 * {@code BruteForceServiceTests} und {@code TeamverfahrenTests}. Sie braucht keinen: Beide
 * geprueften Klassen bekommen alles ueber den Konstruktor. Zusammen laufen die fuenf unter einer
 * Sekunde und machen die Gegenprobe belastbar - <b>sind alle fuenf gruen und der Rest rot, liegt
 * ein Kontext- oder Datenbankfehler vor und kein Anwendungsfehler.</b>
 *
 * <h2>Die Klasse liegt im Paket der geprueften Klasse</h2>
 * {@link Nutzlastverschluesselung} hat eine paketprivate Ueberladung mit vorgegebenem Salz und
 * vorgegebenem ephemerem Paar. <b>Ohne sie waere Anhang A nicht pruefbar</b>: Die oeffentliche
 * Methode zieht beides frisch, und das Ergebnis ist dann bei jedem Aufruf ein anderes - richtig
 * so, aber nicht vergleichbar.
 *
 * <h2>Die Werte des Anhangs sind veroeffentlicht und kein Geheimnis</h2>
 * Sie stehen woertlich in RFC 8291, Anhang A. Der private Schluessel des Browsers und das
 * {@code auth}-Geheimnis gehoeren zu einem Beispiel, das es nie gab; sie duerfen deshalb im
 * Quelltext stehen. <b>Ein echtes Schluesselpaar duerfte das nicht</b> - dafuer gibt es
 * Umgebungsvariablen.
 */
class PushVerschluesselungTests {

    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();
    private static final Base64.Encoder BASE64URL_OHNE_POLSTER =
            Base64.getUrlEncoder().withoutPadding();

    // ------------------------------------------------------------- RFC 8291, Anhang A

    /** Der Klartext des Anhangs; 41 Byte in UTF-8. */
    private static final String ANHANG_KLARTEXT = "When I grow up, I want to be a watermelon";

    /** Oeffentlicher Schluessel des Browsers ({@code p256dh}), 65 Byte unkomprimiert. */
    private static final String ANHANG_UA_OEFFENTLICH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";

    /** Privater Schluessel des Browsers - nur fuer den Rueckweg in diesem Test. */
    private static final String ANHANG_UA_PRIVAT = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";

    /** Ephemerer oeffentlicher Schluessel des Servers fuer diese eine Nachricht. */
    private static final String ANHANG_AS_OEFFENTLICH =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";

    /** Ephemerer privater Schluessel des Servers fuer diese eine Nachricht. */
    private static final String ANHANG_AS_PRIVAT = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";

    /** Salz des Anhangs, 16 Byte; steht unverschluesselt im Kopfblock. */
    private static final String ANHANG_SALZ = "DGv6ra1nlYgDCS1FRnbzlw";

    /** {@code auth}-Geheimnis des Abonnements, 16 Byte; Salz der ersten HKDF-Stufe. */
    private static final String ANHANG_AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    /**
     * Das vollstaendige Ergebnis des Anhangs: Kopfblock und Chiffrat, 144 Byte.
     *
     * <p>Im RFC ueber drei Zeilen umbrochen; hier zusammengesetzt, damit die Zeilenlaenge des
     * Quelltextes eingehalten bleibt. <b>Die Umbruchstellen sind keine Zeichen des Wertes</b> -
     * ein eingeschlichenes Leerzeichen liesse den Vergleich scheitern, ohne dass an der
     * Umsetzung etwas falsch waere.
     */
    private static final String ANHANG_ERGEBNIS =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
                    + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
                    + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    // ------------------------------------------------------------- VAPID

    /**
     * Fuer das JWT wird dasselbe Paar noch einmal benutzt - diesmal als VAPID-Zugang.
     *
     * <p>Im Betrieb sind das zwei verschiedene Paare: das VAPID-Paar ist langlebig und gehoert zum
     * Server, das ephemere gilt fuer genau eine Nachricht. <b>Fuer diesen Test ist die Herkunft
     * gleichgueltig</b> - gebraucht wird ein gueltiges P-256-Paar, und dieses ist bereits
     * geprueft: Der Anhang nennt beide Haelften, und dass sie zusammenpassen, belegt
     * {@link #rfc8291AnhangALiefertGenauDenDortAngegebenenDatensatz} nebenbei mit.
     */
    private static final String VAPID_SUBJECT = "mailto:push@example.invalid";

    private static final String ENDPOINT =
            "https://push.example.invalid/wpush/v2/abcdef0123456789";

    /** Fester Zeitpunkt, damit {@code exp} eine ausrechenbare Zahl ist. */
    private static final Instant JETZT = Instant.parse("2026-09-15T12:00:00Z");

    private static final long ZWOELF_STUNDEN = 12 * 60 * 60;

    private final Nutzlastverschluesselung verschluesselung = new Nutzlastverschluesselung();

    // ==================================================================== Verschluesselung

    /**
     * Pruefpunkt 1: Dieselben Eingaben ergeben <b>byteweise</b> die Ausgabe des Anhangs.
     *
     * <p>Das ist der einzige Fall dieses Meilensteins, den nichts anderes ersetzt. Er deckt in
     * einem Zug ab: ECDH ueber P-256, beide HKDF-Stufen mit ihren drei {@code info}-Zeichenketten,
     * die Reihenfolge der beiden oeffentlichen Schluessel in der ersten Stufe (erst Browser, dann
     * Server - vertauscht ergibt sie einen anderen Schluessel), das Abschlussbyte {@code 0x02}
     * des letzten Datensatzes, AES-GCM mit 128 Bit Pruefsumme und den Aufbau des Kopfblocks.
     *
     * <p><b>Jeder einzelne dieser Punkte laesst die Nachricht still verschwinden, wenn er falsch
     * ist.</b>
     */
    @Test
    void rfc8291AnhangALiefertGenauDenDortAngegebenenDatensatz() throws Exception {
        byte[] ergebnis = verschluesselung.verschluesseln(
                BASE64URL.decode(ANHANG_UA_OEFFENTLICH),
                BASE64URL.decode(ANHANG_AUTH),
                ANHANG_KLARTEXT.getBytes(StandardCharsets.UTF_8),
                BASE64URL.decode(ANHANG_SALZ),
                P256.privat(BASE64URL.decode(ANHANG_AS_PRIVAT)),
                BASE64URL.decode(ANHANG_AS_OEFFENTLICH));

        assertThat(BASE64URL_OHNE_POLSTER.encodeToString(ergebnis))
                .as("RFC 8291, Anhang A - byteweise")
                .isEqualTo(ANHANG_ERGEBNIS);
    }

    /**
     * Pruefpunkt 1, zweite Haelfte: der Aufbau des Kopfblocks nach RFC 8188.
     *
     * <p>Sechzehn Byte Salz, vier Byte Datensatzgroesse als 32-Bit-Zahl in Netzreihenfolge, ein
     * Laengenbyte, dann der ephemere Punkt. <b>Der Fall steht neben dem byteweisen Vergleich und
     * nicht statt seiner</b>: Er benennt, <i>welche</i> Stelle falsch ist, wenn jener rot wird.
     * Ein umgedrehter Ganzzahlwert oder ein vergessenes Laengenbyte sieht im Vergleich der
     * base64url-Zeichenketten nur nach "irgendwo anders" aus.
     */
    @Test
    void derKopfblockTraegtSalzDatensatzgroesseUndDenEphemerenPunkt() {
        ByteBuffer datensatz = ByteBuffer.wrap(BASE64URL.decode(ANHANG_ERGEBNIS));

        byte[] salz = new byte[16];
        datensatz.get(salz);
        int datensatzgroesse = datensatz.getInt();
        int laengeDesPunktes = datensatz.get() & 0xff;
        byte[] punkt = new byte[laengeDesPunktes];
        datensatz.get(punkt);

        assertThat(BASE64URL_OHNE_POLSTER.encodeToString(salz)).isEqualTo(ANHANG_SALZ);
        assertThat(datensatzgroesse)
                .as("rs = 4096; die Anwendung versendet nur einen Datensatz")
                .isEqualTo(4096);
        assertThat(laengeDesPunktes).isEqualTo(P256.LAENGE_PUNKT);
        assertThat(punkt[0])
                .as("unkomprimierter Punkt, erstes Byte 0x04")
                .isEqualTo(P256.PUNKT_UNKOMPRIMIERT);
        assertThat(BASE64URL_OHNE_POLSTER.encodeToString(punkt)).isEqualTo(ANHANG_AS_OEFFENTLICH);
        assertThat(datensatz.remaining())
                .as("Chiffrat: Klartext, ein Abschlussbyte und 16 Byte Pruefsumme")
                .isEqualTo(ANHANG_KLARTEXT.getBytes(StandardCharsets.UTF_8).length + 1 + 16);
    }

    /**
     * Der Rueckweg: Was die oeffentliche Methode erzeugt, laesst sich mit dem privaten Schluessel
     * des Browsers wieder oeffnen.
     *
     * <p><b>Dieser Fall allein belegte nichts</b> - er rechnet dieselbe Ableitung rueckwaerts, und
     * zwei zueinander passende Fehler faenden sich gegenseitig nicht. Er steht deshalb <i>neben</i>
     * Anhang A und ergaenzt genau das, was jener nicht zeigen kann: dass auch ein <b>zufaellig
     * gezogenes</b> Salz und ein <b>frisch erzeugtes</b> ephemeres Paar tragen. Anhang A prueft
     * feste Werte; der Betrieb benutzt nie feste Werte.
     */
    @Test
    void derBrowserKannDieNachrichtWiederEntschluesseln() throws Exception {
        byte[] rumpf = verschluesselung.verschluesseln(anhangAbo(),
                ANHANG_KLARTEXT.getBytes(StandardCharsets.UTF_8));

        assertThat(alsBrowserEntschluesseln(rumpf)).isEqualTo(ANHANG_KLARTEXT);
    }

    /**
     * Dieselbe Nachricht an dasselbe Abonnement ergibt zweimal einen <b>anderen</b> Datensatz.
     *
     * <p>RFC 8291 verlangt Salz und ephemeres Paar je Nachricht neu. Wuerden sie einmal je Lauf
     * gezogen - die naheliegende Einsparung, denn beides kostet Zufall -, waeren zwei gleiche
     * Nachrichten an denselben Empfaenger byteweise gleich. <b>Der Fehler faellt an keiner
     * Stelle auf</b>: Die Nachricht kommt an und ist lesbar; nur die Vertraulichkeit ueber mehrere
     * Nachrichten hinweg ist dahin.
     */
    @Test
    void zweiNachrichtenAnDasselbeAboSindNichtByteweiseGleich() throws Exception {
        byte[] klartext = ANHANG_KLARTEXT.getBytes(StandardCharsets.UTF_8);

        byte[] erste = verschluesselung.verschluesseln(anhangAbo(), klartext);
        byte[] zweite = verschluesselung.verschluesseln(anhangAbo(), klartext);

        assertThat(erste).isNotEqualTo(zweite);
        assertThat(alsBrowserEntschluesseln(erste)).isEqualTo(ANHANG_KLARTEXT);
        assertThat(alsBrowserEntschluesseln(zweite)).isEqualTo(ANHANG_KLARTEXT);
    }

    /**
     * Pruefpunkt 3: Eine Nutzlast ueber der Grenze wird vor dem Versand abgelehnt.
     *
     * <p>Die Grenze ist ein Backstop und nicht die eigentliche Pruefung - die steht im
     * {@code WebPushVersender} und macht daraus ein benennbares Ergebnis statt eines Abbruchs.
     * Hier wird belegt, dass ein kuenftiger Aufrufer nicht an ihr vorbeikommt.
     */
    @Test
    void eineNutzlastUeberDerGrenzeWirdAbgelehnt() {
        byte[] zuGross = new byte[Nutzlastverschluesselung.MAX_KLARTEXT_BYTES + 1];

        assertThatThrownBy(() -> verschluesselung.verschluesseln(anhangAbo(), zuGross))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Nutzlastverschluesselung.MAX_KLARTEXT_BYTES));
    }

    /**
     * Die Gegenprobe: genau auf der Grenze wird noch verschluesselt.
     *
     * <p>Ohne sie waere ein {@code >=} statt {@code >} unbemerkt geblieben - der Test oben bliebe
     * gruen, und die Grenze laege in Wahrheit ein Byte tiefer. Ein Fehler um eins faellt an keiner
     * echten Nachricht auf, weil keine so genau an die Grenze reicht.
     */
    @Test
    void eineNutzlastGenauAnDerGrenzeWirdNochVerschluesselt() throws Exception {
        byte[] geradeNoch = new byte[Nutzlastverschluesselung.MAX_KLARTEXT_BYTES];

        byte[] rumpf = verschluesselung.verschluesseln(anhangAbo(), geradeNoch);

        assertThat(rumpf).hasSize(16 + 4 + 1 + P256.LAENGE_PUNKT
                + Nutzlastverschluesselung.MAX_KLARTEXT_BYTES + 1 + 16);
    }

    // ==================================================================== VAPID-JWT

    /**
     * Pruefpunkt 2: drei Teile, base64url ohne Polsterung.
     *
     * <p>Ein Polsterzeichen {@code =} macht das Token ungueltig, und der Fehler zeigt sich erst
     * beim Push-Dienst als {@code 401} - also als Fehlversuch aller Empfaenger gleichzeitig, mit
     * einer Ursache, die nach einem Netzproblem aussieht.
     */
    @Test
    void dasJwtHatDreiTeileInBase64UrlOhnePolsterung() throws Exception {
        String[] teile = jwt(erzeuger()).split("\\.");

        assertThat(teile).hasSize(3);
        assertThat(teile).allSatisfy(teil ->
                assertThat(teil).matches("[A-Za-z0-9_-]+"));
    }

    /**
     * Pruefpunkt 2: Die Signatur ist 64 Byte lang - R und S hintereinander, ohne DER-Huelle.
     *
     * <p><b>Das ist die haeufigste Fehlerquelle beim VAPID-JWT.</b> Javas Standardverfahren
     * {@code SHA256withECDSA} liefert eine DER-Struktur von 70 bis 72 Byte; RFC 7518 verlangt fuer
     * {@code ES256} genau 64 Byte in P1363-Form. Deshalb steht in
     * {@code PushConfig.SIGNATURVERFAHREN} das Verfahren mit dem Zusatz {@code inP1363Format}.
     * Geprueft wird beides: die Laenge und dass die Signatur mit dem oeffentlichen Schluessel
     * wirklich aufgeht.
     */
    @Test
    void dieSignaturIst64ByteLangUndMitDemOeffentlichenSchluesselPruefbar() throws Exception {
        String token = jwt(erzeuger());
        String[] teile = token.split("\\.");
        byte[] signatur = BASE64URL.decode(teile[2]);

        assertThat(signatur)
                .as("ES256 nach RFC 7518: R und S zu je 32 Byte, keine DER-Huelle")
                .hasSize(2 * P256.LAENGE_SKALAR);

        Signature pruefer = Signature.getInstance(PushConfig.SIGNATURVERFAHREN);
        pruefer.initVerify(P256.oeffentlich(BASE64URL.decode(ANHANG_AS_OEFFENTLICH)));
        pruefer.update((teile[0] + "." + teile[1]).getBytes(StandardCharsets.US_ASCII));

        assertThat(pruefer.verify(signatur))
                .as("signiert wird die ASCII-Form von '<Kopf>.<Rumpf>', nicht deren Rohdaten")
                .isTrue();
    }

    /**
     * Der Kopf hat die Form {@code vapid t=<Token>, k=<oeffentlicher Schluessel>} (RFC 8292).
     *
     * <p>Das Schluesselwort {@code vapid}, das Komma und das Leerzeichen gehoeren dazu; die
     * aeltere Form mit zwei getrennten Koepfen {@code Authorization} und {@code Crypto-Key} ist
     * ueberholt und wird von mehreren Diensten nicht mehr angenommen.
     */
    @Test
    void derKopfTraegtDieVapidFormMitTokenUndSchluessel() throws Exception {
        String kopf = erzeuger().autorisierungskopf(ENDPOINT);

        assertThat(kopf).startsWith("vapid t=");
        assertThat(kopf).endsWith(", k=" + ANHANG_AS_OEFFENTLICH);
    }

    /**
     * Der Rumpf nennt Origin, Ablauf und Kontaktadresse.
     *
     * <p><b>{@code aud} ist die Origin des Endpoints, nicht der Endpoint selbst.</b> Wer den
     * vollstaendigen Pfad eintraegt, bekommt vom Dienst ein {@code 401} - und die Endpoint-Adresse
     * ist personenbezogen, sie hat in einem Token, das ueber fremde Server laeuft, ohnehin nichts
     * zu suchen.
     *
     * <p>Der Ablauf liegt zwoelf Stunden in der Zukunft. RFC 8292 erlaubt hoechstens
     * vierundzwanzig; ein Token, das laenger gilt, wird abgelehnt.
     */
    @Test
    void derRumpfNenntOriginAblaufUndKontaktadresse() throws Exception {
        String rumpf = rumpf(erzeuger(), ENDPOINT);

        assertThat(rumpf).contains("\"aud\":\"https://push.example.invalid\"");
        assertThat(rumpf).doesNotContain("wpush");
        assertThat(rumpf).contains("\"exp\":" + (JETZT.getEpochSecond() + ZWOELF_STUNDEN));
        assertThat(rumpf).contains("\"sub\":\"" + VAPID_SUBJECT + "\"");
    }

    /**
     * Ein Endpoint mit Port behaelt ihn in der Origin; der Pfad faellt weg.
     *
     * <p>Der Fall trifft keinen Dienst der Browserhersteller - die lauschen alle auf 443. Er
     * trifft den Testaufbau mit einem eigenen Dienst unter einem anderen Port, und dort ist ein
     * stillschweigend weggelassener Port ein Fehler, den niemand sucht.
     */
    @Test
    void dieOriginBehaeltDenPortUndVerliertDenPfad() throws Exception {
        String rumpf = rumpf(erzeuger(), "https://push.example.invalid:8443/abo/1");

        assertThat(rumpf).contains("\"aud\":\"https://push.example.invalid:8443\"");
    }

    /**
     * Ohne eingerichtete Schluessel entsteht kein Kopf - und zwar mit einer Ausnahme, nicht mit
     * einem leeren Wert.
     *
     * <p>Ein leerer {@code Authorization}-Kopf saehe fuer den Dienst wie ein Versuch ohne
     * Berechtigung aus und endete in {@code 401}; das wiederum ist in der Buchung
     * {@code SCHLUESSEL_ABGELEHNT} und schriebe eine Fehlermeldung ueber den VAPID-Zugang, die in
     * die Irre fuehrt. Der {@code WebPushVersender} faengt die Ausnahme und bucht einen
     * Fehlversuch dieses einen Abonnements.
     */
    @Test
    void ohneEingerichteteSchluesselGibtEsKeinenKopf() {
        VapidJwtErzeuger ohneSchluessel = new VapidJwtErzeuger(
                new VapidSchluessel(null, null, null, null, false), uhr());

        assertThatThrownBy(() -> ohneSchluessel.autorisierungskopf(ENDPOINT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nicht eingerichtet");
    }

    /**
     * Eine Endpoint-Adresse ohne Schema oder Host wird abgelehnt.
     *
     * <p>Sie kaeme aus der Datenbank und haette die Eingabepruefung des Anlegen-Endpunkts
     * passieren muessen - der Fall ist also unwahrscheinlich, aber nicht unmoeglich (eine von Hand
     * eingespielte Zeile, eine kuenftige Datenuebernahme). Ohne die Pruefung stuende in
     * {@code aud} die Zeichenkette {@code "null://null"}, und der Dienst antwortete {@code 401}.
     */
    @Test
    void einEndpointOhneSchemaWirdAbgelehnt() throws Exception {
        VapidJwtErzeuger erzeuger = erzeuger();

        assertThatThrownBy(() -> erzeuger.autorisierungskopf("push.example.invalid/abo/1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================== Nutzlasttexte

    /**
     * Die Erinnerung nennt Wochentag und Uhrzeit und zeigt auf den Termin.
     *
     * <p>Der Wochentag steht ausgeschrieben und auf Deutsch: Die Nachricht landet auf einem
     * Sperrbildschirm, und dort ist "Donnerstag" lesbarer als ein Datum. Die Sprache ist fest
     * gesetzt ({@code Locale.GERMAN}) und nicht die des Servers - in einem Container steht die
     * Standardsprache auf Englisch.
     */
    @Test
    void dieErinnerungNenntWochentagUndUhrzeitUndZeigtAufDenTermin() {
        PushNutzlast nutzlast = PushNutzlast.erinnerung(42L, LocalDate.of(2026, 9, 17),
                LocalTime.of(20, 45), "Sporthalle Nord");

        assertThat(nutzlast.titel()).isEqualTo("Kicken am Donnerstag, 20:45 Uhr");
        assertThat(nutzlast.url()).isEqualTo("/termine/42");
        assertThat(nutzlast.terminId()).isEqualTo(42L);
        assertThat(nutzlast.ort()).isEqualTo("Sporthalle Nord");
    }

    /** Die Absage nennt den Wochentag und zeigt auf denselben Termin. */
    @Test
    void dieAbsageNenntDenWochentagUndZeigtAufDenTermin() {
        PushNutzlast nutzlast = PushNutzlast.terminAbgesagt(42L, LocalDate.of(2026, 9, 17),
                LocalTime.of(20, 45), "Sporthalle Nord");

        assertThat(nutzlast.titel()).isEqualTo("Kicken am Donnerstag fällt aus");
        assertThat(nutzlast.url()).isEqualTo("/termine/42");
    }

    /**
     * Die Probenachricht traegt keine Terminangaben.
     *
     * <p>Sie gehoert zu keinem Termin - ein erfundener Wert waere im Frontend ein Klick auf eine
     * Seite, die es nicht gibt. Das Ziel ist deshalb die Startseite.
     */
    @Test
    void dieProbeTraegtKeineTerminangaben() {
        PushNutzlast nutzlast = PushNutzlast.probe();

        assertThat(nutzlast.terminId()).isNull();
        assertThat(nutzlast.datum()).isNull();
        assertThat(nutzlast.uhrzeit()).isNull();
        assertThat(nutzlast.url()).isEqualTo("/");
    }

    // ==================================================================== Hilfsmittel

    /** Ein Abonnement mit den Schluesseln des Anhangs; die Adresse ist ohne Bedeutung. */
    private static PushAbo anhangAbo() {
        return new PushAbo(1L, 1L, ENDPOINT, ANHANG_UA_OEFFENTLICH, ANHANG_AUTH);
    }

    private static Clock uhr() {
        return Clock.fixed(JETZT, ZoneOffset.UTC);
    }

    private static VapidJwtErzeuger erzeuger() throws GeneralSecurityException {
        return new VapidJwtErzeuger(new VapidSchluessel(
                P256.oeffentlich(BASE64URL.decode(ANHANG_AS_OEFFENTLICH)),
                P256.privat(BASE64URL.decode(ANHANG_AS_PRIVAT)),
                ANHANG_AS_OEFFENTLICH,
                VAPID_SUBJECT,
                true), uhr());
    }

    /** Das reine Token aus dem Kopf {@code vapid t=<Token>, k=<Schluessel>}. */
    private static String jwt(VapidJwtErzeuger erzeuger) throws Exception {
        return jwt(erzeuger, ENDPOINT);
    }

    private static String jwt(VapidJwtErzeuger erzeuger, String endpoint) throws Exception {
        String kopf = erzeuger.autorisierungskopf(endpoint);
        return kopf.substring("vapid t=".length(), kopf.indexOf(", k="));
    }

    /** Der zweite Teil des Tokens, entschluesselt in Klartext-JSON. */
    private static String rumpf(VapidJwtErzeuger erzeuger, String endpoint) throws Exception {
        return new String(BASE64URL.decode(jwt(erzeuger, endpoint).split("\\.")[1]),
                StandardCharsets.UTF_8);
    }

    /**
     * Der Weg des Browsers: dieselbe Ableitung rueckwaerts.
     *
     * <p><b>Bewusst hier und nicht in der Anwendung</b> - der Server entschluesselt nie. Die
     * Schritte stehen ausgeschrieben und nicht als Aufruf der geprueften Klasse: Ein gemeinsamer
     * Helfer pruefte sich selbst.
     */
    private static String alsBrowserEntschluesseln(byte[] rumpf) throws Exception {
        ByteBuffer datensatz = ByteBuffer.wrap(rumpf);
        byte[] salz = new byte[16];
        datensatz.get(salz);
        datensatz.getInt();
        byte[] serverPunkt = new byte[datensatz.get() & 0xff];
        datensatz.get(serverPunkt);
        byte[] chiffrat = new byte[datensatz.remaining()];
        datensatz.get(chiffrat);

        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(P256.privat(BASE64URL.decode(ANHANG_UA_PRIVAT)));
        ecdh.doPhase(P256.oeffentlich(serverPunkt), true);

        byte[] eingangsmaterial = hkdf(BASE64URL.decode(ANHANG_AUTH), ecdh.generateSecret(),
                verbinde(ascii("WebPush: info\0"), BASE64URL.decode(ANHANG_UA_OEFFENTLICH),
                        serverPunkt),
                P256.LAENGE_SKALAR);

        byte[] inhaltsschluessel = hkdf(salz, eingangsmaterial,
                ascii("Content-Encoding: aes128gcm\0"), 16);
        byte[] nonce = hkdf(salz, eingangsmaterial, ascii("Content-Encoding: nonce\0"), 12);

        Cipher chiffrierer = Cipher.getInstance("AES/GCM/NoPadding");
        chiffrierer.init(Cipher.DECRYPT_MODE, new SecretKeySpec(inhaltsschluessel, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] klartext = chiffrierer.doFinal(chiffrat);

        // Das letzte Byte ist das Abschlusszeichen des Datensatzes und gehoert nicht zum Text.
        return new String(Arrays.copyOf(klartext, klartext.length - 1), StandardCharsets.UTF_8);
    }

    private static byte[] hkdf(byte[] salz, byte[] material, byte[] info, int laenge)
            throws Exception {
        return KDF.getInstance("HKDF-SHA256").deriveData(HKDFParameterSpec.ofExtract()
                .addSalt(salz).addIKM(material).thenExpand(info, laenge));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] verbinde(byte[]... teile) {
        int laenge = 0;
        for (byte[] teil : teile) {
            laenge += teil.length;
        }
        byte[] alles = new byte[laenge];
        int stelle = 0;
        for (byte[] teil : teile) {
            System.arraycopy(teil, 0, alles, stelle, teil.length);
            stelle += teil.length;
        }
        return alles;
    }
}
