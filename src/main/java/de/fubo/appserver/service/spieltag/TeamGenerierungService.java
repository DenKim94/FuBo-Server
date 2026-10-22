package de.fubo.appserver.service.spieltag;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.spieltag.ManuelleAuswahl;
import de.fubo.appserver.domain.spieltag.Restkontingent;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import de.fubo.appserver.domain.spieltag.Terminzustand;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.Bankentscheid;
import de.fubo.appserver.domain.team.Bankkandidat;
import de.fubo.appserver.domain.team.BisherigeAufteilung;
import de.fubo.appserver.domain.team.Einteilung;
import de.fubo.appserver.domain.team.Einteilungseintrag;
import de.fubo.appserver.domain.team.Generierungskopf;
import de.fubo.appserver.domain.team.Teamergebnis;
import de.fubo.appserver.domain.team.Zuteilungssatz;
import de.fubo.appserver.domain.team.Zuteilungszeile;
import de.fubo.appserver.repository.spieltag.KontingentRepository;
import de.fubo.appserver.repository.spieltag.TeamGenerierungRepository;
import de.fubo.appserver.repository.spieltag.TeilnahmeRepository;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.config.ConfigService;
import de.fubo.appserver.service.team.AuswechselErmittlung;
import de.fubo.appserver.service.team.SeedQuelle;
import de.fubo.appserver.service.team.Teamrechner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Der Generierungslauf: Kontingent, Seed, Rechnung, Snapshot und Protokoll
 * (A15, A20b, A24; S5 Abschnitte 6 bis 9).
 *
 * <h2>Zwei Eingangstueren, ein Generator</h2>
 * {@link #generieren} laeuft am Termin und speichert; {@link #manuell} rechnet auf einer frei
 * gewaehlten Menge und speichert nichts (A24). <b>Beide rufen denselben
 * {@link Teamrechner}</b> - die Verzweigung endet im {@link AufstellungService}, und ab der
 * Aufstellung erfaehrt niemand mehr die Herkunft.
 *
 * <h2>Warum dieser Dienst in {@code service.spieltag} liegt</h2>
 * Er kennt Termin, Sitzung, Kontingent und Audit-Log - alles Spieltagswissen. Was mit der
 * Aufstellung <i>gerechnet</i> wird, liegt in {@code service.team} und kennt nichts davon.
 *
 * <h2>Der Auswechselspieler wird zweimal bestimmt, und das ist Absicht</h2>
 * Einmal im Lauf, einmal beim Lesen einer gespeicherten Einteilung ({@link #einteilungLesen}).
 * Gespeichert wird er nicht - {@code team_zuteilung} hat keine Spalte dafuer, und eine haette
 * die Migration {@code V012} gekostet und S5 seine Migrationsfreiheit genommen (8.3).
 * <b>Deshalb liest der Termin-Lauf sein eigenes Ergebnis zurueck</b>, statt die Antwort aus
 * dem Rechenergebnis zu bauen: Weichen Lauf und Ableitung voneinander ab, faellt es sofort
 * auf und nicht erst beim naechsten Oeffnen des Termins.
 */
@Service
public class TeamGenerierungService {

    /** Betroffene Entitaet im Audit-Log; der manuelle Lauf traegt keine. */
    private static final String ENTITAET = "termin";

    private final AufstellungService aufstellungService;
    private final TerminRepository terminRepository;
    private final TeilnahmeRepository teilnahmeRepository;
    private final KontingentRepository kontingentRepository;
    private final TeamGenerierungRepository teamGenerierungRepository;
    private final ConfigService configService;
    private final Teamrechner teamrechner;
    private final SeedQuelle seedQuelle;
    private final AuswechselErmittlung auswechselErmittlung;
    private final AuditService auditService;

    public TeamGenerierungService(AufstellungService aufstellungService,
                                  TerminRepository terminRepository,
                                  TeilnahmeRepository teilnahmeRepository,
                                  KontingentRepository kontingentRepository,
                                  TeamGenerierungRepository teamGenerierungRepository,
                                  ConfigService configService,
                                  Teamrechner teamrechner,
                                  SeedQuelle seedQuelle,
                                  AuswechselErmittlung auswechselErmittlung,
                                  AuditService auditService) {
        this.aufstellungService = aufstellungService;
        this.terminRepository = terminRepository;
        this.teilnahmeRepository = teilnahmeRepository;
        this.kontingentRepository = kontingentRepository;
        this.teamGenerierungRepository = teamGenerierungRepository;
        this.configService = configService;
        this.teamrechner = teamrechner;
        this.seedQuelle = seedQuelle;
        this.auswechselErmittlung = auswechselErmittlung;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ Lauf am Termin

    /**
     * Erzeugt die Teameinteilung eines Termins (A15, S5 Abschnitt 7.1).
     *
     * <h2>Die Reihenfolge der neun Schritte ist Teil der Zusicherung</h2>
     * <ol>
     *   <li>Termin laden, <b>Berechtigung</b> und Zustand pruefen</li>
     *   <li>{@code teilnehmer_version} und {@code kontingent_version} merken</li>
     *   <li>Aufstellung ermitteln - prueft Mindestzahl und Skillwerte</li>
     *   <li><b>Kontingent verbrauchen</b></li>
     *   <li>Seed ziehen, aktuelle Einteilung als "nicht wiederholen" lesen, rechnen,
     *       Auswechselspieler bestimmen</li>
     *   <li>bestehenden Lauf abloesen</li>
     *   <li>Kopf und Zuteilungen schreiben</li>
     *   <li>Version gegenpruefen</li>
     *   <li>Protokolleintrag</li>
     * </ol>
     *
     * <p><b>Das Kontingent vor der Rechnung.</b> Wer erst rechnet und dann prueft, verschenkt
     * bei {@code EXHAUSTIV} eine Zehntelsekunde CPU an jeden, der zu oft drueckt - und macht
     * daraus ein Mittel, den Server zu beschaeftigen.
     *
     * <p><b>Wer generieren darf (Aenderung vom 22.10.2026):</b> der Admin und jeder
     * Teilnehmer <b>mit Zusage</b> an genau diesem Termin - Spieler wie Gast. Wartende zaehlen
     * mit: Sie haben zugesagt, und ob sie nachruecken, entscheidet sich erst durch die
     * Aufstellung. Wer nie oder nicht mehr zugesagt hat, bekommt {@code 403 KEINE_ZUSAGE}.
     * Die Regel haengt an Daten des Termins und kann deshalb nicht im Pfad der Filterchain
     * stehen, sondern gehoert hierher (gleiche Aufteilung wie bei
     * {@code TeilnahmeService#pruefeKeinAdminprofil}). Sie steht <b>nach</b> der Pruefung
     * auf Existenz - ein unbekannter Termin bleibt {@code 404} - und <b>vor</b> Status und
     * Fixierung: Ein Aussenstehender erfaehrt nichts ueber den Zustand eines Termins, an dem
     * er nicht teilnimmt.
     *
     * <p><b>Zwei Schluessel:</b> Das Kontingent haengt an {@code kontingent_version}, die
     * Gegenpruefung am Ende an {@code teilnehmer_version}. Eine Absage veraendert den
     * Teilnehmerkreis (also scheitert ein gleichzeitig laufender Lauf mit
     * {@code TEILNEHMER_GEAENDERT}), oeffnet aber kein Kontingent.
     *
     * <p><b>Zustand vor Aufstellung.</b> Ein abgesagter oder begonnener Termin soll den Grund
     * melden, den der Nutzer nicht beheben kann, nicht {@code ZU_WENIG_TEILNEHMER}. Dass der
     * {@link AufstellungService} den Status ein zweites Mal prueft, ist kein Versehen: Dort
     * beantwortet er "wer steht auf dem Platz", hier ist er der Torwaechter des Schreibpfads.
     *
     * <p><b>Alles in einer Transaktion.</b> Scheitert die Gegenpruefung in Schritt 8, rollen
     * Kontingent, Abloesung und Zuteilungen gemeinsam zurueck - der gescheiterte Lauf kostet
     * also kein Kontingent.
     *
     * @param terminId betroffener Termin
     * @param sitzung  aufrufende Sitzung; liefert Akteur und Anzeigenamen
     * @param clientIp Adresse des Aufrufers, fuer das Protokoll
     * @return die soeben geschriebene Einteilung, aus der Datenbank zurueckgelesen
     * @throws FachlicherFehler {@code 404 INHALT_NICHT_GEFUNDEN};
     *                          {@code 403 KEINE_ZUSAGE};
     *                          {@code 409 TERMIN_GESCHLOSSEN}; {@code 409 TEAMS_FIXIERT};
     *                          {@code 409 ZU_WENIG_TEILNEHMER};
     *                          {@code 409 SKILLWERTE_UNVOLLSTAENDIG};
     *                          {@code 409 KONTINGENT_ERSCHOEPFT};
     *                          {@code 409 TEILNEHMER_GEAENDERT}
     */
    @Transactional
    public Einteilung generieren(Long terminId, AktiveSitzung sitzung, String clientIp) {

        Terminzustand zustand = terminRepository.zustand(terminId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        pruefeBerechtigung(terminId, sitzung);

        if (zustand.status() != TerminStatus.GEPLANT) {
            throw new FachlicherFehler(Fehlercode.TERMIN_GESCHLOSSEN,
                    "Für diesen Termin lassen sich keine Teams mehr generieren.");
        }
        if (zustand.teamsFixiert()) {
            throw new FachlicherFehler(Fehlercode.TEAMS_FIXIERT);
        }

        int version = zustand.teilnehmerVersion();
        AppConfig konfiguration = configService.lesen();
        Aufstellung aufstellung = aufstellungService.fuerTermin(terminId);

        kontingentVerbrauchen(terminId, sitzung, zustand.kontingentVersion(),
                konfiguration.getAnzTeamGenerator());

        long seed = seedQuelle.naechster();
        // Gelesen wird die aktuelle Einteilung, bevor sie abgeloest wird: Der neue Lauf soll
        // die Teams neu durchmischen und darf sie deshalb nicht wiederholen.
        BisherigeAufteilung bisherige = bisherigeAufteilung(terminId, aufstellung);
        Teamergebnis ergebnis = teamrechner.rechne(aufstellung,
                konfiguration.getAlgorithmType(), konfiguration.getAuswechselModus(), seed,
                bisherige);

        teamGenerierungRepository.abloesen(terminId);
        Long generierungId = teamGenerierungRepository.einfuegen(
                terminId, version, seed, sitzung.spielerId(), sitzung.gastName(),
                ergebnis.kostenAlsDezimal());
        teamGenerierungRepository.zuteilungenSchreiben(generierungId, zuteilungen(ergebnis));

        pruefeVersionUnveraendert(terminId, version);

        auditService.protokolliere(sitzung.spielerId(), clientIp, AuditAktion.TEAMS_GENERIERT,
                ENTITAET, terminId, laufDetails(ergebnis));

        // Ueber die private Methode und nicht ueber einteilungLesen: Ein Selbstaufruf liefe
        // am Spring-Proxy vorbei, und was aussieht wie ein Transaktionswechsel, soll auch
        // einer sein. Dieselbe Aufteilung wie in ConfigService.
        return aktuelleEinteilung(terminId).orElseThrow(() -> new IllegalStateException(
                "Der soeben geschriebene Generierungslauf ist nicht wieder auffindbar."));
    }

    /**
     * Die aktuelle Einteilung des Termins als Aufteilung der neuen Aufstellung - oder
     * {@code null}, wenn es nichts zu meiden gibt.
     *
     * <h2>Wann {@code null}</h2>
     * Wenn noch niemand generiert hat, und wenn die bisherige Einteilung <b>einen anderen
     * Teilnehmerkreis</b> hatte: Wer neu zugesagt oder abgesagt hat, macht jede Aufteilung der
     * neuen Aufstellung ohnehin zu einer anderen Einteilung. Gleich ist der Kreis genau dann,
     * wenn beide gleich gross sind und jede Teilnahme der Aufstellung in der alten Einteilung
     * vorkommt. Eine <b>Skill-Aenderung</b> aendert den Kreis nicht - dort gilt die Regel
     * weiter, auch wenn die neuen Werte eine andere Aufteilung nahelegen.
     *
     * <p>Verglichen wird ueber die Teilnahme-Id ({@code team_zuteilung.teilnahme_id}), nicht
     * ueber den Namen: Ein Gast mit demselben Namen an einem anderen Termin ist eine andere
     * Teilnahme, und wer absagt und wieder zusagt, behaelt seine Zeile.
     */
    private BisherigeAufteilung bisherigeAufteilung(Long terminId, Aufstellung aufstellung) {
        Map<Long, Character> teams = teamGenerierungRepository.findeAktuelleTeams(terminId);
        if (teams.size() != aufstellung.groesse()) {
            return null;
        }

        List<Integer> teamA = new ArrayList<>();
        List<Integer> teamB = new ArrayList<>();
        for (int position = 0; position < aufstellung.groesse(); position++) {
            Long teilnahmeId = aufstellung.spieler().get(position).teilnahmeId();
            Character team = teilnahmeId == null ? null : teams.get(teilnahmeId);
            if (team == null) {
                return null;
            }
            (team == 'A' ? teamA : teamB).add(position);
        }
        return new BisherigeAufteilung(teamA, teamB);
    }

    // ------------------------------------------------------------------ Manueller Lauf (A24)

    /**
     * Rechnet eine Einteilung auf einer frei gewaehlten Teilnehmermenge (A24, S5 Abschnitt 9.4).
     *
     * <h2>Er speichert nichts</h2>
     * {@code team_generierung.termin_id} ist {@code NOT NULL} und
     * {@code team_zuteilung.teilnahme_id} haengt am Fremdschluessel auf
     * {@code spieltag.teilnahme}; ein Teilnehmer ohne Teilnahmezeile passt dort nicht hinein,
     * und ein Phantom-Termin machte aus einer Adminrechnung einen Spieltag. <b>Die Historie
     * traegt allein der Audit-Eintrag</b> - mit Teilnehmern, Seed, Verfahren und Kosten, und
     * damit mehr als sonst ueblich.
     *
     * <h2>Kein Kontingent</h2>
     * A15 zaehlt Laeufe je Termin und Teilnehmerstand; beides fehlt. Schutz vor Dauerlaeufen
     * sind der Zugang (der Endpunkt liegt unter {@code /admin/}) und {@code MAX_EXHAUSTIV}.
     *
     * @param auswahl  die benannten Profile und Gaeste
     * @param sitzung  aufrufende Adminsitzung
     * @param clientIp Adresse des Aufrufers, fuer das Protokoll
     * @return das Rechenergebnis; es wird nirgends abgelegt
     * @throws FachlicherFehler {@code 400 EINGABE_UNGUELTIG}; {@code 409 PROFIL_GESCHUETZT};
     *                          {@code 409 ZU_WENIG_TEILNEHMER};
     *                          {@code 409 ZU_VIELE_TEILNEHMER};
     *                          {@code 409 SKILLWERTE_UNVOLLSTAENDIG}
     */
    @Transactional
    public Teamergebnis manuell(ManuelleAuswahl auswahl, AktiveSitzung sitzung, String clientIp) {

        Aufstellung aufstellung = aufstellungService.manuell(auswahl);
        AppConfig konfiguration = configService.lesen();

        long seed = seedQuelle.naechster();
        Teamergebnis ergebnis = teamrechner.rechne(aufstellung,
                konfiguration.getAlgorithmType(), konfiguration.getAuswechselModus(), seed);

        // entitaet und entitaet_id bleiben leer: Es entsteht keine Ressource, auf die sie
        // zeigen koennten.
        auditService.protokolliere(sitzung.spielerId(), clientIp,
                AuditAktion.TEAMS_MANUELL_GENERIERT, null, null, manuellDetails(ergebnis));

        return ergebnis;
    }

    // ------------------------------------------------------------------ Lesen

    /**
     * Liefert die aktuelle Einteilung eines Termins (S5 Abschnitte 9.1, 9.2).
     *
     * <p><b>{@link Optional#empty()} ist der Normalzustand</b>, nicht ein Fehler: Solange
     * niemand generiert hat, gibt es keine Einteilung. Die Einzelansicht traegt dafuer ein
     * leeres Feld.
     *
     * @param terminId gesuchter Termin
     * @return die Einteilung oder {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<Einteilung> einteilungLesen(Long terminId) {
        return aktuelleEinteilung(terminId);
    }

    /**
     * Holt die aktuelle Einteilung.
     *
     * <p>Getrennt von {@link #einteilungLesen}, damit {@link #generieren} sie nicht ueber den
     * eigenen Aufruf holen muss: Ein Selbstaufruf laeuft am Spring-Proxy vorbei,
     * {@code readOnly} bliebe dabei wirkungslos - hier folgenlos, aber nur zufaellig.
     */
    private Optional<Einteilung> aktuelleEinteilung(Long terminId) {
        return teamGenerierungRepository.findeAktuellen(terminId).map(this::zusammensetzen);
    }

    // ------------------------------------------------------------------ Hilfsmittel

    /**
     * Verbucht den Lauf auf dem Kontingent des Aufrufers (A15, 6.1 und 6.4).
     *
     * <h2>Der Akteur ist die Sitzung, nie der Anfragekoerper</h2>
     * Ein Spieler zaehlt ueber seine Profil-Id, ein Gast ueber den belegten Platz - eine
     * Profil-Id hat er nicht, und {@code ck_kontingent_akteur} verlangt genau eine der beiden
     * Spalten.
     *
     * <p><b>Das Adminprofil generiert mit eigenem Kontingent</b> (Weggabelung D, bestaetigt):
     * Generieren ist eine Handlung <i>am</i> Spieltag und nicht <i>im</i> Spieltag - der Admin
     * steht vor Ort und liest die Teams vor. Ihn auszusperren hiesse, dass er ein
     * Spielerprofil borgen muesste.
     *
     * <p>Fehlen beide Kennungen, ist die Sitzung in einem Zustand, den die Filterchain nicht
     * durchlaesst - das ist ein Programmierfehler und kein Eingabefall.
     */
    private void kontingentVerbrauchen(Long terminId, AktiveSitzung sitzung,
                                       int version, short grenze) {
        Akteur akteur = akteur(sitzung);

        kontingentRepository.verbrauche(terminId, akteur.spielerId(), akteur.gastSlotId(),
                        version, grenze)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.KONTINGENT_ERSCHOEPFT,
                        "Für diesen Teilnehmerstand wurden bereits " + grenze
                                + " Läufe verbraucht. Sobald jemand neu zusagt oder sich "
                                + "ein Skillwert ändert, steht das Kontingent wieder offen."));
    }

    /**
     * Stellt sicher, dass die Sitzung an diesem Termin generieren darf (22.10.2026).
     *
     * <h2>Admin oder Teilnehmer mit Zusage</h2>
     * Der Admin darf immer - er steht vor Ort und liest die Teams vor, auch ohne selbst zu
     * spielen. Jeder andere braucht eine Teilnahme mit {@code zusage = true}: Spieler ueber
     * die Profil-Id, Gast ueber den Namen. Die Pruefung ist die einzige Quelle dieser Regel;
     * der Pfad {@code /teams/generieren} bleibt fuer jede angemeldete Sitzung offen, weil
     * die Filterchain keine Termindaten kennt.
     *
     * <p><b>Warum {@code Rolle.ADMIN} und nicht "hat keine Zusage":</b> Ein Admin, der selbst
     * zugesagt hat, wird ohnehin durchgelassen; einer, der es nicht hat, soll nicht auf die
     * Idee kommen muessen, fuer die Teameinteilung zuzusagen.
     *
     * <p><b>Der Gastname wird als Name verglichen</b> ({@code gast_name} ist je Termin
     * eindeutig). Ein gleichnamiger Gast mit anderer Sitzung und Zusage am selben Termin
     * ist nicht ausgeschlossen, aber auch kein Zugewinn an Rechten: Er koennte dieselbe
     * Teilnahme nutzen, die A7 ihm ohnehin zuweist.
     */
    private void pruefeBerechtigung(Long terminId, AktiveSitzung sitzung) {
        if (!darfGenerieren(terminId, sitzung)) {
            throw new FachlicherFehler(Fehlercode.KEINE_ZUSAGE);
        }
    }

    /**
     * Die Regel hinter {@link #pruefeBerechtigung} als Frage: Admin oder Zusage am Termin.
     *
     * <p><b>Eine Stelle fuer beide Wege.</b> Der Lauf und die Auskunft in der Einzelansicht
     * ({@link #restkontingentLesen}) beantworten dieselbe Frage; zwei Nachbauten liessen die
     * Anzeige eines Tages etwas anderes sagen als der Lauf.
     */
    private boolean darfGenerieren(Long terminId, AktiveSitzung sitzung) {
        return sitzung.rolle() == Rolle.ADMIN
                || teilnahmeRepository.hatZugesagt(terminId, sitzung.spielerId(), sitzung.gastName());
    }

    // ------------------------------------------------------------------ Restkontingent

    /**
     * Das Restkontingent der Sitzung an einem Termin, fuer die Einzelansicht (A15,
     * Ergaenzung vom 22.10.2026).
     *
     * <h2>Dieselbe Frage wie {@link #generieren}, nur ohne Verbuchen</h2>
     * Berechtigung ({@link #darfGenerieren}), Akteur ({@link #akteur}), Schluessel
     * ({@code kontingent_version} aus {@code TerminRepository#zustand}) und Grenze
     * ({@code anz_team_generator}) stammen aus denselben Quellen wie im Lauf. Der Schluessel
     * wird nicht nachgebaut: Gelesen wird die Zeile, die {@code KontingentRepository#verbrauche}
     * per {@code ON CONFLICT} trifft.
     *
     * <h2>{@code null} (leeres Optional) statt {@code 0}</h2>
     * Wer nicht generieren darf (weder Admin noch Zusage an diesem Termin) oder an einem Termin,
     * der nicht {@code GEPLANT} ist, hat kein Kontingent, das sich aufbrauchen liesse.
     * {@code uebrig = 0} bedeutete etwas anderes - "erschoepft, eine neue Zusage hilft" - und
     * verleitete den Client zu einem Hinweis, der hier falsch ist. <b>{@code teams_fixiert}
     * bleibt unberuecksichtigt:</b> Die Auskunft betrifft das Kontingent, nicht die Fixierung;
     * die liefert der Lauf selbst als {@code 409 TEAMS_FIXIERT}.
     *
     * <h2>Eine Anzeige, keine Zusicherung</h2>
     * Die Zahl ist eine Momentaufnahme. Durchgesetzt wird das Kontingent weiterhin von dem
     * bedingten {@code UPDATE} in {@code verbrauche}.
     *
     * <h2>Kosten</h2>
     * Drei indizierte Einzelabfragen (Zustand, Zusage - beim Admin entfaellt sie -, Kontingent),
     * kein Schreibzugriff: Der Endpunkt wird vom Client gepollt. Bei einem unbekannten Termin
     * oder einem fremden Status endet die Auskunft nach der ersten Abfrage.
     *
     * @param terminId betroffener Termin
     * @param sitzung  aufrufende Sitzung; liefert Rolle, Identitaet und Gastplatz
     * @return das Restkontingent; leer, wenn die Sitzung nicht generieren darf oder der Termin
     *         nicht {@code GEPLANT} ist
     */
    @Transactional(readOnly = true)
    public Optional<Restkontingent> restkontingentLesen(Long terminId, AktiveSitzung sitzung) {

        Optional<Terminzustand> gelesen = terminRepository.zustand(terminId);
        if (gelesen.isEmpty() || gelesen.get().status() != TerminStatus.GEPLANT) {
            return Optional.empty();
        }
        if (!darfGenerieren(terminId, sitzung)) {
            return Optional.empty();
        }

        Akteur akteur = akteur(sitzung);
        int erlaubt = configService.lesen().getAnzTeamGenerator();
        int verbraucht = kontingentRepository.verbraucht(terminId, akteur.spielerId(),
                akteur.gastSlotId(), gelesen.get().kontingentVersion());

        return Optional.of(new Restkontingent(erlaubt, Math.max(0, erlaubt - verbraucht)));
    }

    /**
     * Der Akteur des Kontingents: ein Spieler ueber seine Profil-Id, ein Gast ueber den
     * belegten Platz. Genau eines von beiden ist gesetzt.
     *
     * <p>Fehlen beide Kennungen, ist die Sitzung in einem Zustand, den die Filterchain nicht
     * durchlaesst - das ist ein Programmierfehler und kein Eingabefall.
     */
    private static Akteur akteur(AktiveSitzung sitzung) {
        Long spielerId = sitzung.spielerId();
        Short gastSlotId = spielerId == null ? sitzung.gastSlotId() : null;

        if (spielerId == null && gastSlotId == null) {
            throw new IllegalStateException(
                    "Die Sitzung trägt weder eine Profil-Id noch einen Gastplatz.");
        }
        return new Akteur(spielerId, gastSlotId);
    }

    /** Schluesselteil des Kontingents; eines der beiden Felder ist {@code null}. */
    private record Akteur(Long spielerId, Short gastSlotId) {
    }

    /**
     * Prueft, ob sich der Teilnehmerkreis waehrend des Laufs geaendert hat (6.3).
     *
     * <p><b>Gegenpruefen statt sperren.</b> Die Alternative waere ein
     * {@code SELECT ... FOR SHARE} auf dem Termin ueber die gesamte Rechnung - bei einem
     * Vorgang von Millisekunden der schlechtere Handel. Es geht nichts verloren: Die
     * Ausnahme rollt die Transaktion zurueck, und mit ihr das verbuchte Kontingent.
     * Geprueft wird {@code teilnehmer_version}, also auch eine Absage waehrend des Laufs.
     */
    private void pruefeVersionUnveraendert(Long terminId, int version) {
        int jetzt = terminRepository.zustand(terminId)
                .map(Terminzustand::teilnehmerVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "Der Termin ist waehrend des Laufs verschwunden."));

        if (jetzt != version) {
            throw new FachlicherFehler(Fehlercode.TEILNEHMER_GEAENDERT);
        }
    }

    /**
     * Setzt die Zuteilungszeilen zusammen - erst Team A, dann Team B, je in Laufreihenfolge.
     *
     * <p><b>Die Reihenfolge ist Teil der Zusicherung</b> und keine Frage des Geschmacks: Der
     * Auswechselspieler wird beim Lesen erneut bestimmt, und bei Gleichstand entscheidet der
     * Seed ueber die Position in der Kandidatenliste. Nur wenn die Zeilen in derselben Ordnung
     * zurueckkommen, faellt die Wahl genauso aus (8.3).
     *
     * <p>Eine fehlende Teilnahme-Id waere ein halb gebauter Lauf: Sie kann nur im manuellen
     * Lauf fehlen, und der kommt hier nie an. <b>Lieber eine {@code IllegalStateException} als
     * eine halb geschriebene Einteilung.</b>
     */
    private static List<Zuteilungssatz> zuteilungen(Teamergebnis ergebnis) {
        List<Zuteilungssatz> saetze = new ArrayList<>(ergebnis.aufstellung().groesse());
        zuteilungenJeTeam(ergebnis, ergebnis.aufteilung().teamA(), 'A', saetze);
        zuteilungenJeTeam(ergebnis, ergebnis.aufteilung().teamB(), 'B', saetze);
        return saetze;
    }

    private static void zuteilungenJeTeam(Teamergebnis ergebnis, List<Integer> team,
                                          char kennung, List<Zuteilungssatz> ziel) {
        for (Integer index : team) {
            Aufstellungsspieler spieler = ergebnis.spieler(index);
            if (spieler.teilnahmeId() == null) {
                throw new IllegalStateException(
                    "Ein Teilnehmer ohne Teilnahme-Id lässt sich nicht speichern: "
                            + spieler.anzeigeName());
            }
            ziel.add(new Zuteilungssatz(spieler.teilnahmeId(), kennung,
                    ergebnis.staerkeAlsDezimal(index), spieler.werte()));
        }
    }

    /**
     * Setzt die gelesene Einteilung zusammen und bestimmt den Auswechselspieler neu (8.3).
     *
     * <p><b>Massgeblich ist der heute eingestellte Modus</b>, nicht der von damals - er wird
     * nirgends gespeichert. Das ist vertretbar, weil A20b ihn ausdruecklich als Anzeigeregel
     * fuehrt: Die Einstellung aendert die Einteilung nicht, nur wer von den Eingeteilten
     * aussetzt. <b>Der Seed dagegen kommt aus dem Lauf</b> - ohne ihn faellt die Wahl bei
     * Gleichstand anders aus als damals.
     */
    private Einteilung zusammensetzen(Generierungskopf kopf) {
        List<Zuteilungszeile> zeilen = teamGenerierungRepository.findeZuteilungen(kopf.id());
        List<Zuteilungszeile> teamA = zeilen.stream().filter(z -> z.team() == 'A').toList();
        List<Zuteilungszeile> teamB = zeilen.stream().filter(z -> z.team() == 'B').toList();

        String auswechselspieler = auswechselspieler(teamA, teamB, kopf.seed());

        return new Einteilung(kopf.erzeugtAm(), kopf.erzeugtVon(), kopf.veraltet(),
                auswechselspieler,
                eintraege(teamA, auswechselspieler),
                eintraege(teamB, auswechselspieler));
    }

    /**
     * Bestimmt den Auswechselspieler einer gelesenen Einteilung.
     *
     * <p>Die Auswahl der Ueberzahl folgt derselben Regel wie {@code Teamaufteilung#ueberzahl}:
     * das groessere Team. Bei gerader Zahl gibt es keinen - {@code null}, kein Platzhalter.
     */
    private String auswechselspieler(List<Zuteilungszeile> teamA, List<Zuteilungszeile> teamB,
                                     long seed) {
        if ((teamA.size() + teamB.size()) % 2 == 0) {
            return null;
        }

        List<Zuteilungszeile> ueberzahl = teamA.size() > teamB.size() ? teamA : teamB;
        List<Bankkandidat> kandidaten = new ArrayList<>(ueberzahl.size());
        for (int i = 0; i < ueberzahl.size(); i++) {
            kandidaten.add(new Bankkandidat(
                    i, ueberzahl.get(i).staerke(), ueberzahl.get(i).gemeldetAm()));
        }

        Bankentscheid entscheid = auswechselErmittlung.waehle(
                kandidaten, configService.lesen().getAuswechselModus(), seed);

        return ueberzahl.get(entscheid.position()).anzeigeName();
    }

    /**
     * Bildet die Zeilen eines Teams auf die Antwortform ab.
     *
     * <p>Der Vergleich laeuft ueber den Namen und nicht ueber eine Position: Namen sind je
     * Termin eindeutig - {@code uq_teilnahme_spieler} und der partielle Index ueber
     * {@code (termin_id, gast_name)} sorgen dafuer -, und der Auswechselspieler steht in genau
     * einem der beiden Teams.
     */
    private static List<Einteilungseintrag> eintraege(List<Zuteilungszeile> zeilen,
                                                      String auswechselspieler) {
        return zeilen.stream()
                .map(zeile -> new Einteilungseintrag(zeile.anzeigeName(), zeile.gast(),
                        zeile.anzeigeName().equals(auswechselspieler)))
                .toList();
    }

    /**
     * Die Details des Protokolleintrags am Termin.
     *
     * <p><b>Beiwerk, anders als beim manuellen Lauf:</b> Der Lauf selbst steht mit allem
     * Noetigen in {@code spieltag.team_generierung} und ueberlebt dort auch die Loeschfrist von
     * 30 Tagen. Die Details sind der schnelle Blick, nicht der Beleg.
     */
    private static Map<String, Object> laufDetails(Teamergebnis ergebnis) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("verfahren", ergebnis.verwendetesVerfahren().name());
        details.put("auswechselModus", ergebnis.verwendeterModus().name());
        details.put("teilnehmer", ergebnis.aufstellung().groesse());
        details.put("seed", ergebnis.seed());
        details.put("kosten", ergebnis.kostenAlsDezimal());
        return details;
    }

    /**
     * Die Details des manuellen Laufs - <b>die einzige Spur, die er hinterlaesst</b> (A24).
     *
     * <p>Deshalb stehen hier die Teilnehmer namentlich und der Seed: Ohne sie liesse sich der
     * Lauf spaeter nicht mehr nachrechnen, und eine Tabelle traegt ihn nicht. Der Seed steht
     * <i>nur</i> hier; die Antwort fuehrt ihn nicht mit (9.4).
     */
    private static Map<String, Object> manuellDetails(Teamergebnis ergebnis) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("verfahren", ergebnis.verwendetesVerfahren().name());
        details.put("auswechselModus", ergebnis.verwendeterModus().name());
        details.put("teilnehmer", ergebnis.aufstellung().spieler().stream()
                .map(Aufstellungsspieler::anzeigeName).toList());
        details.put("seed", ergebnis.seed());
        details.put("kosten", ergebnis.kostenAlsDezimal());
        return details;
    }
}
