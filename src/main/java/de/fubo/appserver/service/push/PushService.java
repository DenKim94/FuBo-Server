package de.fubo.appserver.service.push;

import de.fubo.appserver.common.config.VapidSchluessel;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.profil.Spieler;
import de.fubo.appserver.domain.push.PushAbo;
import de.fubo.appserver.domain.push.PushNutzlast;
import de.fubo.appserver.domain.push.Versandbilanz;
import de.fubo.appserver.dto.push.AboAnlegenRequest;
import de.fubo.appserver.dto.push.AboEntfernenRequest;
import de.fubo.appserver.dto.push.AboZustand;
import de.fubo.appserver.dto.push.Probeversand;
import de.fubo.appserver.dto.push.PushEinstellung;
import de.fubo.appserver.dto.push.PushStatus;
import de.fubo.appserver.dto.push.VapidSchluesselInfo;
import de.fubo.appserver.repository.profil.SpielerRepository;
import de.fubo.appserver.repository.push.PushAboRepository;
import de.fubo.appserver.service.config.ConfigService;
import de.fubo.appserver.utils.TokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.List;

/**
 * Abonnements, Personenschalter, Statusauskunft, Probeversand und Aufraeumen (A25c bis A25f;
 * S8 Abschnitte 7, 10).
 *
 * <h2>Was hier <b>nicht</b> liegt</h2>
 * Die beiden Versandanlaesse ({@code PushBenachrichtigungService}) und der Versandlauf selbst
 * ({@link PushVersandService}). <b>Der Schnitt ist die Transaktionsgrenze:</b> Die Methoden
 * hier schreiben in der Datenbank und tragen {@code @Transactional}, der Versandlauf ruft einen
 * fremden Dienst ueber HTTPS und darf deshalb <i>keine</i> offene Transaktion sehen. Beides in
 * einer Klasse waere die Einladung, ein {@code @Transactional} an die falsche Methode zu
 * schreiben - und der Schaden zeigte sich erst unter Last.
 *
 * <h2>Die Spieler-Id kommt ausnahmslos aus der Sitzung</h2>
 * Nie aus dem Anfragekoerper - sonst abonnierte jemand fuer einen anderen, und die Nachrichten
 * gingen auf dessen Geraet. Dieselbe Regel wie im Rueckmeldepfad aus S4.
 *
 * <h2>Der Personenschalter ist nicht ueberschreibbar</h2>
 * Es gibt bewusst <b>keinen</b> Admin-Endpunkt dafuer (A25f). Er wirkt auf <b>beide</b>
 * Versandanlaesse; eine Aufteilung nach Anlass gibt es nicht.
 *
 * <h2>Abschalten und Widerrufen sind zwei Handlungen</h2>
 * {@link #einstellungAendern} loescht <b>keine</b> Abonnements - sonst verlangte das
 * Wiedereinschalten einen neuen Browserdialog, und ein Nutzer, der nur voruebergehend Ruhe
 * wollte, muesste jedes Geraet neu zustimmen lassen. {@link #aboEntfernen} betrifft
 * ausschliesslich das aufrufende Geraet.
 */
@Service
public class PushService {

    private static final Logger LOG = LoggerFactory.getLogger(PushService.class);

    /**
     * Aufbewahrung erloschener Abonnements (Datenmodell 19).
     *
     * <p><b>Eine Konstante und kein Konfigurationsfeld</b> - wie die Aufbewahrung abgelaufener
     * Sitzungen im {@code SessionService} und aus demselben Grund: Es gibt keinen Anlass, sie
     * zu verstellen. Dreissig Tage sind lang genug, dass ein Spieler, der drei Wochen im
     * Urlaub war, seinen Eintrag beim naechsten Anwendungsstart noch geheilt bekommt - und kurz
     * genug, dass eine personenbezogene Adresse nicht unbegrenzt liegen bleibt.
     */
    private static final int AUFBEWAHRUNG_TAGE = 30;

    private final PushAboRepository pushAboRepository;
    private final SpielerRepository spielerRepository;
    private final ConfigService configService;
    private final PushVersandService pushVersandService;
    private final VapidSchluessel vapidSchluessel;
    private final Clock uhr;

    public PushService(PushAboRepository pushAboRepository,
                       SpielerRepository spielerRepository,
                       ConfigService configService,
                       PushVersandService pushVersandService,
                       VapidSchluessel vapidSchluessel,
                       Clock uhr) {
        this.pushAboRepository = pushAboRepository;
        this.spielerRepository = spielerRepository;
        this.configService = configService;
        this.pushVersandService = pushVersandService;
        this.vapidSchluessel = vapidSchluessel;
        this.uhr = uhr;
    }

    // ------------------------------------------------------------------ Schluessel

    /**
     * Liefert den oeffentlichen VAPID-Schluessel zum Abonnieren im Browser (A25b).
     *
     * @return der Schluessel als base64url
     * @throws FachlicherFehler {@code 503 PUSH_NICHT_KONFIGURIERT}, wenn auf diesem Server
     *                          keine brauchbaren VAPID-Angaben stehen. <b>Wiederholen hilft
     *                          nie</b> - die Oberflaeche blendet den Bereich aus
     */
    @Transactional(readOnly = true)
    public VapidSchluesselInfo schluessel() {
        pruefeEingerichtet();
        return new VapidSchluesselInfo(vapidSchluessel.oeffentlichBase64Url());
    }

    // ------------------------------------------------------------------ Abonnements

    /**
     * Legt das Abonnement eines Geraets an oder frischt es auf (A25c).
     *
     * <h2>Idempotent, und der Client ruft es bei jedem Anwendungsstart</h2>
     * Ueber den SHA-256-Hash der Endpoint-Adresse entsteht hoechstens eine Zeile je
     * Browserinstallation. <b>Ein zuvor deaktiviertes Abonnement wird dabei wieder aktiv, und
     * {@code fehlversuche} faellt auf null</b> - das heilt genau den Fall, in dem der Server
     * nach einem {@code 410} deaktiviert hat, der Browser das Abonnement aber noch fuehrt.
     *
     * <h2>Der Hash statt der Adresse als Schluessel</h2>
     * Die Laenge der Endpoint-Adresse ist in RFC 8030 nicht begrenzt, ein btree-Index fasst
     * rund 2 700 Byte. Ein Unique-Index direkt auf der Adresse koennte erst zur Laufzeit
     * fehlschlagen - bei einem Anbieter, den niemand getestet hat. Dasselbe Verfahren wie beim
     * Session-Token.
     *
     * <p><b>Ohne Pruefung auf eingerichtete VAPID-Schluessel</b>, anders als
     * {@link #schluessel()}: Ein Client, der schon ein Abonnement hat, soll es weiterhin melden
     * duerfen. Es ist dann nur wirkungslos - und der Betreiber, der die Schluessel nachtraegt,
     * muss nicht jeden Spieler bitten, erneut zuzustimmen.
     *
     * @param spielerId Eigentuemer aus der Sitzung
     * @param anfrage   Endpoint und die beiden Schluessel des Browsers
     * @param geraet    gekuerzte Geraetebezeichnung aus dem {@code User-Agent} oder
     *                  {@code null}
     * @return der erreichte Zustand: ein Abonnement besteht
     */
    @Transactional
    public AboZustand aboAnlegen(Long spielerId, AboAnlegenRequest anfrage, String geraet) {
        String endpoint = anfrage.bereinigterEndpoint();

        pushAboRepository.anlegenOderAuffrischen(spielerId, endpoint,
                TokenGenerator.hash(endpoint), anfrage.p256dh(), anfrage.auth(), geraet);

        // Ohne die Adresse: Sie ist personenbezogen und gehoert nicht ins Log - dieselbe
        // Zurueckhaltung wie beim MailService, der die Empfaengeradresse ebenfalls auslaesst.
        LOG.info("Push-Abonnement fuer Spieler {} angelegt oder aufgefrischt ({}).",
                spielerId, geraet == null ? "ohne Geraeteangabe" : geraet);

        return AboZustand.vorhanden();
    }

    /**
     * Widerruft das Abonnement des aufrufenden Geraets (A25c).
     *
     * <p><b>Nur das eigene:</b> Die Anweisung prueft die Spieler-Id mit. Ohne diese zweite
     * Bedingung entfernte ein Aufrufer mit einer fremden Endpoint-Adresse das Abonnement eines
     * anderen - der Endpunkt liegt ausserhalb von {@code /admin/} und steht jedem Angemeldeten
     * offen.
     *
     * <p><b>Ein unbekanntes Abonnement ist kein Fehler.</b> Loeschen ist idempotent; ein
     * {@code 404} zwaenge den Client zu einer Fallunterscheidung ohne Nutzen - und verriete
     * nebenbei, ob eine fremde Adresse existiert.
     *
     * @param spielerId Aufrufer aus der Sitzung
     * @param anfrage   Endpoint des zu widerrufenden Abonnements
     * @return der erreichte Zustand: fuer dieses Geraet besteht kein Abonnement
     */
    @Transactional
    public AboZustand aboEntfernen(Long spielerId, AboEntfernenRequest anfrage) {
        int entfernt = pushAboRepository.entfernen(
                TokenGenerator.hash(anfrage.bereinigterEndpoint()), spielerId);

        if (entfernt > 0) {
            LOG.info("Push-Abonnement eines Geraets von Spieler {} widerrufen.", spielerId);
        }
        return AboZustand.entfernt();
    }

    // ------------------------------------------------------------------ Personenschalter

    /**
     * Setzt den Personenschalter des Aufrufers (A25f).
     *
     * <h2>Ueber die Entity und nicht per SQL - die Lehre aus S6</h2>
     * {@code profil.spieler.push_erwuenscht} ist an der {@code Spieler}-Entity gemappt, und
     * <b>Hibernate schreibt beim Flush alle gemappten Spalten</b>. Wuerde der Schalter nativ
     * gesetzt, waehrend irgendwo eine {@code Spieler}-Entity geladen ist, schriebe deren Flush
     * den alten Wert still zurueck - dasselbe Bild wie bei den Bilanzzaehlern, nur ohne den
     * Testfall, der es damals aufdeckte. Ueber die Entity greift {@code @Version} von selbst.
     *
     * <p><b>Preis, den man kennen muss:</b> Schaltet ein Spieler um, waehrend der Admin sein
     * Profil geoeffnet hat, bekommt der Admin beim Speichern {@code 409 DATEN_VERALTET}. Das
     * ist das gewuenschte Verhalten und kein Fehler - die Zeile <i>hat</i> sich geaendert.
     *
     * <h2>Kein Verwerfen des Profil-Zwischenspeichers</h2>
     * {@code ProfilStammdatenCache} haelt {@code Profileintrag}, und darin steht der Schalter
     * nicht - er ist keine Stammdatenangabe, sondern eine persoenliche Einstellung, die nur ihr
     * Eigentuemer liest. <b>Wer ihn doch in ein bestehendes DTO aufnimmt, muss den Speicher
     * hier verwerfen</b>: Die Regel "jeder schreibende Vorgang verwirft" gilt ausnahmslos, und
     * es gibt keine Frist, die den Fehler von selbst heilte.
     *
     * <p><b>{@code geaendert_am} bleibt unberuehrt</b> - es ist die Spur einer
     * Stammdatenpflege durch den Admin; diese Einstellung ist keine. Dieselbe Entscheidung wie
     * bei den Bilanzzaehlern in S6.
     *
     * <p><b>Nicht protokolliert:</b> Es ist eine Nutzerhandlung, und ihr Zustand steht
     * vollstaendig in der Spalte. Adminaktionen ja, Nutzerhandlungen nein - dieselbe Regel wie
     * bei den Rueckmeldungen aus S4.
     *
     * @param spielerId      Aufrufer aus der Sitzung
     * @param pushErwuenscht gewuenschter Stand
     * @return der gespeicherte Stand
     * @throws FachlicherFehler {@code 404}, wenn es das Profil nicht mehr gibt - bei gueltiger
     *                          Sitzung nicht erreichbar, denn das Entfernen eines Profils
     *                          widerruft dessen Sitzungen
     */
    @Transactional
    public PushEinstellung einstellungAendern(Long spielerId, boolean pushErwuenscht) {
        Spieler spieler = spielerRepository.findById(spielerId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt kein Profil mit dieser Id."));

        spieler.setPushErwuenscht(pushErwuenscht);

        // saveAndFlush und nicht nur save: Der Sperrkonflikt soll hier auftreten und nicht
        // erst beim Commit, wo er ausserhalb dieser Transaktion laege.
        spielerRepository.saveAndFlush(spieler);

        return new PushEinstellung(pushErwuenscht);
    }

    /**
     * Liefert die beiden <b>serverseitigen</b> Ebenen der drei Versandbedingungen (A25e, A25f).
     *
     * <h2>Getrennt, und das ist der ganze Zweck</h2>
     * Faellt eine der drei Bedingungen weg, unterbleibt der Versand stillschweigend - kein
     * Fehlerfall, sondern der Normalzustand vieler Spieler. Eine Meldung "keine
     * Benachrichtigungen" ohne Angabe der Ebene schickt den Nutzer an die falsche Stelle: "vom
     * Admin abgeschaltet" und "von dir abgeschaltet" verlangen verschiedene Handlungen.
     *
     * <h2>Die Geraeteebene fehlt mit Absicht</h2>
     * Ein {@code GET} koennte das aufrufende Geraet gar nicht identifizieren, ohne die
     * Endpoint-Adresse in die URL zu schreiben. Der Client liest sie lokal ueber
     * {@code pushManager.getSubscription()}.
     *
     * <p><b>Der Zustand "VAPID nicht eingerichtet" erscheint hier nicht.</b> Er ist keine Ebene
     * der Entscheidung, sondern die Frage, ob die Funktion auf diesem Server ueberhaupt
     * existiert - und die beantwortet {@code /push/schluessel/lesen} mit {@code 503}.
     *
     * @param spielerId Aufrufer aus der Sitzung
     * @return Anlagen- und Personenebene
     * @throws FachlicherFehler {@code 404}, wenn es das Profil nicht mehr gibt
     */
    @Transactional(readOnly = true)
    public PushStatus status(Long spielerId) {
        Spieler spieler = spielerRepository.findById(spielerId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt kein Profil mit dieser Id."));

        return new PushStatus(configService.lesen().isPushAktiv(), spieler.isPushErwuenscht());
    }

    // ------------------------------------------------------------------ Probeversand

    /**
     * Versendet eine Testbenachrichtigung an die <b>eigenen</b> Geraete des Aufrufers (S8
     * Abschnitt 10.2).
     *
     * <h2>Er prueft die drei Versandbedingungen nicht</h2>
     * Entscheidung vom 14.09.2026 (Weggabelung D). Der Admin ist hier Absender und Empfaenger
     * in einer Person und hat den Versand ausdruecklich angefordert; ihn zu zwingen, erst den
     * Anlagenschalter einzuschalten, um zu pruefen, ob das Einschalten etwas bringt, drehte die
     * Reihenfolge um.
     *
     * <p><b>Der Preis gehoert in die Endpunktbeschreibung:</b> Ein erfolgreicher Probeversand
     * beweist <i>nicht</i>, dass Spieler etwas bekommen - er beweist, dass Schluessel,
     * Verschluesselung und der Weg zum Push-Dienst tragen. Die Oberflaeche zeigt deshalb
     * {@code anlageAktiv} aus {@link #status} daneben.
     *
     * <p><b>{@code empfaenger: 0} ist kein Fehler</b>, sondern die Auskunft "auf diesem Konto
     * ist kein Geraet angemeldet". Ein Fehlercode dafuer waere irrefuehrend; der Aufruf hat
     * getan, was er sollte.
     *
     * <h2>Ohne Transaktionsgrenze</h2>
     * Die Methode ruft einen fremden Dienst ueber HTTPS. <b>Ein {@code @Transactional} hier
     * hielte eine Datenbankverbindung ueber die Netzaufrufe hinweg belegt</b> - siehe
     * {@link PushVersandService}. Das Lesen der Abonnements und das Buchen der Ergebnisse
     * laufen als einzelne, kurze Anweisungen.
     *
     * @param spielerId Aufrufer aus der Sitzung; bei diesem Endpunkt immer der Admin
     * @return angesprochene und angenommene Geraete
     * @throws FachlicherFehler {@code 503 PUSH_NICHT_KONFIGURIERT}, wenn keine brauchbaren
     *                          VAPID-Angaben stehen - dann gibt es nichts zu pruefen
     */
    public Probeversand probeversand(Long spielerId) {
        pruefeEingerichtet();

        List<PushAbo> eigene = pushAboRepository.aktiveFuerSpieler(spielerId);
        Versandbilanz bilanz = pushVersandService.versende(eigene, PushNutzlast.probe());

        LOG.info("Probeversand fuer Spieler {}: {} Geraete angesprochen, {} angenommen.",
                spielerId, bilanz.geraete(), bilanz.zugestellt());

        return new Probeversand(bilanz.geraete(), bilanz.zugestellt());
    }

    // ------------------------------------------------------------------ Aufraeumen

    /**
     * Entfernt erloschene Abonnements jenseits der Aufbewahrungsfrist (Datenmodell 19; S8
     * Abschnitt 10.1).
     *
     * <h2>Aufgerufen vom Aufraeumlauf des {@code SessionService}</h2>
     * <b>Ueber diesen Dienst und nie ueber das Repository</b> - dieselbe Regel, die S6 fuer den
     * Zugriff des Ergebnisdienstes auf die Bilanz aufgestellt hat: Ein Vorgang, der einen
     * fremden Fachbereich beruehrt, ruft dessen <i>Dienst</i>. Sonst muesste der
     * {@code SessionService} wissen, welche Frist gilt und dass sie nur fuer erloschene
     * Abonnements gilt.
     *
     * <p><b>Kein eigener {@code @Scheduled}-Auftrag.</b> Ein zweiter naechtlicher Takt fuer
     * dieselbe Sache waere ein zweiter Ort, an dem jemand ihn verstellt - dieselbe Ueberlegung
     * wie beim A18-Auftrag, der Fixierung und Abschluss in einer Methode erledigt.
     *
     * <p><b>Ein Widerruf durch den Nutzer loescht dagegen sofort</b>: Die Frist gilt nur Zeilen,
     * die der <i>Server</i> deaktiviert hat, weil er sie eventuell noch heilen kann.
     *
     * @return Anzahl entfernter Abonnements
     */
    @Transactional
    public int erloscheneEntfernen() {
        OffsetDateTime grenze = OffsetDateTime.now(uhr).minusDays(AUFBEWAHRUNG_TAGE);
        int entfernt = pushAboRepository.erloscheneEntfernen(grenze);

        if (entfernt > 0) {
            LOG.info("Erloschene Push-Abonnements entfernt: {} (deaktiviert vor {} oder "
                    + "frueher).", entfernt, grenze);
        }
        return entfernt;
    }

    /**
     * Lehnt ab, wenn auf diesem Server keine brauchbaren VAPID-Angaben stehen.
     *
     * <p>Der Zustand entsteht <b>beim Start</b> und nicht bei jedem Aufruf: {@code PushConfig}
     * hat die drei Werte geprueft, das Paar gegen sich selbst gerechnet und das Ergebnis in
     * {@link VapidSchluessel#eingerichtet()} festgehalten.
     */
    private void pruefeEingerichtet() {
        if (!vapidSchluessel.eingerichtet()) {
            throw new FachlicherFehler(Fehlercode.PUSH_NICHT_KONFIGURIERT);
        }
    }
}
