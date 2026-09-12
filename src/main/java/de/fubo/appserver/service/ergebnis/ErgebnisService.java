package de.fubo.appserver.service.ergebnis;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.spieltag.Ergebnis;
import de.fubo.appserver.domain.spieltag.Sieger;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import de.fubo.appserver.domain.spieltag.Terminzustand;
import de.fubo.appserver.dto.spieltag.ErgebnisErfassenRequest;
import de.fubo.appserver.dto.spieltag.ErgebnisKorrigierenRequest;
import de.fubo.appserver.repository.spieltag.ErgebnisRepository;
import de.fubo.appserver.repository.spieltag.TeamGenerierungRepository;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.profil.BilanzService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ergebniserfassung, Korrektur und die daran haengende Bilanz (A9, A21; S6 Abschnitte 2 bis 5).
 *
 * <h2>Zwei Schreibpfade mit verschiedenem Zugang, aber demselben Nachgang</h2>
 * {@link #erfassen} liegt ausserhalb von {@code /admin/} - wer das Ergebnis eintraegt, ist der,
 * der mit dem Telefon auf dem Platz steht, und "der zuerst eingetragene Eintrag gilt" ergibt
 * nur einen Sinn, wenn mehrere es versuchen duerfen. {@link #korrigieren} liegt darunter, denn
 * A21 gibt die Korrektur ausdruecklich dem Admin.
 *
 * <p><b>Beide rechnen die Bilanz neu</b>, in derselben Transaktion wie die Aenderung an
 * {@code spieltag.ergebnis} (4.6), und beide protokollieren.
 *
 * <h2>Was dieser Dienst nicht tut</h2>
 * Er <b>loescht nichts</b> - A21 sieht nur die Korrektur vor. Ein Termin, der versehentlich
 * abgeschlossen wurde und ein Ergebnis traegt, ist damit nicht mehr zu bereinigen: Absagen ist
 * nur aus {@code GEPLANT} heraus moeglich, Entfernen scheitert an
 * {@code TERMIN_IN_VERWENDUNG}, und der Status ist nur vorwaerts setzbar. Bewusste Haerte; der
 * Ausweg ist heute die Datenbank.
 *
 * <p>Und er <b>ruehrt keine Skillwerte an</b> (A16): Die Bilanz ist Statistik, kein Skillwert.
 */
@Service
public class ErgebnisService {

    /**
     * Betroffene Entitaet im Audit-Log.
     *
     * <p>{@code termin} und nicht {@code ergebnis}: Das Ergebnis hat keine eigene Adresse, es
     * haengt am Termin - und wer das Protokoll zu einem Spieltag durchsieht, sucht nach dessen
     * Id, nicht nach der einer Zeile, die er nie zu Gesicht bekommt.
     */
    private static final String ENTITAET = "termin";

    private final ErgebnisRepository ergebnisRepository;
    private final TerminRepository terminRepository;
    private final TeamGenerierungRepository teamGenerierungRepository;
    private final BilanzService bilanzService;
    private final AuditService auditService;
    private final Clock uhr;

    public ErgebnisService(ErgebnisRepository ergebnisRepository,
                           TerminRepository terminRepository,
                           TeamGenerierungRepository teamGenerierungRepository,
                           BilanzService bilanzService,
                           AuditService auditService,
                           Clock uhr) {
        this.ergebnisRepository = ergebnisRepository;
        this.terminRepository = terminRepository;
        this.teamGenerierungRepository = teamGenerierungRepository;
        this.bilanzService = bilanzService;
        this.auditService = auditService;
        this.uhr = uhr;
    }

    // ------------------------------------------------------------------ Erfassen

    /**
     * Traegt das Ergebnis eines Termins ein (A21, S6 Abschnitt 2).
     *
     * <h2>Die Reihenfolge der Pruefungen ist nicht beliebig (2.2)</h2>
     * <ol>
     *   <li>Der Termin existiert - sonst {@code 404 INHALT_NICHT_GEFUNDEN}.</li>
     *   <li>Er ist {@code ABGESCHLOSSEN} - sonst {@code 409 TERMIN_NICHT_ABGESCHLOSSEN}.</li>
     *   <li>Es gibt eine unabgeloeste Einteilung - sonst {@code 409 KEINE_EINTEILUNG}.</li>
     *   <li>Der Eintrag gelingt - sonst {@code 409 ERGEBNIS_VORHANDEN}.</li>
     * </ol>
     * Ein geplanter Termin ohne Einteilung soll "noch nicht gespielt" melden und nicht "keine
     * Einteilung": Das ist der Grund, der zuerst greift, und der andere verschwindet von
     * selbst, sobald jemand generiert hat.
     *
     * <p><b>Die Identitaet kommt aus der Sitzung, nie aus dem Anfragekoerper</b> - sonst waere
     * {@code erfasstVon} als Auskunft wertlos. Auch ein {@code GAST} darf eintragen; er spielt
     * mit, und ihn auszuschliessen waere eine Regel ohne Grund.
     *
     * <p><b>Die Antwort traegt einen Koerper</b> ({@code 201}, nicht {@code 204}): Ohne die
     * {@code version} muesste der Client den Termin sofort danach noch einmal lesen, nur um
     * korrigieren zu koennen.
     *
     * @param anfrage  Termin, Sieger und {@code deutlich}
     * @param sitzung  aufrufende Sitzung; liefert den Erfasser
     * @param clientIp Adresse des Aufrufers, fuer das Protokoll
     * @return das soeben geschriebene Ergebnis, aus der Datenbank zurueckgelesen
     * @throws FachlicherFehler {@code 404 INHALT_NICHT_GEFUNDEN};
     *                          {@code 409 TERMIN_NICHT_ABGESCHLOSSEN};
     *                          {@code 409 KEINE_EINTEILUNG}; {@code 409 ERGEBNIS_VORHANDEN}
     */
    @Transactional
    public Ergebnis erfassen(ErgebnisErfassenRequest anfrage, AktiveSitzung sitzung,
                             String clientIp) {

        Long terminId = anfrage.terminId();

        Terminzustand zustand = terminRepository.zustand(terminId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        if (zustand.status() != TerminStatus.ABGESCHLOSSEN) {
            throw new FachlicherFehler(Fehlercode.TERMIN_NICHT_ABGESCHLOSSEN,
                    "Der Termin hat den Status " + zustand.status()
                            + "; ein Ergebnis lässt sich erst nach dem Abschluss erfassen.");
        }
        if (!teamGenerierungRepository.existiertAktuelle(terminId)) {
            throw new FachlicherFehler(Fehlercode.KEINE_EINTEILUNG);
        }

        // Eine leere Ergebnismenge heisst "liegt schon vor" - die Entscheidung faellt in der
        // Datenbank, nicht hier. Zwei Spieler, die nach dem Abpfiff gleichzeitig tippen, sind
        // der Normalfall und kein Ausnahmezustand.
        ergebnisRepository.einfuegen(terminId, anfrage.sieger().kennung(), anfrage.deutlich(),
                        sitzung.spielerId(), sitzung.gastName())
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.ERGEBNIS_VORHANDEN));

        int betroffen = bilanzService.neuBerechnen(terminId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sieger", anfrage.sieger().name());
        details.put("deutlich", anfrage.deutlich());
        details.put("betroffeneSpieler", betroffen);

        // Handelnder ist der Aufrufer, nicht der Admin: Der Endpunkt liegt ausserhalb
        // von /admin/.
        auditService.protokolliere(sitzung.spielerId(), clientIp, AuditAktion.ERGEBNIS_ERFASST,
                ENTITAET, terminId, details);

        return ergebnisRepository.findByTerminId(terminId)
                .orElseThrow(() -> new IllegalStateException(
                        "Das soeben eingetragene Ergebnis ist nicht wieder auffindbar."));
    }

    // ------------------------------------------------------------------ Korrigieren

    /**
     * Korrigiert ein bestehendes Ergebnis (A21, S6 Abschnitt 3).
     *
     * <h2>Die {@code version} wird an zwei Stellen geprueft</h2>
     * Hier im Dienst fuer die verstaendliche Meldung ({@code 409 DATEN_VERALTET}) und ueber den
     * bestehenden Handler fuer {@code ObjectOptimisticLockingFailureException} fuer das Fenster
     * zwischen Vergleich und Schreibvorgang. <b>Keiner von beiden genuegt allein</b> - dieselbe
     * Aufteilung wie bei der Konfiguration und beim Termin.
     *
     * <h2>{@code saveAndFlush} und nicht {@code save}</h2>
     * Zwei Gruende, und beide zaehlen. Erstens liest die Bilanzrechnung
     * {@code spieltag.ergebnis} <b>per SQL</b> und sieht nur, was in der Datenbank steht; ohne
     * Flush rechnete sie gegen den alten Ausgang, und der Fehler faellt nicht auf, weil die
     * Zahlen plausibel bleiben. Zweitens faellt der Sperrkonflikt damit <i>hier</i> an und
     * nicht erst beim Commit - dort waere er eine
     * {@code UnexpectedRollbackException} ohne erkennbare Ursache.
     *
     * <p><b>Eine Korrektur, die nichts aendert, wird durchgelassen</b> (3.3). Der
     * Protokolleintrag entsteht trotzdem und nennt alten <i>und</i> neuen Wert; dann ist auch
     * dieser Fall lesbar. {@code korrigiert_am} wandert dabei ebenfalls.
     *
     * @param anfrage  Termin, neuer Sieger, {@code deutlich} und die gelesene {@code version}
     * @param sitzung  aufrufende Adminsitzung
     * @param clientIp Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 404 INHALT_NICHT_GEFUNDEN}; {@code 409 DATEN_VERALTET}
     */
    @Transactional
    public void korrigieren(ErgebnisKorrigierenRequest anfrage, AktiveSitzung sitzung,
                            String clientIp) {

        Long terminId = anfrage.terminId();

        Ergebnis ergebnis = ergebnisRepository.findByTerminId(terminId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Für diesen Termin liegt kein Ergebnis vor."));

        if (!ergebnis.getVersion().equals(anfrage.version())) {
            throw new FachlicherFehler(Fehlercode.DATEN_VERALTET);
        }

        // Vor dem Schreiben vergleichen - danach steht der alte Wert nirgends mehr.
        Sieger alterSieger = ergebnis.getSieger();
        boolean altDeutlich = ergebnis.isDeutlich();

        ergebnis.setSieger(anfrage.sieger());
        ergebnis.setDeutlich(anfrage.deutlich());
        ergebnis.setKorrigiertAm(OffsetDateTime.now(uhr));
        ergebnisRepository.saveAndFlush(ergebnis);

        int betroffen = bilanzService.neuBerechnen(terminId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("siegerAlt", alterSieger.name());
        details.put("siegerNeu", anfrage.sieger().name());
        details.put("deutlichAlt", altDeutlich);
        details.put("deutlichNeu", anfrage.deutlich());
        details.put("betroffeneSpieler", betroffen);

        auditService.protokolliere(sitzung.spielerId(), clientIp,
                AuditAktion.ERGEBNIS_KORRIGIERT, ENTITAET, terminId, details);
    }

    // ------------------------------------------------------------------ Lesen

    /**
     * Liefert das Ergebnis eines Termins (A9, S6 Abschnitt 5.1).
     *
     * <h2>Ueber die Entity und nicht nativ</h2>
     * Hier gibt es keinen Zaehler, der zwischendurch nativ hochgezaehlt wuerde - also auch
     * nicht den Grund, aus dem der Generierungslauf und der Rueckmeldepfad den Termin nativ
     * lesen. Eine geladene {@link Ergebnis}-Entity kann niemandem im Weg stehen: Geschrieben
     * wird sie nur von {@link #korrigieren}, und das ist ein eigener Aufruf mit eigener
     * Transaktion.
     *
     * <p><b>{@link Optional#empty()} ist der Normalzustand</b> und kein Fehler: Solange
     * niemand erfasst hat, gibt es keines. Die Einzelansicht des Termins traegt dafuer ein
     * leeres Feld {@code ergebnis} - {@code null} ist die Antwort auf "gibt es schon ein
     * Ergebnis", ohne die Wahl zwischen {@code 404} (klingt nach Fehler) und {@code 204}
     * (klingt nach "nichts zu holen").
     *
     * <p><b>Ein unbekannter Termin ist hier ebenfalls {@link Optional#empty()}</b> und keine
     * Ausnahme. Das ist kein Versehen: Der Aufrufer ist die Einzelansicht, und die hat den
     * Termin bereits geladen - sie meldet {@code 404} an ihrer eigenen Stelle. Eine zweite
     * Existenzpruefung hier waere eine zweite Abfrage fuer eine Antwort, die schon feststeht.
     *
     * @param terminId gesuchter Termin
     * @return das Ergebnis oder {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<Ergebnis> lesen(Long terminId) {
        return ergebnisRepository.findByTerminId(terminId);
    }
}
