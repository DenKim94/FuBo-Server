package de.fubo.appserver.service.spieltag;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.domain.spieltag.TerminEintrag;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import de.fubo.appserver.domain.spieltag.Teilnehmeruebersicht;
import de.fubo.appserver.repository.spieltag.TeilnahmeRepository;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.config.ConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zu- und Absagen, die Skill-Stufe eines Gastes und die Teilnehmeruebersicht
 * (A7, A8, A10, A11, A17; S4 Abschnitte 5 bis 7).
 *
 * <h2>Warum die Rueckmeldung nicht protokolliert wird</h2>
 * Sie steht bereits vollstaendig in {@code spieltag.teilnahme} samt {@code gemeldet_am}; ein
 * zweiter Beleg im Audit-Log verdoppelte personenbezogene Daten und fiele nach 90 Tagen der
 * Loeschfrist zum Opfer, waehrend die Teilnahme bliebe. <b>Adminaktionen werden
 * protokolliert, Nutzerhandlungen nicht</b> - die Aenderung der Gast-Stufe ist deshalb die
 * einzige Stelle hier mit einem Eintrag.
 */
@Service
public class TeilnahmeService {

    /** Betroffene Entitaet im Audit-Log. */
    private static final String ENTITAET = "termin";

    private final TeilnahmeRepository teilnahmeRepository;
    private final TerminRepository terminRepository;
    private final ConfigService configService;
    private final AuditService auditService;
    private final Clock uhr;

    public TeilnahmeService(TeilnahmeRepository teilnahmeRepository,
                            TerminRepository terminRepository,
                            ConfigService configService,
                            AuditService auditService,
                            Clock uhr) {
        this.teilnahmeRepository = teilnahmeRepository;
        this.terminRepository = terminRepository;
        this.configService = configService;
        this.auditService = auditService;
        this.uhr = uhr;
    }

    // ------------------------------------------------------------------ Rueckmeldung

    /**
     * Nimmt die Zu- oder Absage des Aufrufers entgegen (A7, A8).
     *
     * <h2>Ein Endpunkt fuer beide Richtungen und beide Rollen</h2>
     * Der Dienst entscheidet an der <b>Sitzung</b>, welche Spalte gefuellt wird:
     * {@code spieler_id} bei einem Spieler, {@code gast_name} bei einem Gast. Der
     * Anfragekoerper ist identisch - ein Gast schickt keinen Namen mit, er <i>ist</i> schon
     * jemand. <b>Der Name kommt nie aus dem Anfragekoerper</b>, sonst koennte ein Gast unter
     * beliebigen Namen zusagen und die Teilnehmerliste mit Phantomen fuellen; dasselbe
     * Prinzip, mit dem {@code spielerId} nie aus dem Koerper kommt.
     *
     * <h2>Der Termin wird ueber die native Abfrage geladen, nicht ueber {@code findById}</h2>
     * Der Zaehler {@code teilnehmer_version} wird gleich per SQL erhoeht, und das schreibt
     * auch {@code termin.version} fort. Eine daneben verwaltete Entity truege danach eine
     * veraltete Version im Speicher, und der naechste Flush scheiterte an einem
     * Sperrkonflikt, den niemand verursacht hat.
     *
     * @param terminId betroffener Termin
     * @param zusage   {@code true} Zusage, {@code false} Absage
     * @param sitzung  aufrufende Sitzung; liefert Identitaet und Gast-Stufe
     * @throws FachlicherFehler {@code 404}, wenn es den Termin nicht gibt;
     *                          {@code 409 TERMIN_GESCHLOSSEN}, wenn er keine Rueckmeldungen
     *                          mehr annimmt; {@code 409 PROFIL_GESCHUETZT} beim Adminprofil
     */
    @Transactional
    public void rueckmeldung(Long terminId, boolean zusage, AktiveSitzung sitzung) {

        TerminEintrag termin = terminRepository
                .findeEintrag(terminId, sitzung.spielerId(), sitzung.gastName())
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        pruefeOffen(termin);

        if (sitzung.rolle() == Rolle.GAST) {
            // Die Stufe wird kopiert, nicht verwiesen: Die Sitzung endet, die Teilnahme
            // bleibt - und der Teamgenerator in S5 braucht sie zum Zeitpunkt des Spiels.
            GastStufe stufe = sitzung.gastStufe() != null ? sitzung.gastStufe() : GastStufe.MITTEL;
            teilnahmeRepository.rueckmeldungGast(terminId, sitzung.gastName(), stufe, zusage);
        } else {
            pruefeKeinAdminprofil(sitzung);
            teilnahmeRepository.rueckmeldungSpieler(terminId, sitzung.spielerId(), zusage);
        }

        // In derselben Transaktion wie die Rueckmeldung - sonst gaebe es einen Moment, in dem
        // eine Teameinteilung als aktuell gilt, obwohl der Teilnehmerkreis schon ein anderer
        // ist. Auch eine Absage zaehlt: Sie aendert den Kreis genauso.
        terminRepository.teilnehmerVersionErhoehen(terminId);
    }

    // ------------------------------------------------------------------ Gast-Stufe

    /**
     * Aendert die Skill-Stufe eines Gastes an einer bestehenden Teilnahme (A17).
     *
     * <h2>Die einzige Ausnahme von "der Admin meldet nicht fuer andere"</h2>
     * Weggabelung E: Eine Rueckmeldung ist eine Aussage ueber die eigene Verfuegbarkeit; sie
     * stellvertretend zu setzen erzeugte Zusagen, die niemand gegeben hat. A17 gibt dem Admin
     * aber ausdruecklich die Stufe - und nur sie. Weder Zusage noch Name aendern sich hier.
     *
     * <h2>Eine Stufenaenderung ist eine Teilnehmeraenderung (A15)</h2>
     * Der Fall ist dem Skillwechsel eines Spielers gleich: Der Teilnehmerkreis bleibt, seine
     * Zusammensetzung nach Staerke nicht. Der Zaehler steigt deshalb mit - <b>aber nur fuer
     * diesen einen Termin</b>: Der Endpunkt spricht eine einzelne Teilnahme an, nicht alle
     * Meldungen dieses Gastnamens. Ein gleicher Name an einem anderen Termin kann eine ganz
     * andere Person sein; Gastnamen sind je Termin eindeutig, nicht darueber hinaus.
     *
     * @param terminId       betroffener Termin
     * @param gastName       Gastname, zeichengenau wie in der Teilnahme
     * @param stufe          neue Selbsteinschaetzung
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 404}, wenn es diese Gast-Teilnahme nicht gibt
     */
    @Transactional
    public void gastStufeAendern(Long terminId, String gastName, GastStufe stufe,
                                 Long adminSpielerId, String clientIp) {

        String vorher = teilnahmeRepository.gastStufeSetzen(terminId, gastName, stufe)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Für diesen Termin liegt keine Teilnahme dieses Gastes vor."));

        terminRepository.teilnehmerVersionErhoehen(terminId);

        // Alter und neuer Wert, wie bei der Konfiguration: Es ist ein Wert mit fachlicher
        // Bedeutung, und "worauf stand die Stufe vorher" ist ohne ihn nicht zu beantworten.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gastName", gastName);
        details.put("stufeAlt", vorher);
        details.put("stufeNeu", stufe.name());

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.GAST_STUFE_GEAENDERT,
                ENTITAET, terminId, details);
    }

    // ------------------------------------------------------------------ Uebersicht

    /**
     * Liefert die Zusagen eines Termins samt der geltenden Grenzen (A10, A11).
     *
     * <p><b>Die Grenzen werden bei jedem Abruf frisch gelesen.</b> {@code ConfigService} hat
     * bewusst keinen Zwischenspeicher; senkt der Admin {@code max_teilnehmer}, wandert die
     * Warteschlangengrenze sofort mit, ohne dass eine Teilnahme angefasst wird.
     *
     * @param terminId betroffener Termin
     * @return Zusagen in Meldereihenfolge, dazu Mindest- und Hoechstzahl
     */
    @Transactional(readOnly = true)
    public Teilnehmeruebersicht uebersicht(Long terminId) {
        AppConfig konfiguration = configService.lesen();
        return new Teilnehmeruebersicht(
                konfiguration.getMinTeilnehmer(),
                konfiguration.getMaxTeilnehmer(),
                teilnahmeRepository.findeZusagen(terminId));
    }

    // ------------------------------------------------------------------ Hilfsmittel

    /**
     * Lehnt eine Rueckmeldung ab, wenn der Termin sie nicht mehr annimmt (A7, Weggabelung C).
     *
     * <p><b>Zwei unabhaengige Riegel: der Status und die Uhrzeit.</b> Ein abgesagter Termin
     * nimmt nichts mehr an, auch nicht vor seinem Beginn - und ein geplanter Termin, der
     * bereits begonnen hat, ebenfalls nicht. Der zweite Fall ist der, den die Ergaenzung vom
     * 30.08.2026 ausdruecklich benennt: <i>"Ist der Status des Termins bei GEPLANT, aber der
     * Termin hat schon begonnen, dann ist die Zu- oder Absage nicht mehr moeglich."</i>
     *
     * <p><b>Der Statusriegel allein genuegte nicht</b>, obwohl A18 den Termin 30 Minuten nach
     * Beginn automatisch abschliesst: Dazwischen liegen 30 Minuten, in denen der Status noch
     * {@code GEPLANT} ist. Der geplante Auftrag ist eine Aufraeumung, kein Torwaechter.
     *
     * <p>Die drei Faelle werden bewusst nicht unterschieden - fuer den Aufrufer ist die
     * Wirkung dieselbe, und der Status steht ohnehin in der Terminliste. Dieselbe Ueberlegung
     * wie bei {@code RESET_UNGUELTIG} in S2b, das vier Faelle buendelt.
     */
    private void pruefeOffen(TerminEintrag termin) {
        if (termin.status() != TerminStatus.GEPLANT) {
            throw new FachlicherFehler(Fehlercode.TERMIN_GESCHLOSSEN,
                    "Für diesen Termin sind keine Rückmeldungen mehr möglich.");
        }
        LocalDateTime beginn = LocalDateTime.of(termin.datum(), termin.uhrzeit());
        if (!beginn.isAfter(LocalDateTime.now(uhr))) {
            throw new FachlicherFehler(Fehlercode.TERMIN_GESCHLOSSEN,
                    "Der Termin hat bereits begonnen; eine Rückmeldung ist nicht mehr möglich.");
        }
    }

    /**
     * Haelt das Adminprofil aus den Teilnehmerlisten heraus.
     *
     * <p><b>Steht in keiner Anleitung und ist trotzdem Pflicht.</b> Das Adminprofil ist ein
     * technisches Konto: nicht in der Namensliste, ueber die Namensauswahl auch mit bekannter
     * Id nicht waehlbar, nie in einem Team. Es traegt aber eine {@code spielerId}, und der
     * Rueckmeldeendpunkt liegt ausserhalb von {@code /admin/} - ohne diese Pruefung koennte
     * der Admin also zusagen und stuende mit Skillwerten von 0 in der Teameinteilung.
     *
     * <p>Damit wiederholt sich der Ausschluss an einer weiteren Grenze, genau wie es die
     * Architekturregel verlangt. Wer selbst mitspielt, braucht ein eigenes, normales
     * Spielerprofil.
     */
    private static void pruefeKeinAdminprofil(AktiveSitzung sitzung) {
        if (sitzung.rolle() == Rolle.ADMIN) {
            throw new FachlicherFehler(Fehlercode.PROFIL_GESCHUETZT,
                    "Das Adminprofil nimmt an keinem Termin teil. Wer selbst mitspielt, "
                            + "braucht ein eigenes Spielerprofil.");
        }
    }
}
