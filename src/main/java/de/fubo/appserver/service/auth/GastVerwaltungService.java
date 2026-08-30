package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.GastPlatz;
import de.fubo.appserver.dto.admin.GastFreigebenRequest;
import de.fubo.appserver.dto.admin.GastPlatzInfo;
import de.fubo.appserver.repository.auth.GastSlotRepository;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Gastplaetze aus Sicht des Admins: Zustand ansehen und belegte Plaetze freigeben
 * (A17, Vorgabe des Haupt-Entwicklers vom 30.08.2026).
 *
 * <h2>Warum ein eigener Dienst</h2>
 * {@link GastService} ist der Login-Weg und sagt in seinem Klassenkommentar ausdruecklich, dass
 * das Abmelden nicht dort liegt; {@link SessionService#abmelden} ist die Selbstbedienung des
 * Gastes. Der Admin-Eingriff ist ein dritter Anwendungsfall - er buendelt Freigabe,
 * Sitzungswiderruf und Protokolleintrag in einer Transaktion. Dieselbe Aufteilung wie zwischen
 * {@code NamenService} (Selbstbedienung) und {@code SpielerVerwaltungService} (Verwaltung).
 *
 * <h2>Freigeben heisst abmelden</h2>
 * Zu jedem freigegebenen Platz wird die zugehoerige Sitzung widerrufen. <b>Das ist keine
 * Dreingabe, sondern die Einhaltung einer Zusicherung:</b> {@code SessionService#abmelden} haelt
 * Platz und Sitzung seit S2 zusammen, weil sonst ein Gast mit gueltigem Token angemeldet bliebe,
 * ohne einen Platz zu haben - er zaehlte als anwesend, ohne einen zu belegen. Ein Endpunkt, der
 * nur die Tabelle aufraeumt, koennte genau diesen Zustand erzeugen.
 *
 * <p><b>Die Reihenfolge ist festgelegt und nicht umkehrbar:</b> erst den Platz loslassen, dann
 * die Sitzung widerrufen. {@code gast_slot.session_id} zeigt auf die Sitzung; wer zuerst
 * widerruft, verliert den Verweis und findet den Platz nicht mehr.
 */
@Service
public class GastVerwaltungService {

    private static final Logger LOG = LoggerFactory.getLogger(GastVerwaltungService.class);

    /** Betroffene Entitaet im Audit-Log. */
    private static final String ENTITAET = "gast_slot";

    private final GastSlotRepository gastSlotRepository;
    private final SessionRepository sessionRepository;
    private final ConfigService configService;
    private final AuditService auditService;

    public GastVerwaltungService(GastSlotRepository gastSlotRepository,
                                 SessionRepository sessionRepository,
                                 ConfigService configService,
                                 AuditService auditService) {
        this.gastSlotRepository = gastSlotRepository;
        this.sessionRepository = sessionRepository;
        this.configService = configService;
        this.auditService = auditService;
    }

    /**
     * Liefert alle Gastplaetze samt Zustand.
     *
     * <p>Auch die unwirksamen Plaetze sind enthalten - Zeilen jenseits der aktuellen
     * {@code anz_guests}. Sie entstehen, sobald der Admin die Zahl senkt, und koennen belegt
     * sein; sie zu verschweigen hiesse, den Bestand unvollstaendig zu zeigen.
     *
     * @return alle Plaetze, nach Nummer sortiert; nie {@code null}, hoechstens leer
     */
    @Transactional(readOnly = true)
    public List<GastPlatzInfo> uebersicht() {
        int maxGaeste = configService.lesen().getAnzGuests();

        return gastSlotRepository.alleMitZustand().stream()
                .map(platz -> GastPlatzInfo.von(platz, maxGaeste))
                .toList();
    }

    /**
     * Gibt einzelne oder alle belegten Gastplaetze frei und meldet die zugehoerigen Gaeste ab.
     *
     * <p>Ein bereits freier Platz wird uebersprungen - der Aufruf ist wiederholbar.
     *
     * @param anfrage        entweder Platznummern oder das ausdrueckliche {@code alle}
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 400 EINGABE_UNGUELTIG}, wenn weder noch oder beides
     *                          angegeben ist oder eine Platznummer nicht existiert
     */
    @Transactional
    public void freigeben(GastFreigebenRequest anfrage, Long adminSpielerId, String clientIp) {

        pruefeAngabe(anfrage);

        List<GastPlatz> alle = gastSlotRepository.alleMitZustand();

        if (anfrage.nenntPlaetze()) {
            pruefeNummern(anfrage.slotIds(), alle);
        }

        // Nur belegte Plaetze sind Kandidaten; die uebrigen wuerde das UPDATE ohnehin
        // uebergehen, aber ohne diesen Filter stuenden sie im Protokoll.
        List<GastPlatz> betroffen = alle.stream()
                .filter(GastPlatz::belegt)
                .filter(platz -> anfrage.alleGewuenscht() || anfrage.slotIds().contains(platz.nummer()))
                .toList();

        if (betroffen.isEmpty()) {
            LOG.info("Freigabe von Gastplaetzen: kein belegter Platz betroffen.");
            return;
        }

        List<Integer> nummern = betroffen.stream().map(GastPlatz::nummer).toList();

        // Erst den Platz loslassen, dann die Sitzung widerrufen - siehe Klassenkommentar.
        int freigegeben = gastSlotRepository.freigeben(nummern);

        int widerrufen = 0;
        for (GastPlatz platz : betroffen) {
            if (platz.sessionId() != null) {
                widerrufen += sessionRepository.widerrufen(platz.sessionId());
            }
        }

        LOG.info("Gastplaetze durch den Admin freigegeben: {} (Plaetze {}), "
                        + "widerrufene Sitzungen: {}",
                freigegeben, nummern, widerrufen);

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.GAST_ABGEMELDET,
                ENTITAET, null, protokollDetails(betroffen, widerrufen));
    }

    /**
     * Genau eines der beiden Felder muss gesetzt sein.
     *
     * <p><b>Ein leerer Koerper wird abgelehnt, statt "alle" zu bedeuten.</b> Ein Sammelabbruch
     * soll kein Versehen sein koennen - er wirft im Zweifel vier angemeldete Gaeste heraus.
     */
    private static void pruefeAngabe(GastFreigebenRequest anfrage) {
        if (anfrage.alleGewuenscht() && anfrage.nenntPlaetze()) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Entweder 'slotIds' oder 'alle' angeben, nicht beides.");
        }
        if (!anfrage.alleGewuenscht() && !anfrage.nenntPlaetze()) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Es wurde nichts zum Freigeben angegeben: weder 'slotIds' noch 'alle': true.");
        }
    }

    /**
     * Jede genannte Nummer muss es geben.
     *
     * <p>Eine unbekannte Nummer wird gemeldet und nicht stillschweigend uebergangen: Sonst
     * meldete der Endpunkt Erfolg, obwohl der gemeinte Platz noch belegt ist - und der Admin
     * suchte den Fehler anderswo. Die Meldung nennt die vorhandenen Nummern, damit er nicht
     * raten muss.
     */
    private static void pruefeNummern(List<Integer> gewuenscht, List<GastPlatz> alle) {
        List<Integer> vorhanden = alle.stream().map(GastPlatz::nummer).toList();

        List<Integer> unbekannt = gewuenscht.stream()
                .filter(nummer -> !vorhanden.contains(nummer))
                .distinct()
                .sorted()
                .toList();

        if (!unbekannt.isEmpty()) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Unbekannte Gastplätze: %s. Vorhanden sind: %s."
                            .formatted(unbekannt, vorhanden));
        }
    }

    /**
     * Baut die Zusatzangaben des Protokolleintrags.
     *
     * <p>Je Platz Nummer, Gastname und ob dahinter noch eine lebende Sitzung stand. <b>Der
     * letzte Punkt ist der interessante:</b> Er unterscheidet das Aufraeumen verwaister Zeilen
     * vom Hinauswerfen eines anwesenden Gastes - zwei sehr verschiedene Vorgaenge unter
     * demselben Aufruf.
     *
     * <p><b>Die Liste geht als Liste hinein, nicht als Zeichenkette.</b> Der Serialisierer des
     * {@code AuditService} hat dafuer seit dem 30.08.2026 einen eigenen Zweig - dieser Aufruf war
     * sein Anlass. Vorher waere daraus die {@code toString}-Form geworden: ein Text, der wie JSON
     * aussieht und keines ist, in einer {@code jsonb}-Spalte. Derselbe Fallstrick, der in S3
     * schon einmal zugeschlagen hat, nur eine Klammerart weiter.
     */
    private static Map<String, Object> protokollDetails(List<GastPlatz> betroffen, int widerrufen) {
        List<Object> plaetze = new ArrayList<>();
        for (GastPlatz platz : betroffen) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put("nummer", platz.nummer());
            eintrag.put("gastName", platz.gastName());
            eintrag.put("warAktiv", !platz.verwaist());
            plaetze.add(eintrag);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("anzahl", betroffen.size());
        details.put("widerrufeneSitzungen", widerrufen);
        details.put("plaetze", plaetze);
        return details;
    }
}
