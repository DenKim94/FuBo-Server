package de.fubo.appserver.service.spieltag;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.domain.spieltag.Hallentermin;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.config.ConfigService;
import de.fubo.appserver.service.mail.MailService;
import de.fubo.appserver.utils.Absagevorlage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Der Hallenmodus: den gebuchten Hallentermin beim Betreiber absagen (A23, S7).
 *
 * <h2>Die eine Eigenschaft, die diesen Vorgang von jedem anderen unterscheidet</h2>
 * <b>Der Versand ist nicht zurueckrollbar.</b> Jede andere Schreiboperation dieses Servers
 * laesst sich rueckgaengig machen oder wenigstens korrigieren; eine Mail beim Hallenbetreiber
 * nicht - und niemand im Projekt erfaehrt davon, wenn sie falsch war. Daraus folgen drei Dinge,
 * die den Aufbau dieser Klasse bestimmen:
 *
 * <ol>
 *   <li><b>Jede Pruefung laeuft vor dem Versand</b>, ausnahmslos. Es gibt keinen Fall, in dem
 *       erst versendet und danach abgelehnt wird.</li>
 *   <li><b>Der Doppelversand ist der teuerste Fehler</b> - teurer als ein ausgebliebener. Ein
 *       Betreiber, der zwei Absagen fuer denselben Termin bekommt, ruft an und fragt, was denn
 *       nun gilt. Entschieden wird er deshalb in der Datenbank
 *       ({@code TerminRepository#halleAbsageVermerken}) und nicht hier.</li>
 *   <li><b>Wo Versand und Datenbankzustand auseinanderfallen koennen, wird die Richtung
 *       gewaehlt, in der hoechstens eine Mail zu viel ausbleibt</b> - nie eine zu viel ankommt.
 *       Siehe {@link #absagen}.</li>
 * </ol>
 *
 * <h2>Warum keine {@code Termin}-Entity im Spiel ist</h2>
 * Der Vorgang erhoeht {@code termin.version} nativ, im gekoppelten Fall sogar zweimal. Eine im
 * selben Vorgang geladene Entity traege danach eine veraltete Version im Speicher, und der
 * naechste Flush scheiterte an einem Sperrkonflikt, den niemand verursacht hat. Deshalb liest
 * der Pfad ueber {@link Hallentermin} und sagt den Termin ueber
 * {@code TerminRepository#absagenWennGeplant} ab statt ueber {@code TerminService#absagen} -
 * dieselbe Regel wie im Rueckmeldepfad aus S4 und im Generierungslauf aus S5.
 *
 * <h2>Warum ein eigener Dienst und nicht eine Methode im {@link TerminService}</h2>
 * Er buendelt drei Dinge, die der {@code TerminService} nicht kennt: die Hallenkonfiguration,
 * den Mailversand und die Frist. Und er ist der einzige Vorgang des Servers, der das System
 * verlaesst - das soll beim Lesen der Paketstruktur auffallen und nicht zwischen
 * Terminverwaltung stehen.
 */
@Service
public class HallenService {

    /** Betroffene Entitaet im Audit-Log; die Absage haengt am Termin und hat keine eigene Id. */
    private static final String ENTITAET = "termin";

    /**
     * Zeitformat fuer die Fehlermeldungen dieses Dienstes.
     *
     * <p><b>Ohne Wochentag</b>, anders als im Text der Nachricht: Dort hilft er einem Menschen,
     * einen Terminirrtum zu bemerken; hier steht eine Fristgrenze, und die liest der Admin im
     * eigenen Bildschirm neben Datum und Uhrzeit des Termins.
     */
    private static final DateTimeFormatter ZEITPUNKT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TerminRepository terminRepository;
    private final ConfigService configService;
    private final MailService mailService;
    private final AuditService auditService;
    private final Clock uhr;

    public HallenService(TerminRepository terminRepository,
                         ConfigService configService,
                         MailService mailService,
                         AuditService auditService,
                         Clock uhr) {
        this.terminRepository = terminRepository;
        this.configService = configService;
        this.mailService = mailService;
        this.auditService = auditService;
        this.uhr = uhr;
    }

    /**
     * Sagt den gebuchten Hallentermin beim Betreiber ab (A23).
     *
     * <h2>Die Reihenfolge der Pruefungen ist nicht beliebig</h2>
     * <ol>
     *   <li><b>Der Hallenmodus ist eingeschaltet</b> - sonst {@code 409 HALLE_MODUS_INAKTIV}.
     *       Steht als Erste, noch vor der Terminsuche: Ist die Funktion aus, spielt der
     *       einzelne Termin keine Rolle. <b>Folge:</b> Bei ausgeschaltetem Modus liefert auch
     *       eine unbekannte Id diesen Code und nicht {@code 404}.</li>
     *   <li>Der Termin existiert - sonst {@code 404}.</li>
     *   <li>Er ist nicht {@code ABGESCHLOSSEN} - sonst {@code 409 TERMIN_GESCHLOSSEN}.</li>
     *   <li>Das Zeitfenster ist offen - sonst {@code 409 HALLE_FRIST_ABGELAUFEN}.</li>
     *   <li>Eine Empfaengeradresse ist hinterlegt - sonst {@code 409 HALLE_NICHT_KONFIGURIERT}.</li>
     *   <li>Der bedingte {@code UPDATE} greift - sonst {@code 409 HALLE_BEREITS_ABGESAGT}.</li>
     *   <li><b>Erst jetzt der Versand.</b></li>
     * </ol>
     *
     * <p><b>Der Hauptschalter ist unabhaengig von der Empfaengeradresse</b> (Entscheidung vom
     * 13.09.2026): Ein aktiver Modus ohne {@code halle_email} ist erlaubt und laeuft in
     * {@code 409 HALLE_NICHT_KONFIGURIERT}. Die Kopplung im Speicherformular haette den Admin
     * daran gehindert, den Modus einzuschalten und die Adresse danach zu pflegen.
     *
     * <p><b>Zur Reihenfolge von 3 und 4:</b> Die Frist ist die Eigenschaft des Termins, die
     * Adresse die der Anwendung. Ein Admin, der beides falsch hat, soll zuerst erfahren, was er
     * nicht mehr aendern kann - umgekehrt schickte man ihn in die Konfiguration, nur damit er
     * danach erfaehrt, dass es ohnehin zu spaet ist.
     *
     * <h2>Ein geplanter Termin wird mit abgesagt</h2>
     * Entscheidung des Haupt-Entwicklers vom 13.09.2026. <b>Die Nachricht behauptet etwas:</b>
     * In ihr steht, dass der Termin nicht stattfindet. Ginge sie fuer einen geplanten Termin
     * hinaus, waere die Halle storniert, waehrend alle Beteiligten weiter eine Zusage sehen -
     * und es fiele erst auf, wenn jemand vor verschlossener Tuer steht.
     *
     * <p><b>Ein bereits abgesagter Termin wird nur noch gemeldet</b>, und das ist kein Sonderfall,
     * sondern der Weg fuer die Absage nach Fristende: {@code /admin/termin/absagen} kennt keine
     * Frist, ein Termin bleibt nach A19 jederzeit absagbar - gerade kurz vorher ist es am
     * wichtigsten. Beide Aufrufe zu koppeln haette diese Moeglichkeit genommen; sie nur zu
     * koppeln, wenn es passt, nimmt sie nicht.
     *
     * <p><b>Ein {@code ABGESCHLOSSEN}-Termin wird abgelehnt</b> - er hat stattgefunden, eine
     * Absage waere unwahr. {@code TERMIN_GESCHLOSSEN} passt dafuer genau: Der Code bedeutet
     * "nimmt keine Aenderung mehr an", und das trifft hier zu. Ein eigener Code waere eine
     * zweite Bezeichnung fuer denselben Zustand.
     *
     * <h2>Versand und Transaktion - die eine Stelle, an der man sich entscheiden muss</h2>
     * Der Vermerk steht <b>vor</b> dem Versand, beides in <b>einer</b> Transaktion. Damit gibt
     * es genau zwei Ausgaenge, und beide sind bedacht:
     * <ul>
     *   <li><b>Der Versand scheitert</b> - {@code 503 VERSAND_FEHLGESCHLAGEN}, die Transaktion
     *       rollt zurueck, Vermerk und mitlaufende Terminabsage verschwinden. Der Admin kann es
     *       unveraendert wiederholen. <b>Das ist der gewuenschte Ausgang</b> und derselbe Griff
     *       wie beim Passwort-Reset aus S2b.</li>
     *   <li><b>Der Versand gelingt, der Commit scheitert danach</b> - die Mail ist draussen, der
     *       Vermerk nicht. <b>Unschoen, aber unvermeidbar:</b> Ein Mailversand laesst sich nicht
     *       in eine Datenbanktransaktion aufnehmen. Das Fenster ist wenige Millisekunden
     *       breit.</li>
     * </ul>
     * <b>Die umgekehrte Reihenfolge waere schlechter, nicht besser:</b> Dort fuehrte jeder
     * Fehler nach dem Versand zum selben Ergebnis, und zusaetzlich gaebe es keine Sperre gegen
     * zwei gleichzeitige Klicks.
     *
     * @param terminId       Termin, dessen Halle abgesagt wird
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 409 HALLE_MODUS_INAKTIV} bei abgeschaltetem Hallenmodus;
     *                          {@code 404}, wenn es die Id nicht gibt;
     *                          {@code 409 TERMIN_GESCHLOSSEN} bei einem abgeschlossenen Termin;
     *                          {@code 409 HALLE_FRIST_ABGELAUFEN}, wenn der Vorlauf
     *                          unterschritten ist; {@code 409 HALLE_NICHT_KONFIGURIERT} ohne
     *                          Empfaengeradresse; {@code 409 HALLE_BEREITS_ABGESAGT} beim
     *                          zweiten Aufruf; {@code 503 VERSAND_FEHLGESCHLAGEN}, wenn der
     *                          Mailserver die Nachricht nicht annimmt
     */
    @Transactional
    public void absagen(Long terminId, Long adminSpielerId, String clientIp) {

        // Eine Uhr fuer beides: Der Vergleich mit datum + uhrzeit laeuft in Ortszeit, der
        // Vermerk geht als TIMESTAMPTZ in die Spalte. Zwei Aufrufe von now() koennten
        // auseinanderliegen, und der Vermerk truege dann eine andere Zeit als die, gegen die
        // geprueft wurde.
        OffsetDateTime jetzt = OffsetDateTime.now(uhr);

        AppConfig konfiguration = configService.lesen();

        // Der Hauptschalter steht vor allem anderen - auch vor der Suche nach dem Termin. Ist
        // die Funktion aus, spielt es keine Rolle, welchen Termin der Aufruf meint, und eine
        // Abfrage fuer einen Vorgang, der ohnehin abgelehnt wird, waere verschenkt.
        if (!konfiguration.isHallenModusAktiv()) {
            throw new FachlicherFehler(Fehlercode.HALLE_MODUS_INAKTIV,
                    "Der Hallenmodus ist abgeschaltet; es wird keine Absage an einen "
                            + "Hallenbetreiber versendet.");
        }

        Hallentermin termin = terminRepository.hallenzustand(terminId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        if (termin.status() == TerminStatus.ABGESCHLOSSEN) {
            throw new FachlicherFehler(Fehlercode.TERMIN_GESCHLOSSEN,
                    "Der Termin ist bereits abgeschlossen; eine Absage an den Hallenbetreiber "
                            + "würde behaupten, dass er nicht stattgefunden hat.");
        }

        pruefeFrist(termin, konfiguration.getHalleVorlaufStunden(), jetzt);

        String empfaenger = konfiguration.getHalleEmail();
        if (empfaenger == null || empfaenger.isBlank()) {
            throw new FachlicherFehler(Fehlercode.HALLE_NICHT_KONFIGURIERT);
        }

        if (!terminRepository.halleAbsageVermerken(terminId, jetzt)) {
            throw new FachlicherFehler(Fehlercode.HALLE_BEREITS_ABGESAGT, bereitsMeldung(termin));
        }

        // Der Rueckgabewert sagt, ob DIESER Aufruf den Termin abgesagt hat. Ein bereits
        // abgesagter Termin bekommt keinen zweiten Protokolleintrag - das Protokoll belegt
        // vollzogene Aenderungen, und hier hat sich nichts geaendert.
        boolean terminMitAbgesagt = terminRepository.absagenWennGeplant(terminId);

        String vorlage = Absagevorlage.wirksam(konfiguration.getHalleAbsageVorlage());
        mailService.sendeHallenabsage(empfaenger, termin.datum(), termin.uhrzeit(), termin.ort(),
                vorlage);

        if (terminMitAbgesagt) {
            // Eine Absage bleibt eine Absage, auch wenn sie ueber diesen Weg kommt: Wer das
            // Protokoll nach abgesagten Terminen durchsieht, soll sie dort finden. Das Detail
            // "weg" unterscheidet sie von der Absage ueber /admin/termin/absagen - dieselbe
            // Aufteilung, die PASSWORT_GEAENDERT zwischen "reset" und "aendern" macht.
            Map<String, Object> abgesagt = new LinkedHashMap<>();
            abgesagt.put("datum", termin.datum().toString());
            abgesagt.put("uhrzeit", termin.uhrzeit().toString());
            abgesagt.put("weg", "hallenabsage");
            auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.TERMIN_ABGESAGT,
                    ENTITAET, terminId, abgesagt);
        }

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.HALLE_ABGESAGT,
                ENTITAET, terminId, details(termin, empfaenger, vorlage, terminMitAbgesagt));
    }

    /**
     * Lehnt die Absage ab, wenn der Vorlauf unterschritten ist (A23).
     *
     * <h2>Drei Dinge daran sind wichtig</h2>
     * <b>Gerechnet wird in Ortszeit</b> ueber die {@code Clock}-Bean. {@code termin.datum} und
     * {@code .uhrzeit} sind {@code DATE} und {@code TIME} <b>ohne</b> Zone; mit UTC waere die
     * Antwort im Sommer zwei Stunden falsch, und der Fehler betraefe nur einen schmalen
     * Zeitstreifen am Tag - er fiele weder im Test noch im Betrieb verlaesslich auf. Dieselbe
     * Falle wie bei der Vergangenheitspruefung aus S4.
     *
     * <p><b>Der Wert kommt aus der Konfiguration, nie als Konstante.</b> 48 ist der Vorgabewert,
     * nicht die Regel. {@code detail} nennt den tatsaechlich geltenden Vorlauf und den
     * spaetesten Zeitpunkt - "zu spaet" ohne Zahlen zwingt den Admin, die Konfiguration
     * nachzuschlagen.
     *
     * <p><b>Ein bereits vergangener Termin faellt automatisch darunter.</b> Es braucht keine
     * zweite Pruefung auf die Vergangenheit; die Differenz ist dann negativ. Das gilt auch bei
     * einem Vorlauf von {@code 0}, der "bis zum Anpfiff" bedeutet - die Pruefung bleibt stehen
     * und schliesst den vergangenen Termin aus.
     *
     * @param termin  gelesener Zustand mit Datum und Uhrzeit
     * @param vorlauf {@code halle_vorlauf_stunden} aus der Konfiguration; {@code 0} ist gueltig
     * @param jetzt   Zeitpunkt aus der {@code Clock}-Bean
     */
    private void pruefeFrist(Hallentermin termin, short vorlauf, OffsetDateTime jetzt) {
        LocalDateTime spaetestens =
                LocalDateTime.of(termin.datum(), termin.uhrzeit()).minusHours(vorlauf);

        if (jetzt.toLocalDateTime().isAfter(spaetestens)) {
            throw new FachlicherFehler(Fehlercode.HALLE_FRIST_ABGELAUFEN,
                    ("Eine Absage ist bis %d Stunden vor Beginn möglich, also bis zum %s. "
                            + "Dieser Zeitpunkt ist vorbei.")
                            .formatted(vorlauf, spaetestens.format(ZEITPUNKT)));
        }
    }

    /**
     * Baut die Meldung fuer den zweiten Aufruf.
     *
     * <p><b>Der Nullzweig ist erreichbar</b>, auch wenn er nach dem Lesen unmoeglich aussieht:
     * Genau dann, wenn zwei Klicks gleichzeitig ankommen, war der Vermerk beim Lesen noch leer
     * und beim Schreiben schon gesetzt. Das ist der Fall, fuer den der bedingte {@code UPDATE}
     * ueberhaupt gebaut ist - er darf nicht in einer Meldung "seit null" enden.
     */
    private static String bereitsMeldung(Hallentermin termin) {
        if (termin.halleAbgesagtAm() == null) {
            return Fehlercode.HALLE_BEREITS_ABGESAGT.getStandardMeldung();
        }
        return ("Die Absage ist am %s an den Hallenbetreiber gegangen und lässt sich nicht "
                + "zurücknehmen.").formatted(termin.halleAbgesagtAm().format(ZEITPUNKT));
    }

    /**
     * Die Details des Protokolleintrags.
     *
     * <p><b>Die Empfaengeradresse gehoert hinein</b>, obwohl sie in der Konfiguration steht: Sie
     * ist veraenderlich, und "an wen ist die Absage damals gegangen" ist genau die Frage, die
     * man spaeter stellt.
     *
     * <p><b>Der Vorlagentext gehoert nicht hinein</b> - dieselbe Ausnahme, die
     * {@code KONFIG_GEAENDERT} fuer die Vorlage macht: Ein mehrzeiliger Text in jedem Eintrag
     * blaeht die Tabelle auf, ohne etwas zu belegen, was nicht auch die Konfiguration belegt.
     * Die Zeichenzahl genuegt, um eine gepflegte von der Ersatzvorlage zu unterscheiden, und
     * {@code ersatzvorlage} sagt es ausdruecklich.
     */
    private static Map<String, Object> details(Hallentermin termin, String empfaenger,
                                               String vorlage, boolean terminMitAbgesagt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("empfaenger", empfaenger);
        details.put("datum", termin.datum().toString());
        details.put("uhrzeit", termin.uhrzeit().toString());
        details.put("vorlageZeichen", vorlage.length());
        details.put("ersatzvorlage", vorlage.equals(Absagevorlage.ERSATZ));
        details.put("terminMitAbgesagt", terminMitAbgesagt);
        return details;
    }
}
