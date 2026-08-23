package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.auth.AdminKonto;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.repository.auth.AdminKontoRepository;
import de.fubo.appserver.repository.auth.SessionRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Anmeldung des Admins ueber das Passwort aus {@code profil.admin_konto} (A22).
 *
 * <h2>Warum es diesen Weg ueberhaupt gibt</h2>
 * Das Adminprofil ist ein technisches Konto (Entscheidung vom 22.08.2026): Es steht nicht in
 * der Namensliste, nimmt an keinem Termin teil und wird nie in ein Team eingeteilt. Damit
 * faellt der Weg weg, ueber den alle anderen ihre Rolle bekommen - die Namensauswahl. Der
 * Adminzugang braucht deshalb eine eigene zweite Stufe.
 *
 * <p>Das Datenmodell sieht das seit jeher vor: {@code admin_konto.passwort_hash} ist
 * {@code NOT NULL}, und A22 spricht von einem <i>vergessenen Passwort</i> - ein Passwort, das
 * nie zum Anmelden dient, waere sinnlos. {@code admin_konto.spieler_id} verbindet Konto und
 * Profil und liefert genau die Id, die in die Sitzung eingetragen wird.
 *
 * <h2>Die erste Stufe bleibt Pflicht</h2>
 * Auch der Admin muss zuerst die zentrale PIN eingeben; der Endpunkt ist ausschliesslich in
 * der Stufe {@code PIN_VERIFIED} erreichbar. Die PIN grenzt den Kreis der Zugreifenden ein
 * (A1), das Passwort die Rechte innerhalb dieses Kreises - zwei verschiedene Fragen, die
 * beide beantwortet werden muessen.
 */
@Service
public class AdminService {

    /** Die Tabelle ist per CHECK-Constraint auf genau diese Zeile beschraenkt. */
    static final short ADMIN_KONTO_ID = 1;

    private final AdminKontoRepository adminKontoRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final PasswordEncoder passwortEncoder;

    public AdminService(AdminKontoRepository adminKontoRepository,
                        SessionRepository sessionRepository,
                        SessionService sessionService,
                        PasswordEncoder passwortEncoder) {
        this.adminKontoRepository = adminKontoRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.passwortEncoder = passwortEncoder;
    }

    /**
     * Vergleicht die Eingabe mit dem hinterlegten BCrypt-Hash.
     *
     * <p>Der Vergleich laeuft ueber {@link PasswordEncoder#matches}, nicht ueber einen
     * Zeichenkettenvergleich der Hashes: BCrypt bettet Kostenfaktor und Salt ein, ein
     * erneutes Kodieren desselben Passworts liefert deshalb nie denselben Wert.
     *
     * @param passwort Klartext aus dem Anfragekoerper
     * @return {@code true}, wenn das Passwort stimmt
     * @throws IllegalStateException wenn kein Admin-Konto hinterlegt ist. Das ist ein
     *                               Betriebsfehler (Bootstrap nicht gelaufen) und kein
     *                               fachlicher Fehlerfall - deshalb 500 und kein 401.
     */
    @Transactional(readOnly = true)
    public boolean passwortStimmt(String passwort) {
        if (passwort == null || passwort.isBlank()) {
            return false;
        }
        return passwortEncoder.matches(passwort, konto().getPasswortHash());
    }

    /**
     * Hebt eine Sitzung von {@code PIN_VERIFIED} auf {@code PROFILE_AUTHENTICATED} mit der
     * Rolle {@code ADMIN} und traegt das Adminprofil ein. Der Token wird dabei ausgetauscht.
     *
     * <p><b>Warum ohne Belegtpruefung?</b> Bei der Namensauswahl verhindert sie, dass zwei
     * Personen denselben Namen belegen. Hier gibt es nur eine Person. Eine zweite Anmeldung
     * abzulehnen, solange die erste Sitzung laeuft, wuerde den Admin nach einem
     * Browserabsturz bis zum Ablauf der Sitzung aussperren - ein Schaden ohne Nutzen.
     *
     * @param sessionId Id der aufrufenden Sitzung aus dem Sicherheitskontext
     * @return der neue Klartext-Token fuer das Cookie
     * @throws FachlicherFehler {@code 401}, wenn die Sitzung zwischenzeitlich ungueltig wurde
     */
    @Transactional
    public String sitzungAufAdminHeben(Long sessionId) {
        Long adminSpielerId = konto().getSpielerId();

        // Die WHERE-Klausel verlangt stage = 'PIN_VERIFIED' und eine nicht widerrufene
        // Sitzung. Null geaenderte Zeilen bedeuten: zwischen Filterchain und Schreibzugriff
        // ungueltig geworden - oder die Sitzung hat bereits eine Identitaet.
        int geaendert = sessionRepository.aufProfileAuthenticatedSetzen(
                sessionId, adminSpielerId, Rolle.ADMIN.name());

        if (geaendert == 0) {
            throw new FachlicherFehler(Fehlercode.SESSION_UNGUELTIG);
        }

        return sessionService.rotieren(sessionId);
    }

    /**
     * Setzt ein neues Admin-Passwort (S2b).
     *
     * <p>Wird von zwei Wegen aufgerufen: vom Reset per Bestaetigungs-PIN und von der
     * Aenderung im angemeldeten Zustand. Die Pruefung, <i>ob</i> geaendert werden darf,
     * liegt beim Aufrufer - hier steht nur, <i>wie</i>.
     *
     * <p><b>Der Aufrufer ist dafuer zustaendig, anschliessend die Sitzungen des Admins zu
     * widerrufen</b> ({@code SessionService#widerrufenFuerSpieler}). Ohne das bliebe eine
     * uebernommene Sitzung nach einem Reset weiter gueltig - genau der Fall, gegen den der
     * Reset gedacht ist.
     *
     * <p>{@code passwort_geaendert_am} wird ausdruecklich gesetzt und nicht dem
     * Spaltendefault ueberlassen: Der Default gilt nur beim {@code INSERT}.
     *
     * @param neuesPasswort Klartext; die Laengengrenzen prueft die Bean Validation am DTO
     */
    @Transactional
    public void passwortSetzen(String neuesPasswort) {
        AdminKonto konto = konto();
        konto.setPasswortHash(passwortEncoder.encode(neuesPasswort));
        konto.setPasswortGeaendertAm(OffsetDateTime.now());

        adminKontoRepository.save(konto);
    }

    /**
     * Liefert die hinterlegte E-Mail-Adresse des Admins - die Zieladresse der
     * Bestaetigungs-PIN beim Passwort-Reset (S2b).
     *
     * <p>Die Adresse wird bewusst <b>nicht</b> nach aussen gegeben: Der Reset-Endpunkt
     * antwortet mit {@code 204} und verraet weder Adresse noch PIN.
     */
    @Transactional(readOnly = true)
    public String email() {
        return konto().getEmail();
    }

    /** Profil-Id des Admins - fuer den Audit-Eintrag der Anmeldung. */
    @Transactional(readOnly = true)
    public Long adminSpielerId() {
        return konto().getSpielerId();
    }

    private AdminKonto konto() {
        return adminKontoRepository.findById(ADMIN_KONTO_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "profil.admin_konto enthaelt keine Zeile - der Start-Bootstrap ist nicht gelaufen."));
    }
}
