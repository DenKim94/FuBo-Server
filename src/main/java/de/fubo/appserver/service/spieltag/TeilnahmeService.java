package de.fubo.appserver.service.spieltag;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.domain.spieltag.GastAbsageErgebnis;
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
 * zweiter Beleg im Audit-Log verdoppelte personenbezogene Daten und fiele nach 30 Tagen der
 * Loeschfrist zum Opfer, waehrend die Teilnahme bliebe. <b>Adminaktionen werden
 * protokolliert, Nutzerhandlungen nicht</b> - die Aenderung der Gast-Stufe und die Absage fuer
 * einen Gast sind deshalb die einzigen Stellen hier mit einem Eintrag.
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
     * Die Zaehler {@code teilnehmer_version} und {@code kontingent_version} werden gleich per
     * SQL erhoeht, und das schreibt auch {@code termin.version} fort. Eine daneben verwaltete Entity truege danach eine
     * veraltete Version im Speicher, und der naechste Flush scheiterte an einem
     * Sperrkonflikt, den niemand verursacht hat.
     *
     * <h2>Zwei Zaehler, zwei Ausloeser (Aenderung vom 22.10.2026)</h2>
     * {@code teilnehmer_version} steigt bei <b>jeder</b> Rueckmeldung, die etwas aendert: Sie
     * kennzeichnet eine gespeicherte Einteilung als veraltet. {@code kontingent_version}
     * steigt nur bei einer <b>neuen Zusage</b> - Zusage ohne vorherige Zusage, auch nach einer
     * Absage; sie ist Teil des Kontingentschluessels. Eine Absage verschafft also niemandem
     * einen weiteren Generierungslauf.
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

        boolean neueZusage;
        if (sitzung.rolle() == Rolle.GAST) {
            // Die Stufe wird kopiert, nicht verwiesen: Die Sitzung endet, die Teilnahme
            // bleibt - und der Teamgenerator in S5 braucht sie zum Zeitpunkt des Spiels.
            GastStufe stufe = sitzung.gastStufe() != null ? sitzung.gastStufe() : GastStufe.MITTEL;
            neueZusage = teilnahmeRepository.rueckmeldungGast(
                    terminId, sitzung.gastName(), stufe, zusage);
        } else {
            pruefeKeinAdminprofil(sitzung);
            neueZusage = teilnahmeRepository.rueckmeldungSpieler(
                    terminId, sitzung.spielerId(), zusage);
        }

        // In derselben Transaktion wie die Rueckmeldung - sonst gaebe es einen Moment, in dem
        // eine Teameinteilung als aktuell gilt, obwohl der Teilnehmerkreis schon ein anderer
        // ist. Auch eine Absage zaehlt dafuer: Sie aendert den Kreis genauso.
        //
        // Das Generierungskontingent dagegen oeffnet nur eine NEUE Zusage (Aenderung vom
        // 22.10.2026): Wer absagt, verkleinert den Kreis und liefert keinen Grund fuer einen
        // weiteren Lauf; eine wiederholte Zusage aendert gar nichts. Ob die Zusage neu ist,
        // meldet die Rueckmeldung selbst - der Zustand davor ist nur in derselben Anweisung
        // verlaesslich zu lesen (siehe TeilnahmeRepository).
        if (neueZusage) {
            terminRepository.teilnehmerUndKontingentVersionErhoehen(terminId);
        } else {
            terminRepository.teilnehmerVersionErhoehen(terminId);
        }
    }

    /**
     * Nimmt die Zusagen eines gesperrten Profils zurueck (S5 Abschnitt 10.1).
     *
     * <p><b>Weitergereicht und nicht orchestriert:</b> Die Reihenfolge - erst
     * {@code teilnehmer_version} erhoehen, dann die Zusagen zuruecknehmen - steht im
     * {@code TerminService}, der beide Schritte kennt. Zwei Orte fuer diese Reihenfolge waeren
     * einer zu viel; sie ist die ganze Schwierigkeit des Nachtrags.
     *
     * <p>Kein Protokolleintrag: Der Vorgang ist eine <i>Folge</i> des Sperrens und keine
     * eigene Handlung. Die Zahl geht in den bestehenden {@code PROFIL_BLOCKIERT}-Eintrag.
     *
     * @param spielerId gesperrtes Profil
     * @param jetzt     Vergleichszeitpunkt des aufrufenden Vorgangs
     * @return Anzahl zurueckgenommener Zusagen
     */
    @Transactional
    public int zusagenZuruecknehmen(Long spielerId, LocalDateTime jetzt) {
        return teilnahmeRepository.zusagenZuruecknehmen(spielerId, jetzt);
    }

    // ------------------------------------------------------------------ Gast-Stufe

    /**
     * Aendert die Skill-Stufe eines Gastes an einer bestehenden Teilnahme (A17).
     *
     * <h2>Eine Ausnahme von "der Admin meldet nicht fuer andere"</h2>
     * Weggabelung E: Eine Zusage ist eine Aussage ueber die eigene Verfuegbarkeit; sie
     * stellvertretend zu setzen erzeugte Zusagen, die niemand gegeben hat. A17 gibt dem Admin
     * aber ausdruecklich die Stufe. Weder Zusage noch Name aendern sich hier. Die zweite
     * Ausnahme ist {@link #gastAbsagen} (24.10.2026): Sie nimmt eine Zusage zurueck, statt eine
     * zu erzeugen.
     *
     * <h2>Eine Stufenaenderung ist eine Teilnehmeraenderung (A15)</h2>
     * Der Fall ist dem Skillwechsel eines Spielers gleich: Der Teilnehmerkreis bleibt, seine
     * Zusammensetzung nach Staerke nicht. Beide Zaehler steigen deshalb mit - die
     * Einteilung veraltet, und das Generierungskontingent oeffnet sich neu (die Aenderung
     * ist eine ausdruecklich genannte Ausnahme der Absage-Regel) - <b>aber nur fuer
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

        terminRepository.teilnehmerUndKontingentVersionErhoehen(terminId);

        // Alter und neuer Wert, wie bei der Konfiguration: Es ist ein Wert mit fachlicher
        // Bedeutung, und "worauf stand die Stufe vorher" ist ohne ihn nicht zu beantworten.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gastName", gastName);
        details.put("stufeAlt", vorher);
        details.put("stufeNeu", stufe.name());

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.GAST_STUFE_GEAENDERT,
                ENTITAET, terminId, details);
    }

    // ------------------------------------------------------------------ Absage fuer einen Gast

    /**
     * Nimmt die Zusage eines Gastes zurueck, auf Anweisung des Admins (A7, Ergaenzung vom
     * 24.10.2026).
     *
     * <h2>Anlass</h2>
     * Ein Gast hat zugesagt und muss spontan absagen. Ohne eigenes Konto kann er das oft nicht
     * mehr: Seine Sitzung ist abgelaufen oder abgemeldet, und eine neue Anmeldung braucht die
     * zentrale PIN und einen freien Gastplatz. Der Admin nimmt die Zusage deshalb zurueck;
     * das wirkt wie die Absage des Gastes.
     *
     * <h2>Verhaeltnis zu Weggabelung E ("der Admin meldet nicht fuer andere")</h2>
     * Die Regel verhindert Zusagen, die niemand gegeben hat. Eine <b>Absage</b> erzeugt keine
     * Zusage, sie verkleinert den Kreis. Die Ausnahme gilt <b>nur fuer Gaeste und nur in
     * Richtung Absage</b>: Die Suche laeuft ueber {@code gast_name}, der bei einem Spieler
     * {@code NULL} ist - ein Spieler sagt weiter selbst ab, und eine stellvertretende Zusage
     * bleibt ausgeschlossen, weil dieser Weg keine Zeile anlegt und kein {@code zusage = true}
     * schreibt.
     *
     * <h2>Reihenfolge der Pruefungen</h2>
     * {@code 404} (Termin unbekannt), dann {@code 409 TERMIN_GESCHLOSSEN}, dann {@code 404}
     * (keine Teilnahme dieses Gastes). Die Riegel sind dieselben wie bei der eigenen Absage
     * ({@link #pruefeOffen}) und stehen vor dem Schreiben - wie in {@link #rueckmeldung}. Folge,
     * die man kennen muss: Nach Terminbeginn antwortet auch ein wiederholter Aufruf mit
     * {@code 409}, nicht mit {@code 204}.
     *
     * <h2>Zaehler</h2>
     * Nur {@code teilnehmer_version} steigt, nicht {@code kontingent_version} (Regel vom
     * 22.10.2026: Eine Absage oeffnet kein Kontingent). Die Einteilung gilt danach als
     * veraltet, die Warteschlange rueckt beim naechsten Abruf nach - sie ist abgeleitet und
     * muss nicht angefasst werden.
     *
     * <h2>Wiederholbar</h2>
     * Ist die Teilnahme schon eine Absage, antwortet der Dienst ohne Fehler und aendert
     * nichts: weder Zaehler noch Protokoll. Ein zweiter Klick nach einem Netzabbruch schadet so
     * nicht. Entschieden wird das in derselben Anweisung wie das Schreiben
     * ({@code TeilnahmeRepository#gastAbsagen}), nicht durch ein Lesen davor.
     *
     * <h2>Was unberuehrt bleibt</h2>
     * Gastsitzung und Gastplatz - der Gast ist womoeglich noch angemeldet, und das Abmelden
     * ist eine andere Handlung ({@code /admin/gast/freigeben}). Die Stufe bleibt ebenfalls:
     * Sagt der Gast spaeter wieder zu, gilt die Rueckmeldung ohnehin neu.
     *
     * <p><b>Keine Push-Nachricht.</b> Das Projekt kennt genau zwei Versandanlaesse (A25b: die
     * Erinnerung an eine offene Rueckmeldung und die Terminabsage durch den Admin); eine
     * Nachricht an Nachrueckende gehoert nicht dazu, und weitere Anlaesse sind ausgeschlossen.
     *
     * <p><b>Der Termin wird nativ gelesen</b> ({@code findeEintrag}), nicht ueber
     * {@code findById}: Der Zaehler wird gleich per SQL erhoeht und schreibt
     * {@code termin.version} fort; eine daneben verwaltete Entity truege danach eine veraltete
     * Version.
     *
     * @param terminId       betroffener Termin
     * @param gastName       Gastname, zeichengenau wie in der Teilnehmerliste
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 404}, wenn es den Termin nicht gibt oder der Gast an
     *                          diesem Termin keine Teilnahme hat; {@code 409
     *                          TERMIN_GESCHLOSSEN}, wenn der Termin nicht mehr
     *                          {@code GEPLANT} ist oder begonnen hat
     */
    @Transactional
    public void gastAbsagen(Long terminId, String gastName, Long adminSpielerId, String clientIp) {

        // Die Spalte "eigene Rueckmeldung" des Eintrags interessiert hier nicht; die Id des
        // Admins haelt die Abfrage auf dem Weg, den auch die Einzelansicht fuer den Admin geht.
        TerminEintrag termin = terminRepository
                .findeEintrag(terminId, adminSpielerId, null)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        pruefeOffen(termin);

        GastAbsageErgebnis ergebnis = teilnahmeRepository.gastAbsagen(terminId, gastName);

        switch (ergebnis) {
            case KEINE_TEILNAHME -> throw new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                    "Für diesen Termin liegt keine Zusage dieses Gastes vor.");
            case BEREITS_ABGESAGT -> {
                // Nichts zu tun und nichts zu belegen: Der Zustand, den der Aufrufer wollte,
                // besteht bereits.
            }
            case ABGESAGT -> {
                // In derselben Transaktion wie die Aenderung - sonst gaelte eine Einteilung als
                // aktuell, obwohl der Kreis schon ein anderer ist. Nur die Veraltung, nicht das
                // Kontingent: Eine Absage verschafft niemandem einen weiteren Lauf.
                terminRepository.teilnehmerVersionErhoehen(terminId);

                Map<String, Object> details = new LinkedHashMap<>();
                details.put("gastName", gastName);

                auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.GAST_ABGESAGT,
                        ENTITAET, terminId, details);
            }
        }
    }

    // ------------------------------------------------------------------ Uebersicht

    /**
     * Liefert die Zusagen eines Termins samt der geltenden Grenzen (A10, A11).
     *
     * <p><b>Die Grenzen werden bei jedem Abruf frisch gelesen.</b> {@code ConfigService} hat
     * bewusst keinen Zwischenspeicher; senkt der Admin {@code max_teilnehmer}, wandert die
     * Warteschlangengrenze sofort mit, ohne dass eine Teilnahme angefasst wird.
     *
     * <p><b>Die Gast-Stufe nur fuer den Admin (24.10.2026):</b> Mit {@code mitGastStufe} tragen
     * die Gaeste unter den Eintraegen ihre Stufe, sonst nicht. Der Dienst reicht den
     * Wahrheitswert nur durch - <b>wer ihn bildet, steht im Aufrufer</b>
     * ({@code TerminService#einzelnLesen}), und es gibt keinen zweiten.
     *
     * @param terminId     betroffener Termin
     * @param mitGastStufe {@code true} nur fuer eine Sitzung mit der Rolle {@code ADMIN}
     * @return Zusagen in Meldereihenfolge, dazu Mindest- und Hoechstzahl
     */
    @Transactional(readOnly = true)
    public Teilnehmeruebersicht uebersicht(Long terminId, boolean mitGastStufe) {
        AppConfig konfiguration = configService.lesen();
        return new Teilnehmeruebersicht(
                konfiguration.getMinTeilnehmer(),
                konfiguration.getMaxTeilnehmer(),
                teilnahmeRepository.findeZusagen(terminId, mitGastStufe));
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
