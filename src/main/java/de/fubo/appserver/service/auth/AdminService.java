package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.auth.AdminKonto;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.Spieler;
import de.fubo.appserver.repository.auth.AdminKontoRepository;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.repository.profil.SpielerRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Anmeldung des Admins ueber Anmeldename und Passwort aus {@code profil.admin_konto} (A22).
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
 *
 * <h2>Anmeldename und Passwort (ergaenzt am 29.08.2026)</h2>
 * Die Anmeldung verlangt zusaetzlich den Anmeldenamen - den Profilnamen des Adminprofils aus
 * {@code ADMIN_NAME}, zeichengenau einschliesslich Gross- und Kleinschreibung. Details und
 * Begruendung stehen an {@link #anmeldedatenStimmen(String, String)}.
 */
@Service
public class AdminService {

    /** Die Tabelle ist per CHECK-Constraint auf genau diese Zeile beschraenkt. */
    static final short ADMIN_KONTO_ID = 1;

    private final AdminKontoRepository adminKontoRepository;
    private final SpielerRepository spielerRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final PasswordEncoder passwortEncoder;

    public AdminService(AdminKontoRepository adminKontoRepository,
                        SpielerRepository spielerRepository,
                        SessionRepository sessionRepository,
                        SessionService sessionService,
                        PasswordEncoder passwortEncoder) {
        this.adminKontoRepository = adminKontoRepository;
        this.spielerRepository = spielerRepository;
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
        return passwortVergleichen(passwort, konto().getPasswortHash());
    }

    /**
     * Gemeinsamer Passwortvergleich fuer {@link #passwortStimmt(String)} und
     * {@link #anmeldedatenStimmen(String, String)}.
     *
     * <p>Die Leerpruefung steht vor dem Aufruf, weil {@code BCryptPasswordEncoder#matches}
     * bei {@code null} eine {@code IllegalArgumentException} wirft - das waere ein
     * {@code 500} statt einer Ablehnung. Ein Zeitorakel entsteht dadurch nicht: Leere
     * Eingaben faengt bereits die Bean Validation am DTO mit {@code 400} ab, sie erreichen
     * den Dienst im Regelbetrieb gar nicht.
     */
    private boolean passwortVergleichen(String passwort, String hash) {
        if (passwort == null || passwort.isBlank()) {
            return false;
        }
        return passwortEncoder.matches(passwort, hash);
    }

    /**
     * Prueft Anmeldename und Passwort gemeinsam (A22, ergaenzt am 29.08.2026).
     *
     * <h2>Warum der Name ueberhaupt geprueft wird</h2>
     * Bis zum 28.08.2026 genuegte das Passwort allein - mit dem Argument, es gebe nur einen
     * Admin, eine Kennung waere also ein Feld ohne Auswahl. Das Argument traegt fuer die
     * <i>Auswahl</i>, nicht fuer die <i>Absicherung</i>: Wer die zentrale PIN kennt, musste
     * damit nur ein einziges Geheimnis erraten. Der Anmeldename ist zudem nirgends abrufbar -
     * das Adminprofil ist aus der Namensliste ausgeschlossen -, taugt also als zweite Angabe.
     *
     * <h2>Der Anmeldename ist der Profilname</h2>
     * Verglichen wird gegen {@code profil.spieler.name} des Profils aus
     * {@code admin_konto.spieler_id}, also gegen den Wert aus {@code ADMIN_NAME}. Eine eigene
     * Spalte im Admin-Konto waere ein zweiter Name fuer dasselbe Konto, mit einer zweiten
     * Umgebungsvariablen und einer zweiten Startpruefung.
     *
     * <h2>Der Vergleich achtet auf Gross- und Kleinschreibung (Festlegung vom 29.08.2026)</h2>
     * Der Name ist ein Anmeldemerkmal, und bei einem Merkmal ist Nachsicht die falsche
     * Richtung. Tragfaehig ist das nur, weil die Schreibweise eindeutig vorgegeben ist:
     * {@code AdminBootstrap} legt den Profilnamen <b>zeichengenau</b> so ab, wie
     * {@code ADMIN_NAME} ihn nennt, und bricht den Start ab, wenn ein vorhandenes Profil nur
     * in der Schreibweise abweicht. Ohne diese Zusicherung koennte sich der Admin hier
     * aussperren, ohne einen Rueckweg zu haben: Der Passwort-Reset holt das <i>Passwort</i>
     * zurueck, nie den <i>Namen</i>.
     *
     * <p>Randleerzeichen werden dagegen weiterhin entfernt (im DTO). Das ist kein
     * Widerspruch: Ein fuehrendes Leerzeichen ist unsichtbar und nie beabsichtigt, eine
     * Schreibweise ist sichtbar und kann es sein.
     *
     * <h2>Kein vorzeitiges Verlassen (wichtig)</h2>
     * Beide Pruefungen laufen <b>immer</b>, verknuepft mit {@code &} statt {@code &&}. Ein
     * Abbruch beim falschen Namen spart die BCrypt-Berechnung und macht den Endpunkt damit zu
     * einem Zeitorakel: Eine schnelle Ablehnung hiesse "Name falsch", eine langsame "Name
     * richtig, Passwort falsch" - und der Angreifer haette den Namen in wenigen Versuchen.
     * Aus demselben Grund unterscheidet auch der Fehlercode die beiden Faelle nicht; der
     * Aufrufer meldet {@link Fehlercode#ADMIN_PASSWORT_FALSCH} fuer beide.
     *
     * <p><b>Rueckgabewert statt Ausnahme</b>, weil der Aufrufer den Fehlversuch zaehlen und
     * protokollieren muss - dieselbe Aufteilung wie bei {@code PinService#stimmt}.
     *
     * @param anmeldename Klartext aus dem Anfragekoerper, bereits von Randleerzeichen befreit
     * @param passwort    Klartext aus dem Anfragekoerper
     * @return {@code true}, wenn <i>beide</i> Angaben stimmen
     * @throws IllegalStateException wenn kein Admin-Konto hinterlegt ist (Betriebsfehler,
     *                               siehe {@link #passwortStimmt(String)})
     */
    @Transactional(readOnly = true)
    public boolean anmeldedatenStimmen(String anmeldename, String passwort) {
        AdminKonto konto = konto();

        boolean nameStimmt = nameStimmt(konto.getSpielerId(), anmeldename);
        boolean passwortStimmt = passwortVergleichen(passwort, konto.getPasswortHash());

        // Bewusst & und nicht && - siehe JavaDoc, Abschnitt "Kein vorzeitiges Verlassen".
        return nameStimmt & passwortStimmt;
    }

    /**
     * Vergleicht die Eingabe zeichengenau mit dem Namen des Adminprofils.
     *
     * <p>Fehlt das Profil zur hinterlegten {@code spieler_id}, ist das ein Betriebsfehler und
     * kein fachlicher Fehlerfall: {@code fk_admin_konto_spieler} verhindert diesen Zustand,
     * er kann also nur durch einen Eingriff an der Datenbank vorbei entstehen.
     */
    private boolean nameStimmt(Long adminSpielerId, String anmeldename) {
        if (anmeldename == null || anmeldename.isBlank()) {
            return false;
        }
        Spieler admin = spielerRepository.findById(adminSpielerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Zu admin_konto.spieler_id = %d existiert kein Profil."
                                .formatted(adminSpielerId)));

        // equals, nicht equalsIgnoreCase: Die Schreibweise gehoert zum Merkmal. Dass das
        // niemanden aussperrt, sichert der AdminBootstrap zu - er legt den Profilnamen
        // zeichengenau nach ADMIN_NAME ab.
        return anmeldename.equals(admin.getName());
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
