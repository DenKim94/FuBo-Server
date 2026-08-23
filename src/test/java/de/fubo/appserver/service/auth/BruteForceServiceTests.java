package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prueft die Drosselung des PIN-Endpunkts aus {@code S2_UMSETZUNG.md}, Abschnitt 6.
 *
 * <p><b>Ohne Spring-Kontext.</b> Der {@link BruteForceService} haelt seinen Zustand im
 * Arbeitsspeicher und kennt weder Datenbank noch Audit-Log - genau deshalb laesst er sich
 * hier direkt erzeugen. Das haelt den Test schnell und macht sichtbar, dass die Klasse
 * keine verdeckten Abhaengigkeiten hat.
 *
 * <p><b>Zur Uhr.</b> Sperrdauern von 1 bis 15 Minuten sind mit {@code Thread.sleep} nicht
 * pruefbar. Der Dienst bezieht die Zeit deshalb ueber eine {@link Clock}-Bean; hier tritt
 * eine verstellbare Uhr an ihre Stelle. Das ist der Grund fuer {@code ZeitConfig} - nicht
 * Selbstzweck.
 */
class BruteForceServiceTests {

    private static final String IP = "203.0.113.10";

    /** Werte wie in der Produktionskonfiguration. */
    private static final int MAX_JE_IP = 5;
    private static final int MAX_GLOBAL = 30;
    private static final int FENSTER_MINUTEN = 15;
    private static final List<Integer> SPERRDAUERN = List.of(1, 5, 15);

    private TestUhr uhr;
    private BruteForceService bruteForceService;

    @BeforeEach
    void aufbauen() {
        uhr = new TestUhr(Instant.parse("2026-08-16T12:00:00Z"));
        bruteForceService = new BruteForceService(eigenschaften(), uhr);
    }

    // --------------------------------------------------------------- Zaehler je Adresse

    /** Vier Vertipper duerfen niemanden aussperren - die Grenze liegt bei fuenf. */
    @Test
    void unterhalbDerGrenzeWirdNichtGesperrt() {
        for (int versuch = 1; versuch < MAX_JE_IP; versuch++) {
            assertThat(bruteForceService.fehlversuchZaehlen(IP)).isFalse();
        }
        assertDoesNotThrow(() -> bruteForceService.pruefeGesperrt(IP));
    }

    /** Der fuenfte Fehlversuch loest die Sperre aus; erst der naechste Aufruf wird abgewiesen. */
    @Test
    void dieGrenzeSperrtDieAdresse() {
        for (int versuch = 1; versuch < MAX_JE_IP; versuch++) {
            bruteForceService.fehlversuchZaehlen(IP);
        }
        assertThat(bruteForceService.fehlversuchZaehlen(IP)).isTrue();

        FachlicherFehler fehler = assertThrows(FachlicherFehler.class,
                () -> bruteForceService.pruefeGesperrt(IP));
        assertThat(fehler.getCode()).isEqualTo(Fehlercode.PIN_GESPERRT);
    }

    /** Eine andere Adresse bleibt von der Sperre unberuehrt. */
    @Test
    void dieSperreGiltNurFuerDieBetroffeneAdresse() {
        sperreAusloesen(IP);

        assertDoesNotThrow(() -> bruteForceService.pruefeGesperrt("203.0.113.99"));
    }

    /** Nach Ablauf der ersten Sperrdauer (1 Minute) darf es weitergehen. */
    @Test
    void dieSperreEndetNachAblaufDerSperrdauer() {
        sperreAusloesen(IP);

        uhr.vorstellen(Duration.ofSeconds(61));

        assertDoesNotThrow(() -> bruteForceService.pruefeGesperrt(IP));
    }

    /**
     * Die zweite Sperre dauert laenger als die erste (1 -> 5 Minuten). Geprueft wird nicht
     * der Meldungstext, sondern das Verhalten: Nach vier Minuten ist die Adresse noch
     * gesperrt - bei gleichbleibender Sperrdauer waere sie es nicht mehr.
     */
    @Test
    void dieSperrdauerSteigtMitJederSperre() {
        sperreAusloesen(IP);
        uhr.vorstellen(Duration.ofSeconds(61));

        sperreAusloesen(IP);
        uhr.vorstellen(Duration.ofMinutes(4));

        assertThrows(FachlicherFehler.class, () -> bruteForceService.pruefeGesperrt(IP));
    }

    /** Eine erfolgreiche Anmeldung loescht die angesammelten Fehlversuche der Adresse. */
    @Test
    void erfolgLeertDenZaehlerDerAdresse() {
        for (int versuch = 1; versuch < MAX_JE_IP; versuch++) {
            bruteForceService.fehlversuchZaehlen(IP);
        }
        bruteForceService.zuruecksetzen(IP);

        for (int versuch = 1; versuch < MAX_JE_IP; versuch++) {
            assertThat(bruteForceService.fehlversuchZaehlen(IP)).isFalse();
        }
    }

    /** Ohne neuen Fehlversuch verfaellt die Zaehlung nach dem Zeitfenster. */
    @Test
    void nachDemZeitfensterBeginntDieZaehlungNeu() {
        for (int versuch = 1; versuch < MAX_JE_IP; versuch++) {
            bruteForceService.fehlversuchZaehlen(IP);
        }
        uhr.vorstellen(Duration.ofMinutes(FENSTER_MINUTEN));

        assertThat(bruteForceService.fehlversuchZaehlen(IP)).isFalse();
    }

    // --------------------------------------------------------------- Globaler Zaehler

    /**
     * Der eigentliche Zweck des zweiten Zaehlers: Ein Angreifer, der jede Anfrage von einer
     * anderen Adresse sendet, laeuft an der Sperre je IP vorbei - es gibt aber nur eine PIN,
     * also zaehlt die Gesamtrate.
     */
    @Test
    void derGlobaleZaehlerGreiftAuchBeiWechselndenAdressen() {
        for (int nummer = 1; nummer < MAX_GLOBAL; nummer++) {
            bruteForceService.fehlversuchZaehlen("198.51.100." + nummer);
        }
        assertThat(bruteForceService.fehlversuchZaehlen("198.51.100.200")).isTrue();

        FachlicherFehler fehler = assertThrows(FachlicherFehler.class,
                () -> bruteForceService.pruefeGesperrt("198.51.100.201"));
        assertThat(fehler.getCode()).isEqualTo(Fehlercode.PIN_GESPERRT);
    }

    /**
     * Eine erfolgreiche Anmeldung leert den globalen Zaehler bewusst <b>nicht</b>. Sonst
     * koennte ein Angreifer seinen verteilten Versuch hinter der normalen Nutzung
     * verstecken.
     */
    @Test
    void erfolgLeertDenGlobalenZaehlerNicht() {
        for (int nummer = 1; nummer < MAX_GLOBAL; nummer++) {
            bruteForceService.fehlversuchZaehlen("198.51.100." + nummer);
        }
        bruteForceService.zuruecksetzen("198.51.100.1");

        assertThat(bruteForceService.fehlversuchZaehlen("198.51.100.200")).isTrue();
    }

    /** Der Notausstieg loest alle Sperren. */
    @Test
    void alleZuruecksetzenLoestJedeSperre() {
        sperreAusloesen(IP);

        bruteForceService.alleZuruecksetzen();

        assertDoesNotThrow(() -> bruteForceService.pruefeGesperrt(IP));
    }

    // --------------------------------------------------------------- Hilfsmittel

    /** Erzeugt so viele Fehlversuche, dass die Adresse gesperrt ist. */
    private void sperreAusloesen(String clientIp) {
        for (int versuch = 0; versuch < MAX_JE_IP; versuch++) {
            bruteForceService.fehlversuchZaehlen(clientIp);
        }
    }

    private static FuboProperties eigenschaften() {
        return new FuboProperties(
                new FuboProperties.Session("FUBO_SESSION", false, "Lax"),
                new FuboProperties.Cors(List.of("http://localhost:5173")),
                new FuboProperties.BruteForce(MAX_JE_IP, MAX_GLOBAL, FENSTER_MINUTEN, SPERRDAUERN),
                // Fuer die Drosselung ohne Bedeutung; seit S2b sind Mail-Zugang und
                // Reset-Grenzen ebenfalls Pflichtbestandteile von FuboProperties.
                new FuboProperties.Audit(90),
                new FuboProperties.Mail("smtp.example.invalid", 587, "test", "test",
                        "FuBo-Test <noreply@example.invalid>", 5000),
                new FuboProperties.Reset(15, 5, 3));
    }

    /** Verstellbare Uhr; ersetzt im Test die {@code Clock}-Bean aus {@code ZeitConfig}. */
    private static final class TestUhr extends Clock {

        private Instant jetzt;

        private TestUhr(Instant start) {
            this.jetzt = start;
        }

        void vorstellen(Duration dauer) {
            jetzt = jetzt.plus(dauer);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return jetzt;
        }
    }
}
