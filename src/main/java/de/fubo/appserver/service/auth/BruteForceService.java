package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drosselt den PIN-Endpunkt (A3).
 *
 * <p><b>Zwei Zaehler, nicht einer.</b> Der Zaehler je IP faengt den einfachen Fall ab.
 * Ein verteilter Angriff ueber viele Adressen liefe daran aber vorbei, und es gibt nur
 * <i>eine</i> PIN - die eigentliche Groesse ist deshalb die Gesamtrate. Der globale
 * Zaehler deckelt sie.
 *
 * <p><b>Warum ein {@link ConcurrentHashMap} und kein gemeinsamer Speicher?</b> Die
 * Anwendung laeuft als einzelne Instanz auf einem Raspberry Pi. Erst bei mehreren
 * Instanzen brauchte es einen geteilten Zustand (Redis o. ae.), weil ein Angreifer sonst
 * zwischen den Instanzen wechseln koennte.
 *
 * <p><b>Warum keine dauerhafte Sperre?</b> Wer die PIN nicht kennt, koennte sonst alle
 * echten Nutzer aussperren, indem er absichtlich falsche PINs sendet. Die Sperrdauer
 * steigt stattdessen schrittweise (Vorgabe 1, 5, 15 Minuten).
 *
 * <p>Der Dienst kennt bewusst weder das Audit-Log noch die Datenbank: Er ist damit ohne
 * Spring-Kontext testbar, und der Aufrufer entscheidet, was protokolliert wird.
 */
@Service
public class BruteForceService {

    private static final Logger LOG = LoggerFactory.getLogger(BruteForceService.class);

    private final FuboProperties.BruteForce konfiguration;
    private final Clock uhr;

    /** Zaehler je Client-IP. Wird vom Aufraeumjob unten begrenzt. */
    private final Map<String, Zaehler> zaehlerJeIp = new ConcurrentHashMap<>();

    /** Gesamtzaehler ueber alle Adressen hinweg. */
    private final Zaehler globalerZaehler = new Zaehler();

    public BruteForceService(FuboProperties eigenschaften, Clock uhr) {
        this.konfiguration = eigenschaften.bruteForce();
        this.uhr = uhr;
    }

    /**
     * Wirft ab, wenn die Adresse oder der Endpunkt insgesamt gerade gesperrt ist.
     * Wird <b>vor</b> der PIN-Pruefung aufgerufen - eine gesperrte Anfrage darf gar nicht
     * erst gegen den Hash rechnen.
     *
     * @throws FachlicherFehler mit {@link Fehlercode#PIN_GESPERRT} (HTTP 429)
     */
    public void pruefeGesperrt(String clientIp) {
        Instant jetzt = uhr.instant();

        long globalRest = globalerZaehler.verbleibendeSperreSekunden(jetzt);
        if (globalRest > 0) {
            throw gesperrt(globalRest);
        }

        long ipRest = zaehlerFuer(clientIp).verbleibendeSperreSekunden(jetzt);
        if (ipRest > 0) {
            throw gesperrt(ipRest);
        }
    }

    /**
     * Zaehlt einen Fehlversuch auf beiden Zaehlern.
     *
     * @return {@code true}, wenn genau dieser Versuch eine neue Sperre ausgeloest hat -
     *         der Aufrufer kann das gezielt im Audit-Log vermerken
     */
    public boolean fehlversuchZaehlen(String clientIp) {
        Instant jetzt = uhr.instant();

        boolean ipGesperrt = zaehlerFuer(clientIp).fehlversuchZaehlen(
                jetzt, konfiguration.maxVersucheIp(), konfiguration.fensterMinuten(),
                konfiguration.sperrdauernMinuten());

        boolean globalGesperrt = globalerZaehler.fehlversuchZaehlen(
                jetzt, konfiguration.maxVersucheGlobal(), konfiguration.fensterMinuten(),
                konfiguration.sperrdauernMinuten());

        if (globalGesperrt) {
            LOG.warn("PIN-Endpunkt global gesperrt - {} Fehlversuche innerhalb von {} Minuten.",
                    konfiguration.maxVersucheGlobal(), konfiguration.fensterMinuten());
        } else if (ipGesperrt) {
            LOG.warn("PIN-Endpunkt fuer eine Adresse gesperrt ({} Fehlversuche).",
                    konfiguration.maxVersucheIp());
        }
        return ipGesperrt || globalGesperrt;
    }

    /**
     * Setzt den Zaehler einer Adresse nach erfolgreicher Anmeldung zurueck.
     *
     * <p>Der <b>globale</b> Zaehler bleibt bewusst stehen: Er misst die Gesamtrate an
     * Fehlversuchen. Wuerde ihn jede erfolgreiche Anmeldung leeren, koennte ein Angreifer
     * seinen verteilten Versuch hinter der normalen Nutzung verstecken. Der globale Zaehler
     * verfaellt ausschliesslich ueber das Zeitfenster.
     */
    public void zuruecksetzen(String clientIp) {
        zaehlerJeIp.remove(clientIp);
    }

    /**
     * Leert alle Zaehler und Sperren.
     *
     * <p>Gedacht fuer zwei Faelle: als Notausstieg, wenn eine Sperre echte Nutzer trifft,
     * und um in Integrationstests von einem definierten Zustand aus zu starten - der Dienst
     * ist ein Singleton und behaelt seinen Zustand sonst ueber Testgrenzen hinweg.
     */
    public void alleZuruecksetzen() {
        zaehlerJeIp.clear();
        globalerZaehler.zuruecksetzen();
    }

    /**
     * Entfernt Zaehler, die weder gesperrt noch im Zeitfenster sind.
     *
     * <p>Ohne diesen Job waechst die Karte mit jeder Adresse, die je eine falsche PIN
     * gesendet hat - bei einem verteilten Angriff waere das ein Speicherleck.
     */
    @Scheduled(cron = "0 15 * * * *")
    public void veralteteZaehlerEntfernen() {
        Instant jetzt = uhr.instant();
        int vorher = zaehlerJeIp.size();
        zaehlerJeIp.values().removeIf(z -> z.istVerfallen(jetzt, konfiguration.fensterMinuten()));

        int entfernt = vorher - zaehlerJeIp.size();
        if (entfernt > 0) {
            LOG.debug("Verfallene Brute-Force-Zaehler entfernt: {}", entfernt);
        }
    }

    /** Legt den Zaehler bei Bedarf an; {@code computeIfAbsent} ist dabei atomar. */
    private Zaehler zaehlerFuer(String clientIp) {
        return zaehlerJeIp.computeIfAbsent(clientIp, unbenutzt -> new Zaehler());
    }

    /**
     * Einheitliche Ablehnung mit Restwartezeit.
     *
     * <p>Die Wartezeit steht sowohl im Meldungstext als auch als eigener Wert im Fehler.
     * Der Text ist Anzeigetext und darf sich aendern; der Wert wird vom
     * {@code GlobalExceptionHandler} in den {@code Retry-After}-Header und in das Feld
     * {@code wartesekunden} des Problem-Details uebersetzt und ist damit der Teil des
     * Vertrags, auf den sich das Frontend stuetzen darf.
     */
    private static FachlicherFehler gesperrt(long restSekunden) {
        return new FachlicherFehler(Fehlercode.PIN_GESPERRT,
                "Zu viele Fehlversuche. Bitte in %d Sekunden erneut versuchen.".formatted(restSekunden),
                restSekunden);
    }

    /**
     * Zustand eines Zaehlers. Alle Methoden sind {@code synchronized}, weil sie mehrere
     * Felder gemeinsam fortschreiben - einzelne atomare Typen genuegten dafuer nicht.
     */
    private static final class Zaehler {

        private int fehlversuche;

        /** Wie oft dieser Zaehler bereits gesperrt hat; waehlt die Sperrdauer aus. */
        private int sperrstufe;

        private Instant letzterFehlversuch;
        private Instant gesperrtBis;

        /** @return verbleibende Sperrzeit in Sekunden, 0 wenn nicht gesperrt */
        synchronized long verbleibendeSperreSekunden(Instant jetzt) {
            if (gesperrtBis == null || !jetzt.isBefore(gesperrtBis)) {
                return 0;
            }
            // Aufrunden: 0 Sekunden Restzeit waere fuer den Nutzer irrefuehrend.
            return Math.max(1, Duration.between(jetzt, gesperrtBis).toSeconds() + 1);
        }

        /** @return {@code true}, wenn dieser Fehlversuch eine neue Sperre ausgeloest hat */
        synchronized boolean fehlversuchZaehlen(Instant jetzt, int maxVersuche, int fensterMinuten,
                                                List<Integer> sperrdauernMinuten) {
            if (fensterAbgelaufen(jetzt, fensterMinuten)) {
                fehlversuche = 0;
                if (eskalationVerfallen(jetzt, fensterMinuten)) {
                    sperrstufe = 0;
                }
            }
            letzterFehlversuch = jetzt;
            fehlversuche++;

            if (fehlversuche < maxVersuche) {
                return false;
            }

            int dauerMinuten = sperrdauernMinuten.get(
                    Math.min(sperrstufe, sperrdauernMinuten.size() - 1));
            gesperrtBis = jetzt.plus(Duration.ofMinutes(dauerMinuten));
            sperrstufe++;
            fehlversuche = 0;   // Der naechste Zyklus beginnt nach Ablauf der Sperre neu.
            return true;
        }

        /** Der Zaehler kann verworfen werden: keine aktive Sperre, kein frischer Versuch. */
        synchronized boolean istVerfallen(Instant jetzt, int fensterMinuten) {
            boolean gesperrt = gesperrtBis != null && jetzt.isBefore(gesperrtBis);
            return !gesperrt && eskalationVerfallen(jetzt, fensterMinuten);
        }

        synchronized void zuruecksetzen() {
            fehlversuche = 0;
            sperrstufe = 0;
            letzterFehlversuch = null;
            gesperrtBis = null;
        }

        /** Seit dem letzten Fehlversuch ist mehr als ein Zeitfenster vergangen. */
        private boolean fensterAbgelaufen(Instant jetzt, int fensterMinuten) {
            return letzterFehlversuch == null
                    || Duration.between(letzterFehlversuch, jetzt).toMinutes() >= fensterMinuten;
        }

        /**
         * Nach doppelter Fensterlaenge ohne Fehlversuch faellt die Eskalationsstufe zurueck.
         * Ohne diesen Rueckfall traefe einen Nutzer, der vor Wochen einmal gesperrt war,
         * beim naechsten Vertipper sofort die laengste Sperrdauer.
         */
        private boolean eskalationVerfallen(Instant jetzt, int fensterMinuten) {
            return letzterFehlversuch == null
                    || Duration.between(letzterFehlversuch, jetzt).toMinutes() >= 2L * fensterMinuten;
        }
    }
}
