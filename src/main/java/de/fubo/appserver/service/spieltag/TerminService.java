package de.fubo.appserver.service.spieltag;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.spieltag.Termin;
import de.fubo.appserver.domain.spieltag.TerminEintrag;
import de.fubo.appserver.domain.spieltag.TerminMitTeilnehmern;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import de.fubo.appserver.dto.spieltag.TerminAendernRequest;
import de.fubo.appserver.dto.spieltag.TerminAngelegt;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Termine lesen, anlegen, aendern und absagen (A7, A9, A18; S4 Abschnitte 2 und 3).
 *
 * <h2>Zwei Uebergabeformen, mit Absicht</h2>
 * {@link #anlegen} bekommt Einzelwerte, {@link #aendern} das DTO. Das ist der Regelfall des
 * Projekts und seine begruendete Ausnahme: Beim Anlegen sind die drei Werte verschiedenen
 * Typs, eine Verwechslung waere ein Uebersetzungsfehler. Beim Aendern stuenden dagegen
 * {@code terminId} und {@code version} als zwei {@code Long} nebeneinander - vertauscht
 * kompilieren sie fehlerfrei und schreiben still das Falsche. Genau dafuer nimmt auch
 * {@code ConfigService#aktualisieren} einen Record entgegen.
 *
 * <h2>Warum die Zeitpruefungen hier liegen und nicht am DTO</h2>
 * Sie brauchen die {@code Clock}-Bean. {@code @Future} an einem {@code LocalDate} pruefte
 * gegen die Systemuhr, und der Grenzfall "eine Minute vor Beginn" liesse sich dann nur mit
 * {@code Thread.sleep} testen. Die Uhr traegt seit S4 eine Zeitzone - Begruendung in
 * {@code ZeitConfig}.
 */
@Service
public class TerminService {

    private static final Logger LOG = LoggerFactory.getLogger(TerminService.class);

    /** Betroffene Entitaet im Audit-Log. */
    private static final String ENTITAET = "termin";

    /**
     * Frist bis zum automatischen Abschluss eines Termins (A18, Ergaenzung vom 30.08.2026).
     *
     * <p><b>Eine Konstante und kein Konfigurationsfeld.</b> A18 legt die 30 Minuten als
     * Anforderung fest, nicht als Betriebsgroesse - und ein zwoelftes Feld im Voll-Update der
     * Konfiguration waere fuer den Client-Track eine brechende Vertragsaenderung. Auch der
     * Test braucht sie nicht verstellbar: Er legt den Termin einfach weit genug in die
     * Vergangenheit.
     */
    private static final int ABSCHLUSS_NACH_MINUTEN = 30;

    private final TerminRepository terminRepository;
    private final TeilnahmeService teilnahmeService;
    private final AuditService auditService;
    private final Clock uhr;

    public TerminService(TerminRepository terminRepository,
                         TeilnahmeService teilnahmeService,
                         AuditService auditService,
                         Clock uhr) {
        this.terminRepository = terminRepository;
        this.teilnahmeService = teilnahmeService;
        this.auditService = auditService;
        this.uhr = uhr;
    }

    // ------------------------------------------------------------------ Lesen

    /**
     * Liefert die Termine ab einem Stichtag (S4, Abschnitt 2.1).
     *
     * <p><b>Der Stichtag ist der heutige Tag, wenn keiner angegeben ist.</b> Vergangene
     * Termine sind damit nicht enthalten, solange sie nicht ausdruecklich angefragt werden -
     * das haelt die Antwort klein und macht die Historie trotzdem erreichbar; S6 wird sie
     * fuer die Ergebnisanzeige brauchen.
     *
     * <p><b>Abgesagte Termine bleiben in der Liste.</b> Der Spieler soll sehen, dass sein
     * Training ausfaellt, statt einen Termin vorzufinden, der spurlos verschwunden ist.
     *
     * @param ab      Stichtag einschliesslich oder {@code null} fuer "ab heute"
     * @param sitzung aufrufende Sitzung; entscheidet, wessen Rueckmeldung mitgeliefert wird
     * @return Termine ab dem Stichtag, nach Datum und Uhrzeit sortiert
     */
    @Transactional(readOnly = true)
    public List<TerminEintrag> uebersichtLesen(LocalDate ab, AktiveSitzung sitzung) {
        LocalDate stichtag = ab != null ? ab : LocalDate.now(uhr);
        return terminRepository.findeUebersicht(stichtag, sitzung.spielerId(), sitzung.gastName());
    }

    /**
     * Liefert einen einzelnen Termin samt seiner Teilnehmer (S4, Abschnitte 2.1 und 7.1).
     *
     * <p><b>Beide Abfragen in einer Transaktion.</b> Die Teilnehmerliste bekommt keinen
     * eigenen Endpunkt: Wer einen Termin oeffnet, will die Teilnehmer sehen, und zwei
     * Aufrufe fuer eine Ansicht sind zwei Gelegenheiten fuer einen inkonsistenten Stand -
     * zwischen ihnen kann jemand zusagen, und die Zahl im Kopf passte dann nicht mehr zur
     * Liste darunter.
     *
     * @param terminId gesuchter Termin
     * @param sitzung  aufrufende Sitzung
     * @return Termin und Teilnehmer; die Version ist im Termin enthalten
     * @throws FachlicherFehler {@code 404 INHALT_NICHT_GEFUNDEN}, wenn es die Id nicht gibt
     */
    @Transactional(readOnly = true)
    public TerminMitTeilnehmern einzelnLesen(Long terminId, AktiveSitzung sitzung) {
        TerminEintrag termin = terminRepository
                .findeEintrag(terminId, sitzung.spielerId(), sitzung.gastName())
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        return new TerminMitTeilnehmern(termin, teilnahmeService.uebersicht(terminId));
    }

    // ------------------------------------------------------------------ Anlegen

    /**
     * Legt einen Einzeltermin an (A18, S4 Abschnitt 3).
     *
     * <p><b>Der Zeitpunkt ist global eindeutig</b> ({@code uq_termin_zeit}): Es gibt keine
     * zwei Termine zur selben Zeit, auch nicht an verschiedenen Orten. Die
     * {@code ON CONFLICT}-Klausel im Repository entscheidet den Fall in einer einzigen
     * Anweisung - Begruendung dort.
     *
     * @param datum          Datum in Ortszeit
     * @param uhrzeit        Uhrzeit in Ortszeit
     * @param ort            Spielort oder {@code null}, bereits bereinigt
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @return Id, Datum und Uhrzeit des neuen Termins
     * @throws FachlicherFehler {@code 400 EINGABE_UNGUELTIG} bei einem Zeitpunkt in der
     *                          Vergangenheit, {@code 409 TERMIN_BELEGT} bei einem belegten
     *                          Zeitpunkt
     */
    @Transactional
    public TerminAngelegt anlegen(LocalDate datum, LocalTime uhrzeit, String ort,
                                  Long adminSpielerId, String clientIp) {

        pruefeNichtVergangen(datum, uhrzeit);

        Long terminId = terminRepository.einfuegenWennFrei(null, datum, uhrzeit, ort)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.TERMIN_BELEGT));

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.TERMIN_ANGELEGT,
                ENTITAET, terminId,
                Map.of("datum", datum.toString(), "uhrzeit", uhrzeit.toString()));

        return new TerminAngelegt(terminId, datum, uhrzeit);
    }

    // ------------------------------------------------------------------ Aendern

    /**
     * Aendert Datum, Uhrzeit oder Ort eines Termins (S4, Abschnitt 3.5).
     *
     * <h2>Reihenfolge der Pruefungen</h2>
     * Sie ist Teil der Zusicherung, wie schon beim Anlegen eines Profils in S2b: erst
     * "gibt es ueberhaupt etwas zu tun", dann die Existenz, dann der Zustand, dann die
     * Version, zuletzt die Kollision. Umgekehrt bekaeme ein Aufruf mit veralteter Version
     * die Meldung "Zeitpunkt belegt", und die eigentliche Ursache bliebe unerwaehnt.
     *
     * <h2>Optimistic Locking an zwei Stellen</h2>
     * Der Versionsvergleich hier liefert die verstaendliche Meldung; die eigentliche
     * Absicherung leistet Hibernate beim Schreiben. Beides ist noetig - zwischen Vergleich
     * und Commit liegt ein Fenster, in dem eine zweite Transaktion dieselbe Zeile aendern
     * kann. Diesen Fall faengt der Handler fuer
     * {@code ObjectOptimisticLockingFailureException} mit demselben Fehlercode ab.
     *
     * @param anfrage        Id, Version und die zu aendernden Felder
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 400 EINGABE_UNGUELTIG} ohne Aenderung oder bei einem
     *                          Zeitpunkt in der Vergangenheit; {@code 404}, wenn es die Id
     *                          nicht gibt; {@code 409 TERMIN_GESCHLOSSEN}, wenn der Termin
     *                          nicht mehr {@code GEPLANT} ist; {@code 409 DATEN_VERALTET}
     *                          bei abweichender Version; {@code 409 TERMIN_BELEGT}, wenn der
     *                          neue Zeitpunkt vergeben ist
     */
    @Transactional
    public void aendern(TerminAendernRequest anfrage, Long adminSpielerId, String clientIp) {

        if (anfrage.ohneAenderung()) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Es wurde kein zu änderndes Feld angegeben.");
        }

        Termin termin = terminRepository.findById(anfrage.terminId())
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        if (termin.getStatus() != TerminStatus.GEPLANT) {
            throw new FachlicherFehler(Fehlercode.TERMIN_GESCHLOSSEN,
                    "Ein Termin im Zustand %s lässt sich nicht mehr ändern."
                            .formatted(termin.getStatus()));
        }

        if (!Objects.equals(termin.getVersion(), anfrage.version())) {
            throw new FachlicherFehler(Fehlercode.DATEN_VERALTET);
        }

        LocalDate neuesDatum = anfrage.datum() != null ? anfrage.datum() : termin.getDatum();
        LocalTime neueUhrzeit = anfrage.uhrzeit() != null ? anfrage.uhrzeit() : termin.getUhrzeit();
        boolean zeitpunktAendertSich = !neuesDatum.equals(termin.getDatum())
                || !neueUhrzeit.equals(termin.getUhrzeit());

        if (zeitpunktAendertSich) {
            // Nur beim Verschieben geprueft, nicht bei jeder Aenderung: Sonst liesse sich der
            // Ort eines laengst vergangenen Termins nicht mehr berichtigen, obwohl das
            // niemandem schadet. Verschieben in die Vergangenheit dagegen erzeugt genau den
            // Termin, den das Anlegen ablehnt.
            pruefeNichtVergangen(neuesDatum, neueUhrzeit);

            if (terminRepository.existsByDatumAndUhrzeitAndIdNot(neuesDatum, neueUhrzeit, termin.getId())) {
                throw new FachlicherFehler(Fehlercode.TERMIN_BELEGT);
            }
        }

        TerminStatus neuerStatus = pruefeZielstatus(anfrage.status());

        // Vor dem Schreiben vergleichen - danach steht der alte Wert nirgends mehr.
        Map<String, Object> geaenderteFelder = unterschiede(termin, anfrage, neuesDatum, neueUhrzeit);

        termin.setDatum(neuesDatum);
        termin.setUhrzeit(neueUhrzeit);
        if (neuerStatus != null) {
            termin.setStatus(neuerStatus);
        }
        if (anfrage.ort() != null) {
            // Getrimmt wird hier und nicht im DTO: Dort waere die leere Angabe zu null
            // geworden und damit nicht mehr von "nicht angegeben" zu unterscheiden.
            String bereinigt = anfrage.ort().trim();
            termin.setOrt(bereinigt.isEmpty() ? null : bereinigt);
        }

        // saveAndFlush und nicht nur save: Der Sperrkonflikt soll hier auftreten und nicht
        // erst beim Commit, wo er ausserhalb dieser Transaktion laege.
        terminRepository.saveAndFlush(termin);

        // Eine Absage bleibt eine Absage, auch wenn sie ueber das Bearbeitungsformular kommt:
        // Wer das Protokoll nach abgesagten Terminen durchsieht, soll sie dort finden und
        // nicht zwischen Ortsaenderungen. Die uebrigen geaenderten Felder stehen in
        // denselben Details - es ist ein Vorgang, also ein Eintrag.
        AuditAktion aktion = neuerStatus == TerminStatus.ABGESAGT
                ? AuditAktion.TERMIN_ABGESAGT
                : AuditAktion.TERMIN_GEAENDERT;

        auditService.protokolliere(adminSpielerId, clientIp, aktion,
                ENTITAET, termin.getId(), geaenderteFelder);
    }

    // ------------------------------------------------------------------ Absagen

    /**
     * Setzt einen Termin auf {@link TerminStatus#ABGESAGT} (S4, Abschnitt 3.4).
     *
     * <h2>Warum nie geloescht wird</h2>
     * Fuenf Tabellen haengen mit {@code ON DELETE CASCADE} am Termin: {@code teilnahme} aus
     * {@code V005}, dazu {@code team_generierung}, {@code generierung_kontingent} und
     * {@code ergebnis} aus {@code V006} - und ueber die Generierung auch
     * {@code team_zuteilung}. Ein {@code DELETE} auf einer Zeile raeumte lautlos den halben
     * Spieltag ab, einschliesslich der Ergebnisse aus S6. Dazu kommt: Die Rueckmeldungen
     * sind der einzige Beleg dafuer, wer zugesagt hatte - sie stehen bewusst nicht im
     * Audit-Log.
     *
     * <h2>Die Absage ist endgueltig</h2>
     * Es gibt keinen Weg zurueck nach {@code GEPLANT}: Die Zusagen dazwischen sind
     * unbrauchbar geworden, weil niemand weiss, wer von der Absage schon erfahren hat.
     * Ein zweiter Aufruf laeuft deshalb in {@code 409}.
     *
     * <p><b>Ohne Zeitpruefung</b>, anders als beim Aendern: Einen vergangenen Termin
     * abzusagen ist folgenlos, aber nicht falsch - und die Absage ist der einzige Weg, einen
     * Termin loszuwerden, der nie stattgefunden hat.
     *
     * <p><b>Kein E-Mail-Versand an den Hallenbetreiber.</b> Das ist der Hallenmodus aus A23
     * samt der 48-Stunden-Regel und gehoert zu S7.
     *
     * @param terminId       abzusagender Termin
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 404}, wenn es die Id nicht gibt;
     *                          {@code 409 TERMIN_GESCHLOSSEN}, wenn der Termin nicht mehr
     *                          {@code GEPLANT} ist
     */
    @Transactional
    public void absagen(Long terminId, Long adminSpielerId, String clientIp) {

        Termin termin = terminRepository.findById(terminId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        if (termin.getStatus() != TerminStatus.GEPLANT) {
            throw new FachlicherFehler(Fehlercode.TERMIN_GESCHLOSSEN,
                    "Dieser Termin ist bereits %s.".formatted(
                            termin.getStatus() == TerminStatus.ABGESAGT ? "abgesagt" : "abgeschlossen"));
        }

        termin.setStatus(TerminStatus.ABGESAGT);
        terminRepository.saveAndFlush(termin);

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.TERMIN_ABGESAGT,
                ENTITAET, termin.getId(),
                Map.of("datum", termin.getDatum().toString(),
                        "uhrzeit", termin.getUhrzeit().toString()));
    }

    // ------------------------------------------------------------------ Entfernen

    /**
     * Entfernt einen Termin endgueltig (A19, Ergaenzung vom 30.08.2026).
     *
     * <h2>Nur, solange nichts darauf verweist</h2>
     * Fuenf Tabellen haengen mit {@code ON DELETE CASCADE} am Termin. Ein ungepruefter
     * {@code DELETE} raeumte lautlos den halben Spieltag ab - einschliesslich der
     * Rueckmeldungen, die der einzige Beleg dafuer sind, wer zugesagt hatte, und
     * einschliesslich der Ergebnisse aus S6. Die Kaskaden sind richtig gesetzt; nur darf sie
     * niemand ausloesen. Liegt etwas vor, antwortet der Endpunkt
     * {@code 409 TERMIN_IN_VERWENDUNG} und verweist auf das Absagen - dasselbe Muster wie
     * bei {@code PROFIL_IN_VERWENDUNG} aus S2b.
     *
     * <h2>Wofuer der Vorgang gedacht ist</h2>
     * Fuer den versehentlich angelegten Termin. <b>Er ist zugleich der einzige Weg zurueck
     * aus einer versehentlichen Absage:</b> Ein abgesagter Termin belegt seinen Zeitpunkt
     * weiter, denn {@code uq_termin_zeit} gilt fuer ihn genauso - ohne das Entfernen bliebe
     * der Zeitpunkt dauerhaft gesperrt. Entfernen und neu anlegen ist deshalb der
     * vorgesehene Ablauf, und er funktioniert genau dann, wenn noch niemand geantwortet hat.
     *
     * <p><b>Der Protokolleintrag ist der einzige Rest.</b> Anders als beim Absagen bleibt
     * keine Zeile zurueck; die Details nennen deshalb Datum, Uhrzeit und Status.
     *
     * @param terminId       zu entfernender Termin
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 404}, wenn es die Id nicht gibt;
     *                          {@code 409 TERMIN_IN_VERWENDUNG}, wenn Daten darauf verweisen
     */
    @Transactional
    public void entfernen(Long terminId, Long adminSpielerId, String clientIp) {

        Termin termin = terminRepository.findById(terminId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        if (terminRepository.istReferenziert(terminId)) {
            throw new FachlicherFehler(Fehlercode.TERMIN_IN_VERWENDUNG);
        }

        // Vor dem Loeschen lesen: Danach gibt es die Zeile nicht mehr, und die Entity ist
        // nach dem Flush ohnehin geloest.
        Map<String, Object> details = Map.of(
                "datum", termin.getDatum().toString(),
                "uhrzeit", termin.getUhrzeit().toString(),
                "status", termin.getStatus().name());

        terminRepository.delete(termin);
        terminRepository.flush();

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.TERMIN_ENTFERNT,
                ENTITAET, terminId, details);
    }

    // ------------------------------------------------------------------ Automatischer Abschluss

    /**
     * Schliesst geplante Termine ab, deren Beginn {@value #ABSCHLUSS_NACH_MINUTEN} Minuten
     * zurueckliegt (A18, Ergaenzung vom 30.08.2026).
     *
     * <h2>Warum ein geplanter Auftrag und keine Ableitung beim Lesen</h2>
     * Der Status ist eine gespeicherte Spalte, kein abgeleiteter Wert - S6 haengt daran
     * ({@code ergebnis} gehoert zu einem abgeschlossenen Termin), und ab S5 auch die Frage,
     * ob eine Teameinteilung noch aktuell ist. Ein beim Lesen berechneter Status stuende
     * nirgends, wo eine Fremdschluesselbeziehung ihn sehen kann.
     *
     * <h2>Der Auftrag ist eine Aufraeumung, kein Torwaechter</h2>
     * <b>Keine fachliche Regel haengt an seiner Puenktlichkeit.</b> Ob noch zugesagt werden
     * darf, entscheidet die Uhrzeit des Termins und nicht sein Status (A7) - zwischen Beginn
     * und Abschluss liegen 30 Minuten, in denen der Status noch {@code GEPLANT} ist und
     * trotzdem niemand mehr melden kann. Deshalb genuegt ein Lauf alle fuenf Minuten; der
     * Uebergang findet zwischen 30 und 35 Minuten nach Beginn statt, und das ist fuer eine
     * Statusanzeige unsichtbar. Ein Lauf je Minute waere fuer eine Beschriftung
     * unverhaeltnismaessig.
     *
     * <p><b>Nur aus {@code GEPLANT} heraus</b> - ein abgesagter Termin hat nicht
     * stattgefunden, und ein Abschluss behauptete das Gegenteil.
     *
     * <p><b>Kein Eintrag im Audit-Log.</b> Dort stehen Adminaktionen; dies ist eine
     * Systemhandlung ohne Handelnden, wie der Aufraeumlauf des Protokolls selbst. Sie geht in
     * die Anwendungsprotokollierung - und nur dann, wenn wirklich etwas geschehen ist, sonst
     * fuellte sie das Log mit 288 leeren Zeilen am Tag.
     */
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void abgelaufeneAbschliessen() {
        LocalDateTime grenze = LocalDateTime.now(uhr).minusMinutes(ABSCHLUSS_NACH_MINUTEN);
        int anzahl = terminRepository.abgelaufeneAbschliessen(grenze);

        if (anzahl > 0) {
            LOG.info("Termine automatisch abgeschlossen: {} (Beginn vor {} oder frueher).",
                    anzahl, grenze);
        }
    }

    // ------------------------------------------------------------------ Teilnehmer-Version

    /**
     * Zaehlt die Teilnehmer-Version aller kuenftigen Termine hoch, an denen ein Spieler
     * zugesagt hat (A15; Pflichtpunkt aus S3, Abschnitt 3.5).
     *
     * <h2>Warum eine Skillaenderung dazugehoert</h2>
     * A15 nennt als Ausloeser jede <i>Teilnehmeraenderung</i>. Eine geaenderte Bewertung
     * aendert nicht, wer mitspielt, wohl aber die Grundlage jeder Teameinteilung: Dieselben
     * Namen ergeben eine andere Aufteilung. Ohne diesen Ausloeser bliebe eine gespeicherte
     * Einteilung als aktuell gekennzeichnet, obwohl sie es nicht mehr ist - und das
     * Generierungskontingent bliebe verbraucht.
     *
     * <h2>Warum die Methode hier liegt und nicht in der Profilverwaltung</h2>
     * Das Wissen, dass Skillwerte auf Termine wirken, gehoert zum Fachbereich
     * {@code spieltag}. {@code SpielerVerwaltungService} ruft sie auf und muss dafuer weder
     * die Tabelle noch die Bedingung kennen; der Aufruf laeuft in dessen Transaktion mit
     * ({@code REQUIRED}), sodass Skillwert und Zaehler gemeinsam stehen oder gemeinsam
     * zurueckrollen.
     *
     * @param spielerId Profil, dessen Skillwerte sich geaendert haben
     * @return Anzahl betroffener Termine; {@code 0}, wenn der Spieler nirgends zugesagt hat
     */
    @Transactional
    public int teilnehmerVersionErhoehenFuerSpieler(Long spielerId) {
        return terminRepository.teilnehmerVersionErhoehenFuerSpieler(
                spielerId, LocalDateTime.now(uhr));
    }

    // ------------------------------------------------------------------ Hilfsmittel

    /**
     * Prueft den gewuenschten Zielstatus (A19, Ergaenzung vom 30.08.2026).
     *
     * <p><b>Nur vorwaerts.</b> {@code GEPLANT} ist als Ziel nicht zugelassen: Eine Absage
     * ist endgueltig (Festlegung vom 30.08.2026), und ein wieder geoeffneter vergangener
     * Termin naehme nach A7 ohnehin keine Rueckmeldung mehr an - der Auftrag aus A18 setzte
     * ihn binnen Minuten zurueck. Wer einen versehentlich abgesagten Termin doch braucht,
     * entfernt ihn und legt ihn neu an; dass sein Zeitpunkt dabei frei wird, ist der Grund,
     * aus dem es {@link #entfernen} gibt.
     *
     * <p>Die Ablehnung ist ein {@code 400} und kein {@code 409}: Es ist kein Zustand, der
     * sich mit der Zeit aendert, sondern eine Eingabe, die es nie geben wird.
     *
     * @param gewuenscht Zielstatus aus dem Anfragekoerper; darf {@code null} sein
     * @return der Zielstatus oder {@code null}, wenn keiner angegeben war
     */
    private static TerminStatus pruefeZielstatus(TerminStatus gewuenscht) {
        if (gewuenscht == TerminStatus.GEPLANT) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Ein Termin lässt sich nicht wieder auf GEPLANT setzen. Wer ihn doch "
                            + "braucht, entfernt ihn und legt ihn neu an.");
        }
        return gewuenscht;
    }

    /**
     * Lehnt einen Zeitpunkt ab, der bereits vorbei ist.
     *
     * <p><b>Datum <i>und</i> Uhrzeit, nicht nur der Tag.</b> Die Anleitung nennt in
     * Abschnitt 3.2 "datum nicht in der Vergangenheit", begruendet die Regel aber mit
     * Weggabelung C: Zugesagt wird bis Terminbeginn. Ein Termin, der heute um 8 Uhr war und
     * um 20 Uhr angelegt wird, naehme also nie eine Rueckmeldung entgegen und laege als
     * unveraenderliche Leiche im Bestand.
     *
     * <p>Verglichen wird gegen die {@code Clock}-Bean, nicht gegen
     * {@code LocalDateTime.now()} - sonst waere der Grenzfall "eine Minute vor Beginn" nur
     * mit {@code Thread.sleep} pruefbar.
     */
    private void pruefeNichtVergangen(LocalDate datum, LocalTime uhrzeit) {
        LocalDateTime zeitpunkt = LocalDateTime.of(datum, uhrzeit);
        if (!zeitpunkt.isAfter(LocalDateTime.now(uhr))) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Der Zeitpunkt %s %s liegt in der Vergangenheit."
                            .formatted(datum, uhrzeit));
        }
    }

    /**
     * Stellt fest, welche Felder sich wirklich aendern - fuer das Audit-Log.
     *
     * <p><b>Hier lohnt sich der Vorher-Wert</b>, wie bei der Konfiguration und anders als bei
     * den Skillwerten: Es sind drei Felder mit betrieblicher Bedeutung, und die Frage "wer
     * hat den Ort verlegt" ist ohne den alten Wert nicht zu beantworten.
     *
     * <p>Bleibt die Karte leer, weil der Aufruf nur schon geltende Werte geschickt hat,
     * steht der Eintrag trotzdem: Er belegt, wer wann gespeichert hat, und die
     * {@code version} ist ebenfalls gewandert.
     */
    private static Map<String, Object> unterschiede(Termin bestand, TerminAendernRequest anfrage,
                                                    LocalDate neuesDatum, LocalTime neueUhrzeit) {
        Map<String, Object> felder = new LinkedHashMap<>();

        vergleiche(felder, "datum", bestand.getDatum(), neuesDatum);
        vergleiche(felder, "uhrzeit", bestand.getUhrzeit(), neueUhrzeit);

        if (anfrage.ort() != null) {
            String bereinigt = anfrage.ort().trim();
            vergleiche(felder, "ort", bestand.getOrt(), bereinigt.isEmpty() ? null : bereinigt);
        }
        if (anfrage.status() != null) {
            vergleiche(felder, "status", bestand.getStatus(), anfrage.status());
        }
        return felder;
    }

    /**
     * Traegt ein Feld mit altem und neuem Wert ein, wenn es sich geaendert hat.
     *
     * <p>Die verschachtelte Karte schreibt der {@code AuditService} seit S3 als echtes
     * JSON-Objekt; {@code details->'ort'->>'alt'} trifft damit. Die Werte gehen als
     * Zeichenkette hinein - der handgeschriebene Serialisierer kennt {@code LocalDate} und
     * {@code LocalTime} nicht und legte sie sonst in ihrer {@code toString}-Form ab, was
     * hier zwar dasselbe ergaebe, aber auf Zufall beruhte.
     */
    private static void vergleiche(Map<String, Object> felder, String name, Object alt, Object neu) {
        if (Objects.equals(alt, neu)) {
            return;
        }
        Map<String, Object> aenderung = new LinkedHashMap<>();
        aenderung.put("alt", alt == null ? null : alt.toString());
        aenderung.put("neu", neu == null ? null : neu.toString());
        felder.put(name, aenderung);
    }
}
