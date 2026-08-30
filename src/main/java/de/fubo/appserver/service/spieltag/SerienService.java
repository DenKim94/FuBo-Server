package de.fubo.appserver.service.spieltag;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.spieltag.Terminserie;
import de.fubo.appserver.dto.spieltag.SerieAnlegenRequest;
import de.fubo.appserver.dto.spieltag.SerieAngelegt;
import de.fubo.appserver.dto.spieltag.TerminAngelegt;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.repository.spieltag.TerminserieRepository;
import de.fubo.appserver.service.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Legt befristete Terminserien an und erzeugt ihre Termine (A18, S4 Abschnitt 4).
 *
 * <h2>Die Termine entstehen sofort, nicht virtuell</h2>
 * Weggabelung B der Anleitung: Aus Serie, Wochentag, Uhrzeit und Zeitraum werden alle
 * Termine als echte Zeilen erzeugt. {@code teilnahme.termin_id} ist ein Fremdschluessel -
 * ein virtueller Termin haette keine Id, an der eine Zusage haengen koennte, und muesste
 * spaetestens bei der ersten Rueckmeldung doch angelegt werden, in einem Pfad, der
 * eigentlich nur lesen wollte. Dazu kommt: {@code uq_termin_zeit} prueft Zeilen, keine
 * Regeln; ohne Materialisierung fiele die Kollisionspruefung aus Weggabelung A weg.
 *
 * <p><b>Preis, den man kennen muss:</b> Eine Aenderung an der Serie wirkt nicht rueckwirkend
 * auf bereits erzeugte Termine. Deshalb gibt es keinen Endpunkt, der eine Serie aendert -
 * sie ist eine Erzeugungsregel, kein lebender Datensatz.
 *
 * <h2>Ein eigener Dienst und nicht Teil des TerminService</h2>
 * Er schreibt in zwei Tabellen, kennt die Wochentagsrechnung und die Obergrenze - alles
 * Dinge, die den Einzeltermin nichts angehen. Beide teilen sich das
 * {@link TerminRepository}, und dessen {@code ON CONFLICT}-Klausel ist genau die Stelle, an
 * der aus "Kollision" ein "uebersprungen" wird.
 */
@Service
public class SerienService {

    /**
     * Hoechstzahl der Termine je Serie - ein Jahr woechentlich.
     *
     * <p>Festlegung des Haupt-Entwicklers vom 30.08.2026 (offener Punkt 7 der Anleitung).
     * Der Riegel gilt einem Tippfehler im Enddatum: "2036" statt "2026" legte ueber
     * fuenfhundert Termine an, die einzeln wieder abzusagen waeren - loeschen laesst sich
     * keiner.
     *
     * <p><b>Eine Konstante und kein Konfigurationsfeld.</b> Die Konfiguration wird als
     * Voll-Update geschrieben; ein zwoelftes Feld dort waere fuer den Client-Track eine
     * brechende Vertragsaenderung - fuer eine Grenze, die niemand verstellen will.
     */
    private static final int MAX_TERMINE_JE_SERIE = 52;

    /** Betroffene Entitaet im Audit-Log. */
    private static final String ENTITAET = "terminserie";

    private final TerminserieRepository terminserieRepository;
    private final TerminRepository terminRepository;
    private final AuditService auditService;
    private final Clock uhr;

    public SerienService(TerminserieRepository terminserieRepository,
                         TerminRepository terminRepository,
                         AuditService auditService,
                         Clock uhr) {
        this.terminserieRepository = terminserieRepository;
        this.terminRepository = terminRepository;
        this.auditService = auditService;
        this.uhr = uhr;
    }

    /**
     * Legt eine Serie an und erzeugt ihre Termine.
     *
     * <h2>Reihenfolge</h2>
     * Erst wird gerechnet, dann geprueft, dann geschrieben. Waere es umgekehrt, bliebe bei
     * einer zu langen Serie eine Serienzeile ohne Termine zurueck - und die laesst sich
     * ueber keinen Endpunkt wieder entfernen.
     *
     * <h2>Kollisionen sind kein Fehler</h2>
     * Weggabelung A: Ein belegter Zeitpunkt wird uebersprungen und in der Antwort genannt,
     * statt die ganze Serie abzulehnen. {@code uq_termin_zeit} ist eine <i>globale</i>
     * Bedingung - bei zwoelf Wochen genuegt ein einziger bestehender Einzeltermin. Die
     * Alternative "alles oder nichts" waere nur dann besser, wenn eine unvollstaendige Serie
     * schaedlich waere; sie ist es nicht, jeder Termin steht fuer sich.
     *
     * <p><b>Auch der Grenzfall "alles kollidiert" legt die Serie an.</b> Die Antwort nennt
     * dann eine leere Liste angelegter und eine volle Liste uebersprungener Termine - eine
     * eindeutige Auskunft. Sie mit {@code 409} abzulehnen widerspraeche Weggabelung A und
     * unterschiede zwei Faelle, die fachlich derselbe sind.
     *
     * @param anfrage        Titel, Wochentag, Uhrzeit, Zeitraum und Ort
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer Spalte und Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @return Serie samt der erzeugten und der uebersprungenen Termine
     * @throws FachlicherFehler {@code 400 EINGABE_UNGUELTIG}, wenn das Enddatum nicht nach
     *                          dem Startdatum liegt, kein Termin in den Zeitraum faellt, der
     *                          erste Termin bereits vorbei ist oder die Serie mehr als
     *                          {@value #MAX_TERMINE_JE_SERIE} Termine ergaebe
     */
    @Transactional
    public SerieAngelegt anlegen(SerieAnlegenRequest anfrage, Long adminSpielerId, String clientIp) {

        // Feldgrenzen prueft Bean Validation am DTO. Diese Bedingung ist feldeuebergreifend
        // und deshalb hier. ck_terminserie_zeitraum bleibt die letzte Instanz, braechte aber
        // einen 500 mit einem Constraint-Namen im Log statt einer benennenden Meldung -
        // dasselbe Muster wie bei den Teilnehmerzahlen in ConfigService.
        if (!anfrage.enddatum().isAfter(anfrage.startdatum())) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Das Enddatum muss nach dem Startdatum liegen. Für einen einzelnen Termin "
                            + "gibt es /admin/termin/anlegen.");
        }

        List<LocalDate> zeitpunkte = zeitpunkteBerechnen(
                anfrage.startdatum(), anfrage.enddatum(), anfrage.wochentag());

        if (zeitpunkte.isEmpty()) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "In diesem Zeitraum liegt kein einziger passender Wochentag.");
        }
        if (zeitpunkte.size() > MAX_TERMINE_JE_SERIE) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    ("Der Zeitraum ergäbe %d Termine; höchstens %d sind zulässig. "
                            + "Bitte das Enddatum prüfen.")
                            .formatted(zeitpunkte.size(), MAX_TERMINE_JE_SERIE));
        }

        // Nur der erste Zeitpunkt wird geprueft - die Liste ist aufsteigend sortiert, alle
        // weiteren liegen also spaeter. Die Regel ist dieselbe wie beim Einzeltermin: Ein
        // Termin, zu dem niemand mehr zusagen kann, ist eine unveraenderliche Leiche im
        // Bestand. Die Anleitung erwaehnt sie fuer die Serie nicht; sie hier auszulassen
        // hiesse, denselben Zustand ueber einen zweiten Weg doch zu erlauben.
        LocalDateTime ersterZeitpunkt = LocalDateTime.of(zeitpunkte.getFirst(), anfrage.uhrzeit());
        if (!ersterZeitpunkt.isAfter(LocalDateTime.now(uhr))) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Der erste Termin der Serie (%s %s) liegt in der Vergangenheit."
                            .formatted(zeitpunkte.getFirst(), anfrage.uhrzeit()));
        }

        Terminserie serie = new Terminserie(
                anfrage.titelBereinigt(),
                anfrage.wochentag().shortValue(),
                anfrage.uhrzeit(),
                anfrage.startdatum(),
                anfrage.enddatum(),
                anfrage.ortBereinigt(),
                adminSpielerId,
                OffsetDateTime.now(uhr));

        // saveAndFlush und nicht nur save: Die Termine werden ueber natives SQL eingefuegt
        // und verweisen mit serie_id auf diese Zeile. Ohne das Flush stuende sie noch im
        // Persistence-Context und die Fremdschluesselbedingung schluege fehl.
        Terminserie gespeichert = terminserieRepository.saveAndFlush(serie);

        List<TerminAngelegt> angelegt = new ArrayList<>();
        List<LocalDate> uebersprungen = new ArrayList<>();

        for (LocalDate datum : zeitpunkte) {
            Optional<Long> terminId = terminRepository.einfuegenWennFrei(
                    gespeichert.getId(), datum, anfrage.uhrzeit(), anfrage.ortBereinigt());

            if (terminId.isPresent()) {
                angelegt.add(new TerminAngelegt(terminId.get(), datum, anfrage.uhrzeit()));
            } else {
                uebersprungen.add(datum);
            }
        }

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.SERIE_ANGELEGT,
                ENTITAET, gespeichert.getId(), protokolldetails(anfrage, angelegt, uebersprungen));

        return new SerieAngelegt(gespeichert.getId(), gespeichert.getTitel(), angelegt, uebersprungen);
    }

    /**
     * Berechnet die Zeitpunkte der Serie.
     *
     * <p><b>{@code nextOrSame} und nicht {@code next}:</b> Faellt das Startdatum bereits auf
     * den gewuenschten Wochentag, gehoert dieser Tag zur Serie. Mit {@code next} fehlte die
     * erste Woche, und der Fehler fiele erst auf, wenn jemand die Termine zaehlt.
     *
     * <p><b>Die ISO-Zaehlung passt ohne Umrechnung:</b> {@code ck_terminserie_wochentag}
     * laesst 1 bis 7 zu, {@code java.time.DayOfWeek} zaehlt genauso ab Montag. Das ist die
     * Ausnahme und nicht die Regel - {@code java.util.Calendar} zaehlt ab Sonntag.
     *
     * <p>Das Enddatum gehoert dazu ({@code !isAfter}), sonst fehlte bei einem Enddatum, das
     * genau auf den Wochentag faellt, der letzte Termin.
     */
    private static List<LocalDate> zeitpunkteBerechnen(LocalDate startdatum, LocalDate enddatum,
                                                       Integer wochentag) {
        List<LocalDate> zeitpunkte = new ArrayList<>();
        LocalDate lauf = startdatum.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(wochentag)));

        while (!lauf.isAfter(enddatum)) {
            zeitpunkte.add(lauf);
            lauf = lauf.plusWeeks(1);
        }
        return zeitpunkte;
    }

    /**
     * Baut die Details des Protokolleintrags.
     *
     * <p><b>Anzahlen statt Listen.</b> Bei 52 Terminen stuenden sonst 52 Ids in einer Zeile,
     * die nach 90 Tagen der Loeschfrist zum Opfer faellt - waehrend die Termine selbst in
     * {@code spieltag.termin} stehen bleiben und ueber ihre {@code serie_id} auffindbar sind.
     * Der Eintrag beantwortet "wer hat wann welche Serie angelegt", nicht "welche Zeilen
     * entstanden dabei".
     */
    private static Map<String, Object> protokolldetails(SerieAnlegenRequest anfrage,
                                                        List<TerminAngelegt> angelegt,
                                                        List<LocalDate> uebersprungen) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("titel", anfrage.titelBereinigt());
        details.put("wochentag", anfrage.wochentag());
        details.put("uhrzeit", anfrage.uhrzeit().toString());
        details.put("startdatum", anfrage.startdatum().toString());
        details.put("enddatum", anfrage.enddatum().toString());
        details.put("anzahlAngelegt", angelegt.size());
        details.put("anzahlUebersprungen", uebersprungen.size());
        return details;
    }
}
