package de.fubo.appserver.service.spieltag;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.spieltag.Termin;
import de.fubo.appserver.domain.spieltag.TerminEintrag;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import de.fubo.appserver.dto.spieltag.TerminAendernRequest;
import de.fubo.appserver.dto.spieltag.TerminAngelegt;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.audit.AuditService;
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

    /** Betroffene Entitaet im Audit-Log. */
    private static final String ENTITAET = "termin";

    private final TerminRepository terminRepository;
    private final AuditService auditService;
    private final Clock uhr;

    public TerminService(TerminRepository terminRepository, AuditService auditService, Clock uhr) {
        this.terminRepository = terminRepository;
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
     * Liefert einen einzelnen Termin (S4, Abschnitt 2.1).
     *
     * @param terminId gesuchter Termin
     * @param sitzung  aufrufende Sitzung
     * @return der Termin samt Zusagenzahl, eigener Rueckmeldung und Version
     * @throws FachlicherFehler {@code 404 INHALT_NICHT_GEFUNDEN}, wenn es die Id nicht gibt
     */
    @Transactional(readOnly = true)
    public TerminEintrag einzelnLesen(Long terminId, AktiveSitzung sitzung) {
        return terminRepository.findeEintrag(terminId, sitzung.spielerId(), sitzung.gastName())
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));
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

        // Vor dem Schreiben vergleichen - danach steht der alte Wert nirgends mehr.
        Map<String, Object> geaenderteFelder = unterschiede(termin, anfrage, neuesDatum, neueUhrzeit);

        termin.setDatum(neuesDatum);
        termin.setUhrzeit(neueUhrzeit);
        if (anfrage.ort() != null) {
            // Getrimmt wird hier und nicht im DTO: Dort waere die leere Angabe zu null
            // geworden und damit nicht mehr von "nicht angegeben" zu unterscheiden.
            String bereinigt = anfrage.ort().trim();
            termin.setOrt(bereinigt.isEmpty() ? null : bereinigt);
        }

        // saveAndFlush und nicht nur save: Der Sperrkonflikt soll hier auftreten und nicht
        // erst beim Commit, wo er ausserhalb dieser Transaktion laege.
        terminRepository.saveAndFlush(termin);

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.TERMIN_GEAENDERT,
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

    // ------------------------------------------------------------------ Hilfsmittel

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
