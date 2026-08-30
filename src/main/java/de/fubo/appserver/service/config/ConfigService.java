package de.fubo.appserver.service.config;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.dto.admin.KonfigurationAendernRequest;
import de.fubo.appserver.repository.auth.GastSlotRepository;
import de.fubo.appserver.repository.config.AppConfigRepository;
import de.fubo.appserver.service.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Liest und aendert die einzeilige Admin-Konfiguration aus {@code configs.app_config}
 * (A10, A11, A14, A15, A17, A23).
 *
 * <h2>Warum die Aenderung das DTO entgegennimmt und keine Einzelwerte</h2>
 * Sonst ist es in diesem Projekt umgekehrt: {@code SpielerVerwaltungService#bearbeiten} bekommt
 * seine Werte einzeln, damit der Dienst den Vertrag nicht kennen muss. Hier waeren das elf
 * Argumente, davon sieben vom Typ {@code short} - eine Liste, in der zwei vertauschte Werte
 * fehlerfrei kompilieren und stillschweigend das Falsche schreiben. Genau diese Verwechslung ist
 * der Grund, aus dem es {@code ConfigServiceTests} ueberhaupt gibt (JPA-Mapping-Regel 2). Ein
 * Record mit benannten Komponenten schliesst sie aus; das ist den Import des DTOs wert.
 */
@Service
public class ConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigService.class);

    /** Die Tabelle ist per CHECK-Constraint auf genau diese Zeile beschraenkt. */
    private static final short KONFIG_ID = 1;

    /**
     * Fester Schluessel des Admin-Kontos fuer die Spalte {@code geaendert_von}.
     *
     * <p>{@code ck_admin_konto_singleton} laesst nur {@code id = 1} zu - es gibt genau ein Konto,
     * und {@code fk_app_config_admin} verweist darauf. Ein Nachschlagen ueber die Profil-Id des
     * Aufrufers brauchte einen zusaetzlichen Lesezugriff und koennte kein anderes Ergebnis
     * liefern. Dieselbe Konstante fuehrt {@code AdminService}; sie liegt dort im Paket
     * {@code service.auth} und ist von hier nicht sichtbar.
     */
    private static final short ADMIN_KONTO_ID = 1;

    /** Betroffene Entitaet im Audit-Log. */
    private static final String ENTITAET = "app_config";

    private final AppConfigRepository appConfigRepository;
    private final GastSlotRepository gastSlotRepository;
    private final AuditService auditService;

    public ConfigService(AppConfigRepository appConfigRepository,
                         GastSlotRepository gastSlotRepository,
                         AuditService auditService) {
        this.appConfigRepository = appConfigRepository;
        this.gastSlotRepository = gastSlotRepository;
        this.auditService = auditService;
    }

    /**
     * Liefert die aktuelle Konfiguration.
     *
     * <p>Bewusst ohne Zwischenspeicher: Der Aufruf ist ein Primaerschluessel-Zugriff auf
     * eine einzeilige Tabelle, die dauerhaft im Puffer der Datenbank liegt. Ein Cache
     * braeuchte eine Invalidierung, sobald der Admin Werte aendert - zusaetzlicher Zustand
     * fuer einen Gewinn, den erst eine Messung rechtfertigen wuerde. Innerhalb eines Requests
     * laeuft der Zugriff in derselben Transaktion wie die Sitzungspruefung.
     *
     * <p>Der fehlende Zwischenspeicher ist zugleich der Grund, aus dem eine Aenderung ohne
     * Neustart wirkt - siehe {@link #aktualisieren}.
     *
     * @throws IllegalStateException wenn die Seed-Zeile fehlt - das waere ein defekter
     *                               Migrationsstand und kein fachlicher Fehlerfall
     */
    @Transactional(readOnly = true)
    public AppConfig lesen() {
        return laden();
    }

    /**
     * Holt die eine Zeile.
     *
     * <p>Getrennt von {@link #lesen()}, damit {@link #aktualisieren} sie nicht ueber den eigenen
     * Aufruf holen muss: Ein Selbstaufruf laeuft am Spring-Proxy vorbei, {@code readOnly} bliebe
     * dabei wirkungslos - hier folgenlos, aber nur zufaellig. Was aussieht wie ein
     * Transaktionswechsel, soll auch einer sein.
     */
    private AppConfig laden() {
        return appConfigRepository.findById(KONFIG_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "configs.app_config enthaelt keine Zeile mit id = 1 - Migrationsstand pruefen."));
    }

    /**
     * Schreibt die Konfiguration vollstaendig (S3, Abschnitt 5).
     *
     * <h2>Optimistic Locking an zwei Stellen</h2>
     * Der Versionsvergleich hier liefert die verstaendliche Meldung; die eigentliche Absicherung
     * leistet Hibernate beim Schreiben. Beides ist noetig: Der Vergleich liest, der Commit
     * schreibt, und dazwischen liegt ein Fenster, in dem eine zweite Transaktion dieselbe Zeile
     * aendern kann. Diesen Fall faengt der Handler fuer
     * {@code ObjectOptimisticLockingFailureException} im {@code GlobalExceptionHandler} ab - mit
     * demselben Fehlercode.
     *
     * <h2>Was eine Aenderung sofort bewirkt</h2>
     * <table border="1">
     *   <caption>Wirkung auf laufende Sitzungen</caption>
     *   <tr><th>Feld</th><th>Wirkung</th></tr>
     *   <tr><td>{@code sessionLeerlaufMinuten}</td>
     *       <td><b>sofort</b> - {@code SessionService} liest den Wert bei jeder Anfrage neu</td></tr>
     *   <tr><td>{@code sessionMaximalStunden}</td>
     *       <td><b>nicht rueckwirkend</b> - {@code absolut_gueltig_bis} entsteht einmalig beim
     *           Anlegen der Sitzung und wandert nie</td></tr>
     *   <tr><td>{@code anzGuests}</td>
     *       <td><b>sofort</b> ueber {@code id <= :maxGaeste}; eine Erhoehung legt die fehlenden
     *           Plaetze mit an, eine Senkung loescht keine</td></tr>
     *   <tr><td>uebrige Felder</td>
     *       <td>betrifft S4 bis S7; heute ohne Wirkung, weil die auswertenden Endpunkte noch
     *           nicht existieren</td></tr>
     * </table>
     * Die zweite Zeile ist die ueberraschende: Wer die harte Obergrenze von einer Stunde auf acht
     * setzt, wundert sich sonst, warum die eigene Sitzung trotzdem nach einer Stunde endet.
     *
     * <p><b>Die beiden Sitzungsfelder stehen bewusst hier und nicht in den Properties</b> - A14
     * nennt sie als Admin-Konfiguration. Anders als die Loeschfrist des Audit-Logs, die aus genau
     * diesem Grund <b>nicht</b> dort steht: Ein Admin soll die Nachvollziehbarkeit seiner eigenen
     * Aenderungen nicht per Formular verkuerzen koennen.
     *
     * @param anfrage        alle elf aenderbaren Felder samt der Version, auf der sie aufsetzen
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 400 EINGABE_UNGUELTIG}, wenn die Maximalzahl unter der
     *                          Mindestzahl liegt; {@code 409 DATEN_VERALTET} bei abweichender
     *                          Version
     */
    @Transactional
    public void aktualisieren(KonfigurationAendernRequest anfrage, Long adminSpielerId, String clientIp) {

        // Feldgrenzen prueft Bean Validation am DTO. Feldeuebergreifende Regeln kann sie nicht,
        // und davon gibt es hier eine. Die Datenbank hat dazu ck_app_config_teilnehmer - der
        // braechte aber einen 500 mit einem Constraint-Namen im Log statt einer Meldung, die den
        // Fehler benennt. Dasselbe Muster wie bei den Skillwerten in S2b: Die Datenbank bleibt
        // die letzte Instanz, aber sie ist nicht die Instanz, die dem Nutzer antwortet.
        if (anfrage.maxTeilnehmer() < anfrage.minTeilnehmer()) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Die Maximalzahl (%d) darf nicht unter der Mindestzahl (%d) liegen."
                            .formatted(anfrage.maxTeilnehmer(), anfrage.minTeilnehmer()));
        }

        AppConfig bestand = laden();

        if (!Objects.equals(bestand.getVersion(), anfrage.version())) {
            throw new FachlicherFehler(Fehlercode.DATEN_VERALTET);
        }

        // Vor dem Schreiben vergleichen - danach steht der alte Wert nirgends mehr.
        Map<String, Object> geaenderteFelder = unterschiede(bestand, anfrage);
        short alteAnzGuests = bestand.getAnzGuests();

        bestand.setMinTeilnehmer(anfrage.minTeilnehmer());
        bestand.setMaxTeilnehmer(anfrage.maxTeilnehmer());
        bestand.setAnzGuests(anfrage.anzGuests());
        bestand.setAlgorithmType(anfrage.algorithmType());
        bestand.setAuswechselModus(anfrage.auswechselModus());
        bestand.setAnzTeamGenerator(anfrage.anzTeamGenerator());
        bestand.setSessionLeerlaufMinuten(anfrage.sessionLeerlaufMinuten());
        bestand.setSessionMaximalStunden(anfrage.sessionMaximalStunden());
        bestand.setHalleEmail(anfrage.halleEmailBereinigt());
        bestand.setHalleAbsageVorlage(anfrage.halleAbsageVorlageBereinigt());
        bestand.setHalleVorlaufStunden(anfrage.halleVorlaufStunden());
        bestand.setGeaendertVon(ADMIN_KONTO_ID);
        bestand.setGeaendertAm(OffsetDateTime.now());

        // saveAndFlush und nicht nur save: Der Sperrkonflikt soll hier auftreten und nicht erst
        // beim Commit, wo er ausserhalb dieser Transaktion und ausserhalb des Aufraeumens laege.
        // Die Version steigt dabei um genau 1 - auch dann, wenn sich kein fachlicher Wert
        // geaendert hat, denn geaendert_am wandert bei jedem Speichern.
        appConfigRepository.saveAndFlush(bestand);

        gastplaetzeNachziehen(alteAnzGuests, anfrage.anzGuests());

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.KONFIG_GEAENDERT,
                ENTITAET, (long) KONFIG_ID, geaenderteFelder);
    }

    /**
     * Haelt {@code profil.gast_slot} zur neuen Obergrenze passend (S3, Abschnitt 6).
     *
     * <p>Der Aufruf steht <b>in</b> der Transaktion der Konfigurationsaenderung, nicht daneben:
     * Scheitert das Anlegen der Plaetze, darf auch {@code anz_guests} nicht steigen - sonst
     * stuende in der Konfiguration eine Zahl, die die Anwendung nicht einloesen kann.
     *
     * <p><b>Beim Senken geschieht nichts.</b> Zeilen werden nie geloescht: {@code
     * fk_gast_slot_session} hat kein {@code ON DELETE}, ein belegter Platz liesse sich also
     * ohnehin nicht entfernen, und die Grenze {@code id <= :maxGaeste} erledigt die Sache
     * vollstaendig. Bereits belegte Plaetze oberhalb der neuen Grenze bleiben belegt, bis ihre
     * Sitzung ablaeuft - die Gaeste haben nichts falsch gemacht, und ein Zwangsabmelden mitten in
     * einer Rueckmeldung waere unverhaeltnismaessig. Weil die Zaehlung dadurch voruebergehend
     * nicht aufgeht, steht der Vorgang im Log.
     */
    private void gastplaetzeNachziehen(short alt, short neu) {
        int angelegt = gastSlotRepository.plaetzeSicherstellen(neu);
        if (angelegt > 0) {
            LOG.info("Gastplaetze ergaenzt: {} neu, Obergrenze jetzt {} (vorher {}).", angelegt, neu, alt);
        }

        if (neu < alt) {
            int belegtOberhalb = gastSlotRepository.belegteOberhalbZaehlen(neu);
            LOG.info("Gastplaetze gesenkt: {} statt {}. Keine Zeile geloescht; {} belegte Plaetze "
                            + "oberhalb der neuen Grenze bleiben bis zum Ende ihrer Sitzung besetzt.",
                    neu, alt, belegtOberhalb);
        }
    }

    /**
     * Stellt fest, welche Felder sich wirklich aendern - fuer das Audit-Log.
     *
     * <p><b>Hier lohnt sich der Vorher-Wert</b>, anders als bei den Skillwerten in
     * {@code SpielerVerwaltungService}: Es sind hoechstens elf Werte, sie gelten anwendungsweit,
     * und die Betriebsfrage "seit wann steht das Leerlauf-Fenster auf 60 Minuten" ist ohne den
     * alten Wert nicht zu beantworten.
     *
     * <p>{@code halleAbsageVorlage} ist davon ausgenommen: Ein mehrzeiliger Text in jedem Eintrag
     * blaehte die Tabelle auf, ohne etwas zu belegen. Es genuegt der Vermerk, dass die Vorlage
     * geaendert wurde.
     *
     * <p>Verglichen wird gegen die <b>bereinigten</b> Werte des DTOs - sonst meldete ein
     * Formularfeld, das beim Leeren {@code ""} statt {@code null} sendet, eine Aenderung, die
     * keine ist.
     */
    private static Map<String, Object> unterschiede(AppConfig bestand, KonfigurationAendernRequest anfrage) {
        Map<String, Object> felder = new LinkedHashMap<>();

        vergleiche(felder, "minTeilnehmer", bestand.getMinTeilnehmer(), anfrage.minTeilnehmer());
        vergleiche(felder, "maxTeilnehmer", bestand.getMaxTeilnehmer(), anfrage.maxTeilnehmer());
        vergleiche(felder, "anzGuests", bestand.getAnzGuests(), anfrage.anzGuests());
        vergleiche(felder, "algorithmType", bestand.getAlgorithmType(), anfrage.algorithmType());
        vergleiche(felder, "auswechselModus", bestand.getAuswechselModus(), anfrage.auswechselModus());
        vergleiche(felder, "anzTeamGenerator", bestand.getAnzTeamGenerator(), anfrage.anzTeamGenerator());
        vergleiche(felder, "sessionLeerlaufMinuten",
                bestand.getSessionLeerlaufMinuten(), anfrage.sessionLeerlaufMinuten());
        vergleiche(felder, "sessionMaximalStunden",
                bestand.getSessionMaximalStunden(), anfrage.sessionMaximalStunden());
        vergleiche(felder, "halleEmail", bestand.getHalleEmail(), anfrage.halleEmailBereinigt());
        vergleiche(felder, "halleVorlaufStunden",
                bestand.getHalleVorlaufStunden(), anfrage.halleVorlaufStunden());

        if (!Objects.equals(bestand.getHalleAbsageVorlage(), anfrage.halleAbsageVorlageBereinigt())) {
            felder.put("halleAbsageVorlage", "geaendert");
        }
        return felder;
    }

    /**
     * Traegt ein Feld mit altem und neuem Wert ein, wenn es sich geaendert hat.
     *
     * <p>Die verschachtelte Karte wird vom {@code AuditService} seit S3 als echtes
     * JSON-Objekt geschrieben; {@code details->'anzGuests'->>'alt'} trifft damit.
     */
    private static void vergleiche(Map<String, Object> felder, String name, Object alt, Object neu) {
        if (Objects.equals(alt, neu)) {
            return;
        }
        Map<String, Object> aenderung = new LinkedHashMap<>();
        aenderung.put("alt", alt);
        aenderung.put("neu", neu);
        felder.put(name, aenderung);
    }
}
