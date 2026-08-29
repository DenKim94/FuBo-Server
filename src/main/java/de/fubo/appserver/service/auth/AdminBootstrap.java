package de.fubo.appserver.service.auth;

import de.fubo.appserver.domain.auth.AdminKonto;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.Spieler;
import de.fubo.appserver.repository.auth.AdminKontoRepository;
import de.fubo.appserver.repository.profil.SpielerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Legt beim Start das Admin-Konto an, falls {@code profil.admin_konto} noch leer ist
 * (Abschnitt 9 der Umsetzungsanleitung, Teil "Admin-Konto").
 *
 * <p>Wie beim {@code PinBootstrap} gilt: Ein BCrypt-Hash in einer Flyway-Migration waere
 * ein Geheimnis in der Git-Historie, und Migrationen sind unveraenderlich. Die Zeile
 * entsteht deshalb erst beim Start.
 *
 * <h2>Auswahl des Admins (Entscheidung vom 16.08.2026)</h2>
 * {@code admin_konto.spieler_id} ist {@code NOT NULL}; der Bootstrap braucht also ein
 * Auswahlkriterium. Es sind {@code ADMIN_NAME}, {@code ADMIN_EMAIL} und
 * {@code ADMIN_PASSWORD} aus der Umgebung beziehungsweise der {@code .env}.
 *
 * <h2>Warum ein Startabbruch und keine Notloesung (Entscheidung vom 22.08.2026)</h2>
 * Fehlt eine der drei Angaben, bricht der Start mit einer benennenden Meldung ab, statt
 * willkuerlich ein Profil zu waehlen oder ein Zufallspasswort zu erzeugen. Ein
 * willkuerlich gewaehlter Admin waere ein stilles Sicherheitsproblem: Der Betreiber wuesste
 * nicht, wer die Adminrechte hat. Und anders als bei der zentralen PIN gibt es fuer das
 * Adminpasswort einen zweiten Weg - den Reset per E-Mail aus S2b -, der aber genau die
 * Adresse braucht, die hier fehlt.
 *
 * <h2>Das Profil wird angelegt, wenn es fehlt (Entscheidung vom 22.08.2026)</h2>
 * Eine fruehere Fassung brach ab, wenn kein Profil mit {@code ADMIN_NAME} existierte. Auf
 * einer frischen Datenbank ist {@code profil.spieler} aber leer - der Name konnte dort nie
 * passen, und der Erststart war ein Zweischritt aus "Profile einspielen" und "starten".
 *
 * <p><b>Warum nicht per Flyway-Migration?</b> Weil der Name eine Eigenschaft der
 * <i>Installation</i> ist und keine des Schemas. Eine Migration muss auf jeder Installation
 * dasselbe Ergebnis erzeugen; mit einem Platzhalter erzeugte sie bei gleicher Pruefsumme
 * unterschiedliche Daten, und Flyway koennte die Abweichung nicht bemerken - die Pruefsumme
 * deckt nur den Dateiinhalt ab. Dazu kaeme: Migrationen sind unveraenderlich, ein einmal
 * committeter Name bliebe dauerhaft in der Git-Historie (Regel "keine realen Personennamen",
 * ohne Ausnahme), und ein Platzhalter ohne Vorgabewert liesse die Migration scheitern, sobald
 * die Variable aus der Umgebung entfernt wird - was fuer {@code ADMIN_PASSWORD} nach dem
 * Erststart ausdruecklich vorgesehen ist.
 *
 * <p><b>Preis dieser Loesung:</b> Ein Tippfehler in {@code ADMIN_NAME} legt ein
 * ueberfluessiges Profil an, statt abzubrechen. Das ist verkraftbar - es passiert hoechstens
 * einmal (der Runner laeuft nur, solange kein Konto existiert), die Zeile faellt sofort in der
 * Namensliste auf und laesst sich im Adminbereich (S3) umbenennen oder deaktivieren. Die
 * Logmeldung unterscheidet deshalb ausdruecklich zwischen "vorhandenes Profil verwendet" und
 * "Profil neu angelegt".
 *
 * <h2>Die Suche laeuft zeichengenau (Festlegung vom 29.08.2026)</h2>
 * {@code findByName} statt {@code findByNameIgnoreCase}: Der Anmeldename des Admins wird
 * zeichengenau geprueft, also muss der gespeicherte Profilname zeichengenau dem Wert aus
 * {@code ADMIN_NAME} entsprechen. <b>Das ist die Zusicherung, auf der der Login aufsetzt.</b>
 * Weicht ein vorhandenes Profil allein in der Schreibweise ab, bricht der Start ab, statt ein
 * zweites, nahezu gleichnamiges Profil anzulegen - siehe {@link #pruefeSchreibweise}.
 *
 * <h2>Das Adminprofil ist ein technisches Konto (Entscheidung vom 22.08.2026)</h2>
 * Es nimmt weder an der Namensauswahl noch an Terminen noch an der Teamgenerierung teil. Der
 * Admin meldet sich ueber {@code POST /auth/admin/anmelden} mit dem Profilnamen und dem
 * Passwort aus {@code admin_konto} an; in der Namensliste taucht das Profil nicht auf.
 *
 * <p>Die Skillwerte werden deshalb je aktiver Kategorie auf {@code 0} gesetzt. Sie sind
 * fachlich bedeutungslos - das Profil wird nie eingeteilt -, machen es aber vollstaendig,
 * sodass eine spaetere Auswertung nicht ueber fehlende Zeilen stolpert. <b>Der Wert 0 ist
 * kein Ersatz fuer den Ausschluss:</b> Wuerde das Profil doch in eine Generierung geraten,
 * bekaeme sein Team einen Spieler ohne jede Staerke. Der Ausschluss steht als verbindliche
 * Regel in {@code AGENT_SERVER.md}.
 *
 * <p>Fuer Profile, die das Admin-CRUD in S3 anlegt, bleibt die Frage offen, wie der
 * Teamgenerator mit unvollstaendigen Skillwerten umgeht - siehe offener Punkt 20.
 *
 * <p><b>Der Bootstrap ist idempotent und die Variablen sind nur beim ersten Start
 * noetig.</b> Existiert das Konto bereits, passiert nichts - insbesondere wird ein
 * geaendertes Passwort <b>nicht</b> auf den Wert aus der Umgebung zurueckgesetzt. Der
 * Betreiber kann {@code ADMIN_PASSWORD} nach dem ersten Start also entfernen; ohne die
 * Idempotenz muesste das Klartextpasswort dauerhaft in der Umgebung stehen bleiben.
 */
@Component
@Order(AdminBootstrap.REIHENFOLGE)
public class AdminBootstrap implements ApplicationRunner {

    /**
     * Laeuft nach {@code PinBootstrap}.
     *
     * <p>Fachlich sind die beiden unabhaengig - {@code zugangsdaten.geaendert_von} bleibt
     * beim Bootstrap leer, weil es zu diesem Zeitpunkt noch kein Konto gibt. Die feste
     * Reihenfolge sorgt aber dafuer, dass die Meldung zur zentralen PIN immer vor der zum
     * Admin-Konto im Log steht; bei einem Startabbruch hier ist die PIN dann bereits
     * angelegt und der naechste Versuch braucht sie nicht erneut zu erzeugen.
     */
    static final int REIHENFOLGE = 20;

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);

    /** Die Tabelle ist per CHECK-Constraint auf genau diese Zeile beschraenkt. */
    private static final short ADMIN_KONTO_ID = 1;

    private final AdminKontoRepository adminKontoRepository;
    private final SpielerRepository spielerRepository;
    private final PasswordEncoder passwortEncoder;

    private final String adminName;
    private final String adminEmail;
    private final String adminPasswort;

    public AdminBootstrap(AdminKontoRepository adminKontoRepository,
                          SpielerRepository spielerRepository,
                          PasswordEncoder passwortEncoder,
                          @Value("${ADMIN_NAME:}") String adminName,
                          @Value("${ADMIN_EMAIL:}") String adminEmail,
                          @Value("${ADMIN_PASSWORD:}") String adminPasswort) {
        this.adminKontoRepository = adminKontoRepository;
        this.spielerRepository = spielerRepository;
        this.passwortEncoder = passwortEncoder;
        this.adminName = adminName;
        this.adminEmail = adminEmail;
        this.adminPasswort = adminPasswort;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminKontoRepository.existsById(ADMIN_KONTO_ID)) {
            LOG.debug("Admin-Konto ist bereits angelegt - Bootstrap uebersprungen.");
            return;
        }

        pruefeAngaben();

        String name = adminName.trim();
        Spieler vorhandenes = spielerRepository.findByName(name).orElse(null);

        // Beide Pruefungen laufen vor jeder Aenderung. Andernfalls bliebe bei einem
        // fehlgeschlagenen Start ein neu angelegtes Profil zurueck, das den naechsten
        // Versuch behindert.
        pruefeSchreibweise(vorhandenes, name);
        pruefeKeinAndererAdmin(vorhandenes, name);

        Spieler admin;
        if (vorhandenes != null) {
            admin = vorhandenes;
            adminRolleSetzen(admin);
            LOG.info("Vorhandenes Profil '{}' als Adminprofil uebernommen.", admin.getName());
        } else {
            admin = profilAnlegen(name);
            LOG.warn("Kein Profil mit dem Namen '{}' vorhanden - Adminprofil neu angelegt. "
                            + "Bei einem Tippfehler in ADMIN_NAME das Profil im Adminbereich "
                            + "umbenennen.",
                    admin.getName());
        }

        int skillzeilen = spielerRepository.nullwerteAnlegen(admin.getId());
        if (skillzeilen > 0) {
            LOG.info("Skillwerte des Adminprofils auf 0 gesetzt: {} Kategorien.", skillzeilen);
        }

        kontoAnlegen(admin);

        LOG.warn("Admin-Konto fuer das Profil '{}' angelegt (E-Mail: {}). Bitte das Passwort "
                        + "nach der ersten Anmeldung aendern und ADMIN_PASSWORD aus der Umgebung entfernen.",
                admin.getName(), adminEmail.trim());
    }

    /**
     * Bricht mit einer Meldung ab, die benennt, welche Angaben fehlen.
     *
     * <p>Alle fehlenden Werte werden in <i>einer</i> Meldung genannt und nicht nacheinander:
     * Sonst startet der Betreiber dreimal, um dreimal einen weiteren fehlenden Wert zu
     * erfahren.
     */
    private void pruefeAngaben() {
        StringBuilder fehlend = new StringBuilder();
        if (istLeer(adminName)) {
            fehlend.append("ADMIN_NAME ");
        }
        if (istLeer(adminEmail)) {
            fehlend.append("ADMIN_EMAIL ");
        }
        if (istLeer(adminPasswort)) {
            fehlend.append("ADMIN_PASSWORD ");
        }
        if (!fehlend.isEmpty()) {
            throw new IllegalStateException(
                    ("Das Admin-Konto ist noch nicht angelegt und folgende Angaben fehlen in der "
                            + "Umgebung bzw. der .env: %s- der Start wird abgebrochen. "
                            + "Ohne diese Angaben liesse sich nur willkuerlich ein Admin bestimmen.")
                            .formatted(fehlend));
        }
    }

    /**
     * Bricht ab, wenn ein Profil allein in der Schreibweise von {@code ADMIN_NAME} abweicht
     * (Festlegung vom 29.08.2026).
     *
     * <h2>Warum diese Pruefung noetig wurde</h2>
     * Der Anmeldename des Admins wird seit dem 29.08.2026 <b>zeichengenau</b> geprueft
     * ({@code AdminService#anmeldedatenStimmen}). Das traegt nur, solange der gespeicherte
     * Profilname zeichengenau dem Wert aus {@code ADMIN_NAME} entspricht. Frueher suchte
     * dieser Runner mit {@code findByNameIgnoreCase}: Stand in der Datenbank
     * "Beispielspieler 05" und in der {@code .env} {@code beispielspieler 05}, uebernahm er
     * das vorhandene Profil - und der Betreiber konnte sich anschliessend mit genau dem Wert
     * <i>nicht</i> anmelden, den er selbst gesetzt hatte.
     *
     * <h2>Warum Abbruch und nicht Anlegen</h2>
     * Die Suche laeuft jetzt exakt, ein Profil mit abweichender Schreibweise wird also nicht
     * mehr gefunden. Ohne diese Pruefung legte der Runner daneben ein zweites, nahezu
     * gleichnamiges Profil an - {@code uq_spieler_name} laesst das zu, weil der Index in
     * PostgreSQL gross-/kleinschreibungsempfindlich ist. In der Namensliste stuenden dann
     * zwei fast gleiche Eintraege, von denen einer ein technisches Konto ist.
     *
     * <p>Ein Abbruch ist hier das mildere Mittel: Er kostet einen Neustart mit korrigierter
     * {@code .env}, waehrend der Alternativfall - Admin ausgesperrt - <b>keinen</b>
     * Selbstbedienungsweg hat. Der Passwort-Reset holt das Passwort zurueck, nie den Namen;
     * es bliebe nur ein Eingriff an der Datenbank. Das entspricht der Linie des Runners:
     * lieber ein benennender Abbruch als eine stille Notloesung.
     *
     * <p><b>Es bleibt bei der bisherigen Nachsicht, wo kein Profil im Weg steht:</b> Findet
     * sich gar nichts, wird das Profil angelegt wie bisher. Nur die Zweideutigkeit fuehrt
     * zum Abbruch.
     *
     * <p>Die Gegenprobe laeuft ueber eine <b>Liste</b>, nicht ueber ein {@code Optional}:
     * {@code uq_spieler_name} ist gross-/kleinschreibungsempfindlich, es koennen also
     * mehrere Schreibweisen nebeneinander stehen. Die Meldung nennt sie dann alle.
     *
     * @param exakterTreffer Ergebnis der zeichengenauen Suche oder {@code null}
     * @param gewuenschterName Wert aus {@code ADMIN_NAME}, bereits getrimmt
     */
    private void pruefeSchreibweise(Spieler exakterTreffer, String gewuenschterName) {
        if (exakterTreffer != null) {
            return;
        }

        List<Spieler> abweichend = spielerRepository.findAllByNameIgnoreCase(gewuenschterName);
        if (abweichend.isEmpty()) {
            return;
        }

        String gefundene = abweichend.stream()
                .map(spieler -> "'" + spieler.getName() + "'")
                .collect(Collectors.joining(", "));

        throw new IllegalStateException(
                ("ADMIN_NAME nennt '%s', in profil.spieler steht aber %s - der Unterschied "
                        + "liegt allein in der Gross-/Kleinschreibung. Der Anmeldename des "
                        + "Admins wird zeichengenau geprueft; wuerde hier ein vorhandenes "
                        + "Profil uebernommen, liesse sich der Wert aus ADMIN_NAME nicht zum "
                        + "Anmelden verwenden - und ein zweites, nahezu gleichnamiges Profil "
                        + "anzulegen waere noch schlechter. Entweder ADMIN_NAME an die "
                        + "vorhandene Schreibweise angleichen oder das Profil umbenennen.")
                        .formatted(gewuenschterName, gefundene));
    }

    /**
     * Verhindert einen unverstaendlichen Datenbankfehler.
     *
     * <p>Traegt bereits ein anderes Profil die Rolle {@code ADMIN} - etwa aus einem
     * eingespielten Datenbestand -, scheiterte weiter unten das {@code UPDATE} der Rolle
     * beziehungsweise das {@code INSERT} des neuen Profils am partiellen Unique-Index
     * {@code uq_spieler_genau_ein_admin}. Die Meldung der Datenbank benennt dann den Index,
     * nicht die Ursache.
     *
     * <p>Die Pruefung laeuft deshalb <b>vor</b> jeder Aenderung. Andernfalls bliebe bei
     * jedem fehlgeschlagenen Start ein neu angelegtes Profil zurueck, und beim naechsten
     * Versuch stuende sein Name bereits belegt im Weg.
     *
     * @param gewaehlt         vorhandenes Profil zum konfigurierten Namen oder {@code null}
     * @param gewuenschterName Wert aus {@code ADMIN_NAME}, fuer die Meldung
     */
    private void pruefeKeinAndererAdmin(Spieler gewaehlt, String gewuenschterName) {
        Optional<Spieler> vorhandener = spielerRepository.findByRolle(Rolle.ADMIN);

        boolean derselbe = vorhandener.isPresent() && gewaehlt != null
                && vorhandener.get().getId().equals(gewaehlt.getId());

        if (vorhandener.isPresent() && !derselbe) {
            throw new IllegalStateException(
                    ("Das Profil '%s' traegt bereits die Rolle ADMIN, ADMIN_NAME nennt aber '%s'. "
                            + "Es darf nur einen Admin geben (uq_spieler_genau_ein_admin). "
                            + "Entweder ADMIN_NAME anpassen oder die Rolle des bisherigen Profils entziehen.")
                            .formatted(vorhandener.get().getName(), gewuenschterName));
        }
    }

    /**
     * Legt ein neues Profil mit der Rolle {@code ADMIN} an.
     *
     * <p>{@code saveAndFlush} statt {@code save}: {@link #kontoAnlegen} braucht sofort den
     * erzeugten Schluessel fuer {@code admin_konto.spieler_id}. Bei {@code IDENTITY} setzt
     * Hibernate das {@code INSERT} zwar ohnehin unmittelbar ab - sich darauf zu verlassen
     * waere aber eine unsichtbare Kopplung an die Generierungsstrategie.
     *
     * <p>Die Zeitstempel werden ausdruecklich gesetzt, obwohl die Spalten in {@code V002}
     * einen Default haben: Die Entity bildet sie als {@code nullable = false} ab, und ein
     * {@code null} liefe in eine Verletzung der Spaltenbedingung, statt den Default zu ziehen.
     */
    private Spieler profilAnlegen(String name) {
        Spieler admin = new Spieler();
        admin.setName(name);
        admin.setRolle(Rolle.ADMIN);
        admin.setAktiv(true);
        admin.setErstelltAm(OffsetDateTime.now());
        admin.setGeaendertAm(OffsetDateTime.now());

        return spielerRepository.saveAndFlush(admin);
    }

    /** Hebt das gewaehlte Profil auf die Rolle {@code ADMIN}. */
    private void adminRolleSetzen(Spieler admin) {
        if (admin.getRolle() == Rolle.ADMIN) {
            return;
        }
        admin.setRolle(Rolle.ADMIN);
        admin.setGeaendertAm(OffsetDateTime.now());
        spielerRepository.save(admin);
    }

    /** Legt die Singleton-Zeile in {@code profil.admin_konto} an. */
    private void kontoAnlegen(Spieler admin) {
        AdminKonto konto = new AdminKonto();
        konto.setId(ADMIN_KONTO_ID);
        konto.setSpielerId(admin.getId());
        konto.setEmail(adminEmail.trim());
        konto.setPasswortHash(passwortEncoder.encode(adminPasswort));
        konto.setPasswortGeaendertAm(OffsetDateTime.now());

        adminKontoRepository.save(konto);
    }

    private static boolean istLeer(String wert) {
        return wert == null || wert.isBlank();
    }
}
