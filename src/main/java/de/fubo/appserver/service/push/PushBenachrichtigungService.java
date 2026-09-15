package de.fubo.appserver.service.push;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.common.config.VapidSchluessel;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.push.PushAbo;
import de.fubo.appserver.domain.push.PushNutzlast;
import de.fubo.appserver.domain.push.Versandbilanz;
import de.fubo.appserver.domain.spieltag.Erinnerungstermin;
import de.fubo.appserver.domain.spieltag.TerminAbgesagtEreignis;
import de.fubo.appserver.repository.push.PushAboRepository;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die beiden Versandanlaesse: Erinnerung an offene Rueckmeldungen und Terminabsage (A25b; S8
 * Abschnitte 8 und 9).
 *
 * <h2>Zwei Anlaesse, und es werden bewusst keine weiteren</h2>
 * A25b schliesst "Teameinteilung liegt vor" und "Mindestanzahl erreicht" ausdruecklich aus:
 * Sie feuern mehrfach je Termin, und der Empfaenger entzieht dann die Berechtigung im Browser.
 * <b>Danach erreicht ihn auch die Absage nicht mehr</b> - ein zusaetzlicher Anlass kostet also
 * die beiden, die es gibt.
 *
 * <h2>Warum hier keine Transaktion offen ist - und wie das erreicht wird</h2>
 * Beide Anlaesse rufen einen fremden Dienst ueber HTTPS. <b>Innerhalb einer offenen
 * Transaktion darf das nicht geschehen</b>: Ein Lauf mit dreissig Empfaengern hielte sonst eine
 * Verbindung aus dem Pool ueber dreissig Netzaufrufe hinweg belegt.
 *
 * <p>Die beiden Methoden erreichen das auf verschiedenen Wegen, und der Unterschied ist der
 * Punkt, an dem man sich leicht vertut:
 * <ul>
 *   <li><b>Der Auftrag</b> ({@link #erinnerungVersenden}) traegt <b>kein</b>
 *       {@code @Transactional}. Es ist keine Transaktion gebunden; jede Datenbankanweisung
 *       laeuft fuer sich, und ein {@code @Transactional} an einer aufgerufenen Bean - etwa dem
 *       {@code AuditService} - erzeugt seine eigene kurze Transaktion.</li>
 *   <li><b>Der Listener</b> ({@link #absageVersenden}) traegt
 *       {@code @Transactional(NOT_SUPPORTED)}, und das ist <b>nicht</b> dasselbe wie gar keine
 *       Annotation. Ein {@code AFTER_COMMIT}-Callback laeuft <i>innerhalb</i> des Commits: Die
 *       Transaktion ist bereits festgeschrieben, ihre Synchronisation und ihre Verbindung sind
 *       aber noch gebunden. Ein {@code REQUIRED} traete deshalb der <b>abgeschlossenen</b>
 *       Transaktion bei, und die Schreibvorgaenge gingen verloren - ohne Fehlermeldung. Das ist
 *       die klassische Falle dieses Ereignistyps. {@code NOT_SUPPORTED} <b>setzt die
 *       bestehende Transaktion fuer die Dauer der Methode aus</b>; danach verhaelt sich alles
 *       wie im Auftrag, und jedes {@code @Transactional(REQUIRED)} darunter oeffnet eine
 *       frische Transaktion.</li>
 * </ul>
 *
 * <p><b>{@code REQUIRES_NEW} waere der naheliegende und hier schlechtere Griff:</b> Es loeste
 * dasselbe Problem, haette aber eine Transaktion ueber die HTTP-Aufrufe hinweg offen - genau
 * das, was vermieden werden soll. Und es widerspraeche der Projektregel, die
 * {@code REQUIRES_NEW} allein dem Versuchszaehler des Passwort-Resets zugesteht.
 *
 * <h2>Der Listener faengt jede Ausnahme selbst ab</h2>
 * Kein Stilpunkt, sondern eine Bedingung: <b>Eine Ausnahme aus einem
 * {@code AFTER_COMMIT}-Callback propagiert zum Aufrufer</b>, obwohl der Commit laengst durch
 * ist. Der Admin bekaeme einen {@code 500} fuer eine Absage, die gespeichert wurde - und
 * druecke ein zweites Mal. <b>Ein fehlgeschlagener Push darf die Antwort auf die Absage nicht
 * veraendern.</b>
 *
 * <h2>Genau eine Serverinstanz</h2>
 * Der bedingte {@code UPDATE} auf {@code push_erinnerung_am} schuetzt gegen Doppelversand; eine
 * zweite Instanz erzeugte vor allem Leerlauf. Sobald skaliert wird, gehoert eine Laufsperre
 * (etwa ShedLock) dazu.
 */
@Service
public class PushBenachrichtigungService {

    private static final Logger LOG = LoggerFactory.getLogger(PushBenachrichtigungService.class);

    /** Betroffene Entitaet im Audit-Log; beide Anlaesse haengen am Termin. */
    private static final String ENTITAET = "termin";

    /**
     * Bezeichnung des Handelnden im Audit-Log.
     *
     * <p>{@code akteur_bezeichnung} ist {@code NOT NULL}, und einen Nutzer gibt es hier nicht:
     * Der Auftrag laeuft von selbst, und der Versand nach einer Absage ist deren Folge - der
     * Admin steht mit Client-Adresse schon im Eintrag {@code TERMIN_ABGESAGT} daneben.
     */
    private static final String AKTEUR = "System (Push-Versand)";

    private final TerminRepository terminRepository;
    private final PushAboRepository pushAboRepository;
    private final PushVersandService pushVersandService;
    private final ConfigService configService;
    private final AuditService auditService;
    private final VapidSchluessel vapidSchluessel;

    /**
     * Ob der Erinnerungsauftrag ueberhaupt etwas tut
     * ({@code fubo.push.erinnerung-aktiv}).
     *
     * <p>Geprueft wird er in {@link #erinnerungsAuftrag}, nicht in
     * {@link #erinnerungVersenden} - so kommt der Testfall an ihm vorbei, der Takt nicht.
     *
     * <p><b>Als Feld und nicht als {@code @ConditionalOnProperty}:</b> Die Annotation wirkt auf
     * {@code @Bean}-Methoden und Klassen, <b>nicht</b> auf eine {@code @Scheduled}-Methode - sie
     * stuende dort wirkungslos und ohne Fehlermeldung, derselbe stille Ausfall wie ein
     * vergessenes {@code @EnableScheduling}. Eine bedingte Bean wiederum naehme dem
     * {@code PushService} seinen Zugriff und dem Test die Moeglichkeit, die Methode selbst
     * aufzurufen.
     *
     * <p><b>Der Takt laeuft trotzdem</b>, er kehrt nur sofort um. Das ist der gewollte
     * Unterschied: Die Testfaelle rufen {@link #erinnerungVersenden} ausdruecklich auf und
     * bekommen das echte Verhalten; nebenher passiert nichts.
     */
    private final boolean erinnerungAktiv;
    private final Clock uhr;

    public PushBenachrichtigungService(TerminRepository terminRepository,
                                       PushAboRepository pushAboRepository,
                                       PushVersandService pushVersandService,
                                       ConfigService configService,
                                       AuditService auditService,
                                       VapidSchluessel vapidSchluessel,
                                       FuboProperties eigenschaften,
                                       Clock uhr) {
        this.terminRepository = terminRepository;
        this.pushAboRepository = pushAboRepository;
        this.pushVersandService = pushVersandService;
        this.configService = configService;
        this.auditService = auditService;
        this.vapidSchluessel = vapidSchluessel;
        this.erinnerungAktiv = eigenschaften.push().erinnerungAktiv();
        this.uhr = uhr;
    }

    // ------------------------------------------------------------------ Anlass 1: Erinnerung

    /**
     * Erinnert an offene Rueckmeldungen zu Terminen, die im Vorlauffenster liegen (A25b,
     * Anlass 1).
     *
     * <h2>Warum fuenf Minuten</h2>
     * Das Intervall ist kein Selbstzweck: Es bestimmt, wie genau der konfigurierte Vorlauf
     * getroffen wird, und begrenzt nach einem Neustart den verpassten Zeitraum. <b>Derselbe
     * Takt wie der A18-Auftrag</b>, und wie dort haengt keine fachliche Regel an der
     * Puenktlichkeit - eine Erinnerung, die 24 Stunden und drei Minuten vorher kommt, ist
     * dieselbe Erinnerung.
     *
     * <h2>Der fruehe Ausstieg kommt zuerst, und er schweigt</h2>
     * Sind die VAPID-Schluessel nicht eingerichtet oder steht der Anlagenschalter aus, endet
     * der Lauf, <b>ohne die Datenbank zu fragen</b> - und ohne Logzeile: Sonst fuellte er das
     * Protokoll mit 288 leeren Zeilen am Tag. Dieselbe Ueberlegung wie beim A18-Auftrag, der
     * nur bei tatsaechlicher Wirkung protokolliert.
     *
     * <p><b>Der Anlagenschalter steht hier und nicht in der Empfaengerabfrage</b>: Er gilt fuer
     * den ganzen Lauf, nicht je Spieler. Die beiden anderen Bedingungen - Person und Geraet -
     * stehen in {@code PushAboRepository#empfaengerErinnerung}.
     *
     * <h2>Markieren vor dem Versenden</h2>
     * Der bedingte {@code UPDATE} laeuft <b>vor</b> dem Versand. Ein Absturz mitten im Versand
     * kostet dann einzelne Nachrichten; markierte man erst danach, bekaemen nach einem Neustart
     * <b>alle</b> Empfaenger die Nachricht ein zweites Mal. Dasselbe Muster und dieselbe
     * Begruendung wie bei {@code halle_abgesagt_am} aus S7.
     *
     * <p><b>Markiert wird auch dann, wenn es keine Empfaenger gibt</b> - das ist Absicht: Der
     * Vermerk beantwortet "wurde fuer diesen Termin erinnert", nicht "hat jemand etwas
     * bekommen". Ohne ihn liefe der Lauf alle fuenf Minuten erneut durch dieselbe
     * Empfaengerabfrage.
     *
     * <h2>Abschaltbar ueber {@code fubo.push.erinnerung-aktiv}</h2>
     * Notwendig fuer die Tests: <b>Alles, was den Kontextstart ueberlebt, ueberlebt auch die
     * Test-Transaktion.</b> Ein nebenher laufender Auftrag markierte Termine fremder Testfaelle
     * als erinnert - und der Fall "der zweite Lauf versendet nichts" pruefte dann das
     * Gegenteil dessen, was er soll.
     *
     * <p><b>Die Pruefung steht im Rumpf und nicht als Annotation an der Methode</b>;
     * Begruendung am Feld {@link #erinnerungAktiv}. Sie steht in {@link #erinnerungsAuftrag}
     * und nicht hier, damit die Testfaelle diese Methode aufrufen und das echte Verhalten
     * sehen - abgeschaltet ist nur der <i>Takt</i>.
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void erinnerungsAuftrag() {
        if (!erinnerungAktiv) {
            return;
        }
        erinnerungVersenden();
    }

    /**
     * Die Arbeit des Auftrags, ohne den Schalter.
     *
     * <h2>Warum sie vom Takt getrennt ist</h2>
     * <b>Damit der Testfall sie aufrufen kann.</b> Stuende die Pruefung auf
     * {@link #erinnerungAktiv} hier, kaeme kein Test an ihr vorbei - im Testprofil steht der
     * Schalter auf {@code false}, und zwar aus gutem Grund: Alles, was den Kontextstart
     * ueberlebt, ueberlebt auch die Test-Transaktion, und ein nebenher laufender Auftrag
     * markierte Termine fremder Testfaelle als erinnert.
     *
     * <p><b>Ihn fuer den Test einzuschalten waere die schlechtere Antwort:</b> Der Takt liefe
     * dann mit, und ob er waehrend eines Laufs feuert, haengt daran, ob der Testlauf gerade
     * eine Fuenfminutengrenze kreuzt. Das ist genau die Art Fehlschlag, die einmal in zehn
     * Laeufen auftritt und niemandem zuzuordnen ist.
     *
     * <p>So gibt es stattdessen zwei Methoden mit je einer Aufgabe: {@link #erinnerungsAuftrag}
     * entscheidet, <i>ob</i> gelaufen wird, diese hier, <i>was</i> geschieht.
     */
    public void erinnerungVersenden() {
        if (!vapidSchluessel.eingerichtet()) {
            return;
        }

        var konfiguration = configService.lesen();
        if (!konfiguration.isPushAktiv()) {
            return;
        }

        LocalDateTime jetzt = LocalDateTime.now(uhr);
        LocalDateTime grenze = jetzt.plusHours(konfiguration.getPushErinnerungStunden());

        List<Erinnerungstermin> faellige =
                terminRepository.faelligeFuerPushErinnerung(jetzt, grenze);

        for (Erinnerungstermin termin : faellige) {
            erinnere(termin);
        }
    }

    /**
     * Erinnert fuer genau einen Termin.
     *
     * <p>Je Termin ein eigener Durchgang samt eigenem Protokolleintrag: Liegen zwei Termine im
     * Vorlauffenster, gehen zwei Nachrichten hinaus (offener Punkt 5 der Anleitung) - sie
     * zusammenzufassen waere eine eigene Entscheidung, und in der Praxis faellt der Fall nicht
     * an, weil die Serie woechentliche Termine anlegt.
     */
    private void erinnere(Erinnerungstermin termin) {
        if (!terminRepository.pushErinnerungVermerken(termin.id(), OffsetDateTime.now(uhr))) {
            // Ein anderer Lauf war schneller. Bei einer Serverinstanz praktisch nur nach einem
            // Neustart erreichbar - und genau dafuer ist der Riegel da.
            return;
        }

        List<PushAbo> empfaenger = pushAboRepository.empfaengerErinnerung(termin.id());
        Versandbilanz bilanz = pushVersandService.versende(empfaenger,
                PushNutzlast.erinnerung(termin.id(), termin.datum(), termin.uhrzeit(),
                        termin.ort()));

        if (!bilanz.etwasVersandt()) {
            // Der haeufigste Fall und kein Fehler: kein Abonnement, Schalter aus, oder alle
            // haben schon geantwortet. Kein Protokolleintrag - er belegte nichts.
            return;
        }

        LOG.info("Erinnerung zu Termin {} ({} {}) versandt: {} Empfaenger, {} Geraete, "
                        + "{} angenommen.",
                termin.id(), termin.datum(), termin.uhrzeit(), bilanz.empfaenger(),
                bilanz.geraete(), bilanz.zugestellt());

        auditService.protokolliere(null, AKTEUR, AuditAktion.PUSH_ERINNERUNG_VERSANDT,
                ENTITAET, termin.id(), details(termin.datum().toString(),
                        termin.uhrzeit().toString(), bilanz));
    }

    // ------------------------------------------------------------------ Anlass 2: Absage

    /**
     * Benachrichtigt die Zusager, dass ein Termin abgesagt wurde (A25b, Anlass 2).
     *
     * <h2>{@code AFTER_COMMIT} und nicht der direkte Aufruf</h2>
     * <b>Bei einem Rollback geht nichts hinaus.</b> Eine Absage, die fachlich nie stattgefunden
     * hat, liesse sich nicht zurueckrollen - anders als eine Datenbankzeile. Das deckt genau
     * den S7-Fall ab: Scheitert der Mailversand an den Hallenbetreiber, rollt die Transaktion
     * zurueck, und dieser Listener feuert nicht.
     *
     * <h2>Er laeuft synchron im Anfrage-Thread des Admins</h2>
     * Ein {@code AFTER_COMMIT}-Callback ist per Voreinstellung synchron - <b>auch wenn
     * "Ereignis" nach nebenlaeufig klingt</b> - und laeuft innerhalb des Commits, also bevor
     * die Dienstmethode zum Controller zurueckkehrt. Die HTTP-Antwort geht erst hinaus, wenn er
     * fertig ist. Deshalb versendet {@link PushVersandService} nebenlaeufig unter einer
     * Gesamtfrist; seriell waeren dreissig Empfaenger im schlechtesten Fall zweieinhalb Minuten
     * Ladekreis.
     *
     * <h2>Jede Ausnahme wird hier abgefangen</h2>
     * Sie propagierte sonst zum Aufrufer, obwohl der Commit durch ist: Der Admin bekaeme einen
     * {@code 500} fuer eine gespeicherte Absage und druecke ein zweites Mal - beim zweiten Mal
     * ist der Termin dann schon abgesagt und er sieht {@code 409}. <b>Ein fehlgeschlagener Push
     * darf die Antwort auf die Absage nicht veraendern.</b>
     *
     * <p><b>Die drei Versandbedingungen gelten auch hier</b> - Anlage zuerst, dann Person, dann
     * Geraet. Ein Anlass, der eine davon uebergeht, waere der erste; A25f sagt ausdruecklich,
     * dass der Personenschalter auf <b>beide</b> Anlaesse wirkt.
     *
     * @param ereignis der vollzogene Statuswechsel samt Datum, Uhrzeit und Ort
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void absageVersenden(TerminAbgesagtEreignis ereignis) {
        try {
            if (!vapidSchluessel.eingerichtet() || !configService.lesen().isPushAktiv()) {
                return;
            }

            List<PushAbo> empfaenger = pushAboRepository.empfaengerAbsage(ereignis.terminId());
            Versandbilanz bilanz = pushVersandService.versende(empfaenger,
                    PushNutzlast.terminAbgesagt(ereignis.terminId(), ereignis.datum(),
                            ereignis.uhrzeit(), ereignis.ort()));

            if (!bilanz.etwasVersandt()) {
                return;
            }

            LOG.info("Absage zu Termin {} ({} {}) versandt: {} Empfaenger, {} Geraete, "
                            + "{} angenommen.",
                    ereignis.terminId(), ereignis.datum(), ereignis.uhrzeit(),
                    bilanz.empfaenger(), bilanz.geraete(), bilanz.zugestellt());

            auditService.protokolliere(null, AKTEUR, AuditAktion.PUSH_ABSAGE_VERSANDT,
                    ENTITAET, ereignis.terminId(), details(ereignis.datum().toString(),
                            ereignis.uhrzeit().toString(), bilanz));

        } catch (RuntimeException e) {
            // Bewusst alles: Der Commit ist durch, die Absage steht, und nichts davon darf die
            // Antwort an den Admin veraendern. Die Nachricht ist verloren - das ist der
            // hinnehmbare Ausgang, ein 500 fuer eine gespeicherte Absage waere es nicht.
            LOG.error("Push-Benachrichtigung zur Absage von Termin {} ist fehlgeschlagen. Die "
                            + "Absage selbst ist gespeichert; die Nachricht wird nicht "
                            + "wiederholt.", ereignis.terminId(), e);
        }
    }

    /**
     * Die Details des Protokolleintrags - fuer beide Anlaesse dieselben.
     *
     * <p><b>Datum und Uhrzeit gehoeren hinein</b>, obwohl sie am Termin stehen: Bei der
     * Hallenabsage aus S7 gilt dasselbe, und der Grund ist derselbe - der Termin kann
     * verschoben werden, der Eintrag soll sagen, worueber benachrichtigt wurde.
     *
     * <p><b>Empfaenger <i>und</i> Geraete</b>, weil beides gefragt wird und die Zahlen
     * auseinandergehen: "zwoelf Spieler erinnert" ist die fachliche Auskunft, "neunzehn Geraete
     * angesprochen" erklaert, warum der Lauf so lange gedauert hat.
     *
     * <p><b>Keine Namen und keine Adressen.</b> Die Empfaengerliste waere personenbezogen und
     * verdoppelte, was in {@code profil.push_abo} und {@code spieltag.teilnahme} ohnehin steht -
     * in eine Tabelle, die nach 30 Tagen geloescht wird und den Zustand damit nicht einmal
     * ueberlebt.
     */
    private static Map<String, Object> details(String datum, String uhrzeit,
                                               Versandbilanz bilanz) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("datum", datum);
        details.put("uhrzeit", uhrzeit);
        details.put("empfaenger", bilanz.empfaenger());
        details.put("geraete", bilanz.geraete());
        details.put("zugestellt", bilanz.zugestellt());
        return details;
    }
}
