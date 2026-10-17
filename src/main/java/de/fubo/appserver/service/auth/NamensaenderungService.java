package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AnforderungsFenster;
import de.fubo.appserver.domain.auth.OffeneNamensaenderung;
import de.fubo.appserver.repository.auth.NamensaenderungRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.mail.MailService;
import de.fubo.appserver.service.profil.SpielerVerwaltungService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Aenderung des Admin-Anmeldenamens mit Bestaetigung per E-Mail (A22, Ergaenzung vom
 * 04.10.2026).
 *
 * <h2>Warum der Name eine Bestaetigung braucht</h2>
 * Der Anmeldename ist das einzige Anmeldemerkmal, das der Passwort-Reset nicht zurueckholt.
 * Bis zum 04.10.2026 genuegte eine gueltige Adminsitzung, um ihn zu aendern - wer an einem
 * offenen Rechner sass, konnte den Admin mit einem einzigen Aufruf dauerhaft aussperren, und
 * der Weg zurueck fuehrte nur ueber die Datenbank. Die Bestaetigung verlangt zusaetzlich den
 * Zugriff auf das hinterlegte Postfach.
 *
 * <h2>Zwei Schritte, der Name im ersten</h2>
 * {@link #anfordern} prueft den Namen, speichert ihn am Vorgang und verschickt die PIN;
 * {@link #einloesen} uebernimmt ihn. Der Name steht im ersten Schritt und nicht - wie das
 * Passwort beim Reset - im zweiten (Entscheidung des Haupt-Entwicklers vom 04.10.2026):
 * <ul>
 *   <li>Die Nachricht kann den Namen nennen. <b>Die PIN bestaetigt dann genau das, was der
 *       Admin gelesen hat</b>, und nicht einen beliebigen Namen, der spaeter mitgeschickt
 *       wird.</li>
 *   <li>Ein vergebener Name faellt <b>vor</b> dem Versand auf ({@code 409 NAME_BELEGT}) und
 *       loest keine Nachricht aus.</li>
 * </ul>
 * Beim Passwort waere das falsch: Ein Passwort im Klartext gehoert weder in eine Tabelle noch
 * in eine E-Mail. Ein Name ist kein Geheimnis.
 *
 * <h2>Dieselben Grenzen wie beim Passwort-Reset</h2>
 * Fuenf Versuche je Vorgang, 15 Minuten, drei Anforderungen je Stunde und Adresse, BCrypt,
 * Brute-Force-Zaehler. Gelesen werden sie aus {@code fubo.reset.*} - es ist dieselbe Art PIN
 * mit derselben Staerke, und zwei Saetze Grenzen fuer dasselbe Mittel liefen auseinander.
 * Zusaetzlich zur zentralen PIN steht hier die Adminsitzung davor.
 *
 * <h2>Die Sitzung bleibt bestehen</h2>
 * Auch nach dem Einloesen wird nichts widerrufen - unveraendert seit dem 29.08.2026 und aus
 * demselben Grund: Der Name verschafft allein keinen Zugang.
 */
@Service
public class NamensaenderungService {

    private static final Logger LOG = LoggerFactory.getLogger(NamensaenderungService.class);

    /** Laenge des Drosselungsfensters; deckt sich mit dem {@code interval} in der Abfrage. */
    private static final Duration DROSSELUNGSFENSTER = Duration.ofHours(1);

    /** Betroffene Entitaet im Audit-Log: Umbenannt wird das Adminprofil. */
    private static final String ENTITAET_SPIELER = "spieler";

    private final NamensaenderungRepository repository;
    private final PasswordEncoder passwortEncoder;
    private final MailService mailService;
    private final AdminService adminService;
    private final SpielerVerwaltungService spielerVerwaltungService;
    private final ZugangsdatenService zugangsdatenService;
    private final AuditService auditService;
    private final FuboProperties.Reset grenzen;
    private final Clock uhr;

    public NamensaenderungService(NamensaenderungRepository repository,
                                  PasswordEncoder passwortEncoder,
                                  MailService mailService,
                                  AdminService adminService,
                                  SpielerVerwaltungService spielerVerwaltungService,
                                  ZugangsdatenService zugangsdatenService,
                                  AuditService auditService,
                                  FuboProperties eigenschaften,
                                  Clock uhr) {
        this.repository = repository;
        this.passwortEncoder = passwortEncoder;
        this.mailService = mailService;
        this.adminService = adminService;
        this.spielerVerwaltungService = spielerVerwaltungService;
        this.zugangsdatenService = zugangsdatenService;
        this.auditService = auditService;
        this.grenzen = eigenschaften.reset();
        this.uhr = uhr;
    }

    /**
     * Prueft den gewuenschten Namen, legt eine Anforderung an und verschickt die PIN.
     *
     * <p><b>Reihenfolge:</b> Drosselung, Name, Entwerten, Anlegen, Versand, Protokoll. Die
     * Drosselung steht vorn, weil sie das ist, was der Admin nicht aendern kann - wer beides
     * falsch hat, soll zuerst erfahren, dass er warten muss, nicht einen anderen Namen suchen
     * und danach doch warten. Der Name steht vor allem, was schreibt.
     *
     * <p><b>Der Versand laeuft innerhalb der Transaktion</b>, wie beim Reset: Scheitert er,
     * rollt alles zurueck - auch das Entwerten einer zuvor offenen Anforderung, die damit
     * gueltig bleibt.
     *
     * @param neuerName bereits getrimmter Name
     * @param clientIp  anfordernde Adresse; Grundlage der Drosselung und des Protokolls
     * @throws FachlicherFehler {@code 429 NAME_AENDERUNG_GEDROSSELT},
     *                          {@code 409 NAME_BELEGT} oder {@code 503 VERSAND_FEHLGESCHLAGEN}
     */
    @Transactional
    public void anfordern(String neuerName, String clientIp) {
        pruefeDrosselung(clientIp);
        spielerVerwaltungService.pruefeAdminNameFrei(neuerName);

        repository.offeneEntwerten();

        String pin = BestaetigungsPin.erzeugen();
        int gueltigkeitMinuten = grenzen.gueltigkeitMinuten();
        repository.anlegen(passwortEncoder.encode(pin), neuerName, gueltigkeitMinuten, clientIp);

        mailService.sendeNamensaenderungsPin(adminService.email(), pin, gueltigkeitMinuten, neuerName);

        Long adminSpielerId = adminService.adminSpielerId();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nameNeu", neuerName);
        details.put("gueltigkeitMinuten", gueltigkeitMinuten);
        auditService.protokolliere(adminSpielerId, clientIp,
                AuditAktion.ADMIN_NAME_AENDERUNG_ANGEFORDERT,
                ENTITAET_SPIELER, adminSpielerId, details);
    }

    /**
     * Zaehlt einen Versuch und prueft die eingegebene PIN.
     *
     * <p>Dasselbe Muster wie {@code PasswortResetService#versuchPruefen}: Eine falsche PIN ist
     * ein Fehlversuch, den der Aufrufer zaehlen und protokollieren soll (leeres Ergebnis); ein
     * unbrauchbarer Vorgang ist ein Endzustand und wird sofort geworfen.
     *
     * <p><b>Bewusst ohne {@code @Transactional}</b> - der Zaehler laeuft in einer eigenen
     * Transaktion und ist festgeschrieben, sobald der Aufruf zurueckkehrt.
     *
     * @param eingegebenePin Klartext aus dem Anfragekoerper
     * @return Id der Anforderung bei richtiger PIN, sonst ein leeres Ergebnis
     * @throws FachlicherFehler {@code 409 NAME_AENDERUNG_UNGUELTIG}, wenn keine brauchbare
     *                          Anforderung existiert
     */
    public Optional<Long> versuchPruefen(String eingegebenePin) {
        OffeneNamensaenderung vorgang = repository.versuchZaehlen(grenzen.maxVersuche())
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.NAME_AENDERUNG_UNGUELTIG));

        if (!passwortEncoder.matches(eingegebenePin, vorgang.pinHash())) {
            return Optional.empty();
        }
        return Optional.of(vorgang.id());
    }

    /**
     * Loest die Anforderung ein und uebernimmt den bestaetigten Namen.
     *
     * <p>Schliessen und Umbenennen stehen in <i>einer</i> Transaktion. Scheitert die
     * Umbenennung - etwa weil der Name seit der Anforderung an ein anderes Profil vergeben
     * wurde ({@code 409 NAME_BELEGT}) -, rollt auch das Schliessen zurueck. Die Anforderung
     * bleibt dann offen, laesst sich aber nicht mehr einloesen; die naechste Anforderung
     * entwertet sie.
     *
     * <p>Protokoll und Sitzungsverhalten stehen in
     * {@link ZugangsdatenService#adminNameAendern(String, String)} und haben sich gegenueber
     * dem direkten Weg nicht geaendert.
     *
     * @param vorgangId Anforderung aus {@link #versuchPruefen(String)}
     * @param clientIp  Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 409 NAME_AENDERUNG_UNGUELTIG}, wenn die Anforderung
     *                          zwischen Pruefung und Einloesung verbraucht, entwertet oder
     *                          abgelaufen ist; {@code 409 NAME_BELEGT} siehe oben
     */
    @Transactional
    public void einloesen(Long vorgangId, String clientIp) {
        String neuerName = repository.einloesen(vorgangId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.NAME_AENDERUNG_UNGUELTIG));

        zugangsdatenService.adminNameAendern(neuerName, clientIp);
    }

    /**
     * Entfernt Anforderungen jenseits der Aufbewahrungsfrist ({@code fubo.reset.aufbewahrung-tage}).
     *
     * <p>Dieselbe Frist wie beim Passwort-Reset und aus demselben Grund: {@code angefordert_von_ip}
     * ist personenbezogen, und die Tabelle darf nie laenger aufbewahren als das Protokoll, das
     * den fachlichen Beleg traegt. Der Lauf schreibt sich nicht selbst ins Audit-Log.
     *
     * <p>Fuenf Minuten nach dem Reset-Lauf (4:00) - gleichzeitige Loeschlaeufe auf einem
     * Raspberry Pi stuenden einander nur im Weg.
     */
    @Scheduled(cron = "0 5 4 * * *")
    @Transactional
    public void alteVorgaengeEntfernen() {
        int aufbewahrungTage = grenzen.aufbewahrungTage();
        int anzahl = repository.loescheAelterAls(OffsetDateTime.now(uhr).minusDays(aufbewahrungTage));

        LOG.info("Anforderungen zur Namensaenderung aelter als {} Tage entfernt: {}", aufbewahrungTage, anzahl);
    }

    /**
     * Lehnt ab, sobald das Stundenkontingent einer Adresse ausgeschoepft ist.
     *
     * <p>Ein eigener Zaehler, getrennt von dem des Resets - die Anforderungen stehen in einer
     * eigenen Tabelle. Wer das eine ausschoepft, behaelt das andere; beide begrenzen den
     * Versand von E-Mails, nicht das Raten.
     */
    private void pruefeDrosselung(String clientIp) {
        AnforderungsFenster fenster = repository.anforderungenDerLetztenStunde(clientIp);

        if (fenster.anzahl() < grenzen.maxAnforderungenProStunde()) {
            return;
        }

        long restSekunden = restwartezeit(fenster.aeltestes());
        throw new FachlicherFehler(Fehlercode.NAME_AENDERUNG_GEDROSSELT,
                "Zu viele Anforderungen. Bitte in %d Sekunden erneut versuchen.".formatted(restSekunden),
                restSekunden);
    }

    /**
     * Sekunden, bis die aelteste Anforderung aus dem Fenster faellt; mindestens eine.
     */
    private long restwartezeit(OffsetDateTime aelteste) {
        if (aelteste == null) {
            return DROSSELUNGSFENSTER.toSeconds();
        }
        long rest = Duration.between(OffsetDateTime.now(uhr), aelteste.plus(DROSSELUNGSFENSTER)).toSeconds();
        return Math.max(1, rest + 1);
    }
}
