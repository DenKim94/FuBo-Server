package de.fubo.appserver.service.auth;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.AdminKonto;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.Spieler;
import de.fubo.appserver.repository.auth.AdminKontoRepository;
import de.fubo.appserver.repository.profil.SpielerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft den Admin-Bootstrap aus {@code S2_UMSETZUNG.md}, Abschnitt 9.
 *
 * <p><b>Der Runner wird von Hand erzeugt und aufgerufen</b>, nicht die Bean aus dem
 * Kontext. Nur so lassen sich verschiedene Belegungen von {@code ADMIN_NAME},
 * {@code ADMIN_EMAIL} und {@code ADMIN_PASSWORD} pruefen - die Bean im Kontext ist beim
 * Start bereits mit den Werten aus {@code src/test/resources/application.yml} gelaufen.
 * Die Repositories stammen dagegen aus dem Kontext und laufen damit in der
 * Test-Transaktion, die anschliessend zurueckgerollt wird.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminBootstrapTests {

    private static final String TEST_EMAIL = "admin@example.invalid";

    /** Die Tabelle ist per CHECK-Constraint auf genau diese Zeile beschraenkt. */
    private static final short ADMIN_KONTO_ID = 1;

    /**
     * Name, den es in den Demodaten bewusst nicht gibt. Neutral gehalten - reale
     * Personennamen sind in Testdaten untersagt.
     */
    private static final String NEUER_NAME = "Testadmin Neu";

    @Autowired
    private AdminKontoRepository adminKontoRepository;

    @Autowired
    private SpielerRepository spielerRepository;

    @Autowired
    private PasswordEncoder passwortEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ Idempotenz

    /**
     * Existiert das Konto bereits, passiert nichts - insbesondere wird ein geaendertes
     * Passwort nicht auf den Wert aus der Umgebung zurueckgesetzt. Ohne diese Eigenschaft
     * muesste das Klartextpasswort dauerhaft in der Umgebung stehen bleiben.
     */
    @Test
    void vorhandenesKontoBleibtUnveraendert() {
        String hashVorher = jdbc.queryForObject(
                "SELECT passwort_hash FROM profil.admin_konto WHERE id = 1", String.class);
        assertThat(hashVorher).as("Der Kontextstart hat das Konto bereits angelegt").isNotNull();

        bootstrap("Beispielspieler 12", TEST_EMAIL, "ein-ganz-anderes-passwort").run(null);

        assertThat(jdbc.queryForObject(
                "SELECT passwort_hash FROM profil.admin_konto WHERE id = 1", String.class))
                .isEqualTo(hashVorher);
    }

    // ------------------------------------------------------------------ Startabbruch

    /**
     * Fehlen Angaben, bricht der Start ab - und die Meldung nennt alle fehlenden Werte auf
     * einmal. Sonst startet der Betreiber dreimal, um dreimal einen weiteren fehlenden Wert
     * zu erfahren.
     */
    @Test
    void fehlendeAngabenBrechenDenStartAbUndBenennenAlleWerte() {
        kontoEntfernen();

        assertThatThrownBy(() -> bootstrap("", "", "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_NAME")
                .hasMessageContaining("ADMIN_EMAIL")
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    /**
     * Ein fehlendes Passwort allein genuegt fuer den Abbruch. Bewusst keine Notloesung mit
     * Zufallspasswort: Anders als bei der zentralen PIN gibt es fuer das Adminpasswort
     * einen zweiten Weg - den Reset per E-Mail aus S2b -, der genau die Adresse braucht,
     * die dann ebenfalls fehlen koennte.
     */
    @Test
    void fehlendesPasswortBrichtDenStartAb() {
        kontoEntfernen();

        assertThatThrownBy(() -> bootstrap("Beispielspieler 12", TEST_EMAIL, "  ").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    /**
     * Traegt bereits ein anderes Profil die Rolle ADMIN, meldet der Bootstrap das
     * verstaendlich - statt am partiellen Unique-Index zu scheitern, dessen Meldung den
     * Index nennt und nicht die Ursache.
     */
    @Test
    void andererVorhandenerAdminBrichtDenStartAb() {
        kontoEntfernen();

        assertThatThrownBy(() ->
                bootstrap("Beispielspieler 01", TEST_EMAIL, "passwort").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Beispielspieler 12")
                .hasMessageContaining("Beispielspieler 01");
    }

    /**
     * Der Abbruch wegen eines vorhandenen Admins greift <b>vor</b> dem Anlegen. Sonst
     * bliebe bei jedem fehlgeschlagenen Start ein neues Profil zurueck - und beim zweiten
     * Versuch stuende der Name bereits belegt im Weg.
     */
    @Test
    void beiVorhandenemAdminWirdKeinProfilAngelegt() {
        kontoEntfernen();

        assertThatThrownBy(() ->
                bootstrap(NEUER_NAME, TEST_EMAIL, "passwort").run(null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(spielerRepository.findByNameIgnoreCase(NEUER_NAME))
                .as("Vor dem Abbruch darf kein Profil entstanden sein")
                .isEmpty();
    }

    // ------------------------------------------------------------------ Anlegen

    /**
     * Der Erfolgsfall: Das genannte Profil erhaelt die Rolle ADMIN, und das Konto entsteht
     * mit gehashtem Passwort.
     */
    @Test
    void gueltigeAngabenSetzenRolleUndLegenDasKontoAn() {
        kontoEntfernen();
        jdbc.update("UPDATE profil.spieler SET rolle = 'USER' WHERE rolle = 'ADMIN'");

        bootstrap("Beispielspieler 05", TEST_EMAIL, "start-passwort").run(null);

        // Bewusst ueber das Repository gelesen und nicht per JdbcTemplate: Der Runner legt
        // das Konto ueber JPA an, und ein `persist` schreibt nicht sofort in die Datenbank -
        // ein direkter SQL-Zugriff saehe die Zeile innerhalb derselben Transaktion je nach
        // Flush-Zeitpunkt noch nicht.
        AdminKonto konto = adminKontoRepository.findById(ADMIN_KONTO_ID).orElseThrow();

        Long spielerId = spielerRepository.findByNameIgnoreCase("Beispielspieler 05")
                .orElseThrow().getId();

        assertThat(konto.getSpielerId()).isEqualTo(spielerId);
        assertThat(konto.getEmail()).isEqualTo(TEST_EMAIL);
        assertThat(konto.getPasswortGeaendertAm()).isNotNull();
        assertThat(passwortEncoder.matches("start-passwort", konto.getPasswortHash()))
                .as("Das Passwort darf nur als BCrypt-Hash gespeichert werden")
                .isTrue();
        assertThat(konto.getPasswortHash()).doesNotContain("start-passwort");

        assertThat(spielerRepository.findById(spielerId).orElseThrow().getRolle())
                .isEqualTo(Rolle.ADMIN);
    }

    /**
     * Existiert kein Profil mit diesem Namen, legt der Bootstrap es an - statt abzubrechen.
     *
     * <p>Genau das war vorher das Henne-Ei-Problem: Auf einer frischen Datenbank ist
     * {@code profil.spieler} leer, {@code ADMIN_NAME} konnte dort nie passen, und der
     * Erststart war ein Zweischritt aus "Profile einspielen" und "starten".
     *
     * <p><b>Die Skillwerte stehen auf 0</b>, je aktiver Kategorie eine Zeile. Sie sind
     * fachlich bedeutungslos - das Adminprofil ist ein technisches Konto und wird nie
     * eingeteilt -, machen das Profil aber vollstaendig.
     */
    @Test
    void unbekannterNameLegtDasProfilAn() {
        kontoEntfernen();
        jdbc.update("UPDATE profil.spieler SET rolle = 'USER' WHERE rolle = 'ADMIN'");

        bootstrap(NEUER_NAME, TEST_EMAIL, "start-passwort").run(null);

        Spieler admin = spielerRepository.findByNameIgnoreCase(NEUER_NAME).orElseThrow();

        assertThat(admin.getRolle()).isEqualTo(Rolle.ADMIN);
        assertThat(admin.isAktiv()).isTrue();
        assertThat(admin.getErstelltAm()).isNotNull();

        AdminKonto konto = adminKontoRepository.findById(ADMIN_KONTO_ID).orElseThrow();
        assertThat(konto.getSpielerId()).isEqualTo(admin.getId());

        Integer kategorien = jdbc.queryForObject(
                "SELECT count(*) FROM profil.skill_kategorie WHERE aktiv", Integer.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler_skill WHERE spieler_id = ?",
                Integer.class, admin.getId()))
                .as("Je aktiver Kategorie eine Zeile - die Liste kommt aus der Datenbank")
                .isEqualTo(kategorien);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler_skill WHERE spieler_id = ? AND wert <> 0",
                Integer.class, admin.getId()))
                .as("Das Adminprofil spielt nicht mit; die Werte sind bedeutungslos und stehen auf 0")
                .isZero();
    }

    /** Der Name wird ohne Ruecksicht auf Gross- und Kleinschreibung gesucht. */
    @Test
    void abweichendeSchreibweiseDesNamensGenuegt() {
        kontoEntfernen();
        jdbc.update("UPDATE profil.spieler SET rolle = 'USER' WHERE rolle = 'ADMIN'");

        bootstrap("beispielspieler 05", TEST_EMAIL, "start-passwort").run(null);

        assertThat(adminKontoRepository.existsById(ADMIN_KONTO_ID)).isTrue();
    }

    // ------------------------------------------------------------------ Hilfsmittel

    /**
     * Entfernt das beim Kontextstart angelegte Konto. Die Aenderung wird mit der
     * Test-Transaktion zurueckgerollt.
     */
    private void kontoEntfernen() {
        jdbc.update("DELETE FROM profil.admin_konto WHERE id = 1");
    }

    private AdminBootstrap bootstrap(String name, String email, String passwort) {
        return new AdminBootstrap(adminKontoRepository, spielerRepository, passwortEncoder,
                name, email, passwort);
    }
}
