package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AnforderungsFenster;
import de.fubo.appserver.domain.auth.OffenerReset;
import de.fubo.appserver.repository.auth.PasswortResetRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.mail.MailService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Zuruecksetzen des Admin-Passworts ueber eine fuenfstellige Bestaetigungs-PIN (A22).
 *
 * <h2>Zwei Schritte, nicht drei</h2>
 * Anfordern und Bestaetigen - kein eigener Pruefschritt dazwischen. Ein solcher braeuchte
 * einen Zwischenzustand: ein kurzlebiges Token, das "die PIN war richtig" bezeichnet. Das
 * waere ein zweiter Anmeldemechanismus neben der Sitzung, mit eigener Gueltigkeit, eigenem
 * Widerruf und eigenen Fehlerfaellen. Der Gewinn fuer das Frontend waere gering: Es sammelt
 * PIN und neues Passwort ohnehin in einem Formular.
 *
 * <h2>Warum fuenf Stellen genuegen</h2>
 * 100 000 Moeglichkeiten sind fuer sich genommen wenig. Tragfaehig wird die PIN erst durch
 * die Summe der Grenzen: fuenf Versuche je Vorgang, 15 Minuten Gueltigkeit, drei
 * Anforderungen je Stunde und Adresse, BCrypt statt Klartext und die Lage des Endpunkts
 * hinter der zentralen PIN. Selbst wer eine Stunde lang alle erlaubten Versuche
 * ausschoepft, kommt auf 15 von 100 000 - etwa 0,015 Prozent. <b>Keine dieser Grenzen darf
 * entfallen</b>; jede einzelne traegt.
 *
 * <h2>Umfang des Sitzungswiderrufs</h2>
 * Widerrufen werden ausschliesslich die Sitzungen des <i>Admins</i>, nicht alle Sitzungen
 * der Anwendung (entschieden zu offenem Punkt 5 der S2b-Anleitung, abweichend vom Entwurf
 * in Abschnitt 4.2). Das Adminpasswort betrifft nur den Adminzugang; Spieler und Gaeste
 * ohne Grund abzumelden waere ein Schaden ohne Nutzen. Alle Sitzungen widerruft nur der
 * Wechsel der <i>zentralen</i> PIN - dort ist es das gemeinsame Geheimnis, das sich aendert.
 */
@Service
public class PasswortResetService {

    /**
     * Kryptografisch sichere Quelle. Ein {@code java.util.Random} waere hier ein Fehler:
     * Aus wenigen beobachteten Werten laesst sich sein Zustand rekonstruieren.
     */
    private static final SecureRandom ZUFALL = new SecureRandom();

    /** Laenge des Drosselungsfensters; deckt sich mit dem {@code interval} in der Abfrage. */
    private static final Duration DROSSELUNGSFENSTER = Duration.ofHours(1);

    /** Die Tabelle {@code profil.admin_konto} ist auf genau diese Zeile beschraenkt. */
    private static final long ADMIN_KONTO_ID = 1L;

    private final PasswortResetRepository resetRepository;
    private final PasswordEncoder passwortEncoder;
    private final MailService mailService;
    private final AdminService adminService;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final FuboProperties.Reset konfiguration;

    public PasswortResetService(PasswortResetRepository resetRepository,
                                PasswordEncoder passwortEncoder,
                                MailService mailService,
                                AdminService adminService,
                                SessionService sessionService,
                                AuditService auditService,
                                FuboProperties eigenschaften) {
        this.resetRepository = resetRepository;
        this.passwortEncoder = passwortEncoder;
        this.mailService = mailService;
        this.adminService = adminService;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.konfiguration = eigenschaften.reset();
    }

    /**
     * Erzeugt eine Bestaetigungs-PIN, speichert ihren Hash und versendet sie.
     *
     * <p><b>Der Versand laeuft innerhalb der Transaktion.</b> Scheitert er, rollt der
     * Vorgang mit zurueck - es bleibt keine PIN gespeichert, die niemand bekommen hat. Die
     * Alternative, nach dem Commit zu senden, hielte die Datenbankverbindung kuerzer,
     * hinterliesse aber bei einem Fehlschlag einen gueltigen Vorgang ohne Empfaenger. Da
     * hier eine Person alle paar Monate einen Reset ausloest, wiegt die Verstaendlichkeit
     * schwerer als die Verbindungsdauer. Bedingung dafuer sind die Zeitgrenzen aus
     * {@code fubo.mail.zeitgrenze-millis}.
     *
     * @param clientIp anfordernde Adresse; Grundlage der Drosselung und des Protokolls
     * @throws FachlicherFehler {@code 429}, wenn die Adresse ihr Stundenkontingent
     *                          ausgeschoepft hat, oder {@code 503}, wenn der Versand
     *                          scheitert
     */
    @Transactional
    public void anfordern(String clientIp) {
        pruefeDrosselung(clientIp);

        // Aeltere offene Vorgaenge entwerten: Sonst waeren mehrere PINs gleichzeitig
        // gueltig, und ein abgefangener alter Wert bliebe brauchbar.
        resetRepository.offeneEntwerten();

        String pin = erzeugePin();
        int gueltigkeitMinuten = konfiguration.gueltigkeitMinuten();

        resetRepository.anlegen(
                passwortEncoder.encode(pin),
                OffsetDateTime.now().plusMinutes(gueltigkeitMinuten),
                clientIp);

        mailService.sendeBestaetigungsPin(adminService.email(), pin, gueltigkeitMinuten);

        auditService.protokolliere(clientIp, AuditAktion.PASSWORT_RESET_ANGEFORDERT,
                Map.of("gueltigkeitMinuten", gueltigkeitMinuten));
    }

    /**
     * Zaehlt einen Versuch und prueft die eingegebene PIN.
     *
     * <p><b>Der Rueckgabetyp trennt zwei Faelle, die verschieden behandelt werden:</b> Eine
     * falsche PIN ist ein Fehlversuch, den der Aufrufer zaehlen und protokollieren soll;
     * ein unbrauchbarer Vorgang ist ein Endzustand, aus dem nur eine neue Anforderung
     * herausfuehrt. Der zweite Fall wird deshalb sofort als {@code 409} geworfen, der erste
     * als leeres Ergebnis gemeldet - genauso, wie {@code PinService#stimmt} und
     * {@code AdminService#passwortStimmt} es tun. Der Controller bleibt damit in demselben
     * Muster wie beim PIN- und beim Admin-Login.
     *
     * <p><b>Die Methode traegt bewusst kein {@code @Transactional}.</b> Der Zaehler laeuft
     * im Repository in einer eigenen Transaktion und ist festgeschrieben, sobald der Aufruf
     * zurueckkehrt - unabhaengig davon, ob der Aufrufer die Anfrage anschliessend ablehnt.
     *
     * @param eingegebenePin Klartext aus dem Anfragekoerper
     * @return Id des Vorgangs bei richtiger PIN, sonst ein leeres Ergebnis
     * @throws FachlicherFehler {@code 409 RESET_UNGUELTIG}, wenn kein brauchbarer Vorgang
     *                          existiert - nie angefordert, abgelaufen, bereits verbraucht
     *                          oder die Versuche sind erschoepft
     */
    public Optional<Long> versuchPruefen(String eingegebenePin) {
        OffenerReset vorgang = resetRepository.versuchZaehlen(konfiguration.maxVersuche())
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.RESET_UNGUELTIG));

        if (!passwortEncoder.matches(eingegebenePin, vorgang.pinHash())) {
            return Optional.empty();
        }
        return Optional.of(vorgang.id());
    }

    /**
     * Loest den Vorgang ein: Passwort setzen, Vorgang schliessen, Adminsitzungen
     * widerrufen.
     *
     * <p>Die drei Schritte gehoeren in <i>eine</i> Transaktion. Fielen sie auseinander,
     * gaebe es Zwischenzustaende, die niemand haben will - ein gesetztes Passwort mit noch
     * offenem Vorgang etwa liesse sich ein zweites Mal einloesen.
     *
     * <p>Die <b>aufrufende</b> Sitzung bleibt bestehen. Sie steht in {@code PIN_VERIFIED}
     * und gehoert nicht dem Admin; das Frontend kann direkt zum Admin-Login weitergehen.
     *
     * @param vorgangId     Vorgang aus {@link #versuchPruefen(String)}
     * @param neuesPasswort Klartext; die Laengengrenzen prueft die Bean Validation am DTO
     * @param clientIp      Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 409 RESET_UNGUELTIG}, wenn der Vorgang zwischen
     *                          Pruefung und Einloesung von einem anderen Aufruf verbraucht
     *                          wurde
     */
    @Transactional
    public void passwortSetzen(Long vorgangId, String neuesPasswort, String clientIp) {
        if (resetRepository.verbrauchen(vorgangId) == 0) {
            throw new FachlicherFehler(Fehlercode.RESET_UNGUELTIG);
        }

        adminService.passwortSetzen(neuesPasswort);

        Long adminSpielerId = adminService.adminSpielerId();
        sessionService.widerrufenFuerSpieler(adminSpielerId);

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.PASSWORT_GEAENDERT,
                "admin_konto", ADMIN_KONTO_ID, Map.of("weg", "reset"));
    }

    /**
     * Lehnt ab, sobald das Stundenkontingent einer Adresse ausgeschoepft ist.
     *
     * <p><b>Warum ein eigener Zaehler und nicht der {@code BruteForceService}?</b> Jener
     * zaehlt <i>Fehlversuche</i> und sperrt nach Haeufung. Hier wird eine
     * <i>erfolgreiche</i> Handlung begrenzt - jede Anforderung ist fuer sich legitim, nur
     * nicht beliebig oft. Zwei verschiedene Fragen, zwei Mechanismen.
     *
     * <p>Die Restwartezeit ergibt sich aus der aeltesten Anforderung im Fenster: Sobald
     * diese aus dem Fenster faellt, ist wieder ein Platz frei.
     */
    private void pruefeDrosselung(String clientIp) {
        AnforderungsFenster fenster = resetRepository.anforderungenDerLetztenStunde(clientIp);

        if (fenster.anzahl() < konfiguration.maxAnforderungenProStunde()) {
            return;
        }

        long restSekunden = restwartezeit(fenster.aeltestes());
        throw new FachlicherFehler(Fehlercode.RESET_GEDROSSELT,
                "Zu viele Anforderungen. Bitte in %d Sekunden erneut versuchen.".formatted(restSekunden),
                restSekunden);
    }

    /**
     * Sekunden, bis der aelteste Eintrag aus dem Fenster faellt.
     *
     * <p>Mindestens eine Sekunde: Eine Restzeit von 0 waere fuer den Nutzer irrefuehrend -
     * dieselbe Ueberlegung wie im {@code BruteForceService}.
     */
    private static long restwartezeit(OffsetDateTime aelteste) {
        if (aelteste == null) {
            return DROSSELUNGSFENSTER.toSeconds();
        }
        long rest = Duration.between(OffsetDateTime.now(), aelteste.plus(DROSSELUNGSFENSTER)).toSeconds();
        return Math.max(1, rest + 1);
    }

    /**
     * Fuenf Stellen, kryptografisch sicher, mit fuehrenden Nullen.
     *
     * <p><b>{@code nextInt(100_000)} und nicht {@code 10_000 + nextInt(90_000)}.</b> Die
     * zweite Variante schloesse alle PINs mit fuehrender Null aus und verkleinerte den Raum
     * um zehn Prozent - fuer eine Formatierung, die {@code %05d} ohnehin erledigt.
     */
    private static String erzeugePin() {
        return "%05d".formatted(ZUFALL.nextInt(100_000));
    }
}
