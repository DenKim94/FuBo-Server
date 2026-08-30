package de.fubo.appserver.database;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft, dass die Flyway-Migrationen vollstaendig durchlaufen und die
 * Referenzdaten wie spezifiziert vorliegen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional      // jeder Test wird nach dem Durchlauf zurueckgerollt
class MigrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    /** Alle drei Schemas muessen nach dem Start existieren. */
    @Test
    void schemasSindAngelegt() {
        var schemas = jdbc.queryForList("""
                SELECT schema_name FROM information_schema.schemata
                 WHERE schema_name IN ('profil', 'spieltag', 'configs')
                """, String.class);
        assertThat(schemas).containsExactlyInAnyOrder("profil", "spieltag", "configs");
    }

    /** Der Seed muss genau fuenf Kategorien mit den vorgegebenen Gewichten liefern. */
    @Test
    void skillKategorienSindGeseedet() {
        Integer anzahl = jdbc.queryForObject(
                "SELECT count(*) FROM profil.skill_kategorie", Integer.class);
        assertThat(anzahl).isEqualTo(5);

        var gewicht = jdbc.queryForObject(
                "SELECT gewicht FROM profil.skill_kategorie WHERE schluessel = 'TORWART'",
                java.math.BigDecimal.class);
        assertThat(gewicht).isEqualByComparingTo("0.30");
    }

    /**
     * Der partielle Unique-Index laesst nur einen einzigen Admin zu.
     *
     * <p><b>Warum die Rolle zuerst entzogen wird:</b> Seit Abschnitt 9 legt der
     * {@code AdminBootstrap} beim Start des Testkontexts ein Admin-Konto an und hebt dabei
     * ein Demoprofil auf die Rolle {@code ADMIN}. Ohne das {@code UPDATE} scheiterte
     * bereits das erste {@code INSERT} - der Test wuerde dann zwar weiterhin gruen sein,
     * aber die falsche Aussage pruefen. Die Aenderung wird mit der Test-Transaktion
     * zurueckgerollt.
     */
    @Test
    void zweiterAdminWirdAbgelehnt() {
        jdbc.update("UPDATE profil.spieler SET rolle = 'USER' WHERE rolle = 'ADMIN'");

        jdbc.update("INSERT INTO profil.spieler (name, rolle) VALUES ('Testspieler A', 'ADMIN')");

        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO profil.spieler (name, rolle) VALUES ('Testspieler B', 'ADMIN')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** Der Trigger erzwingt den kategoriespezifischen Wertebereich (Torwart 0..3). */
    @Test
    void torwartWertUeberDreiWirdAbgelehnt() {
        Long id = jdbc.queryForObject(
                "INSERT INTO profil.spieler (name) VALUES ('Testspieler C') RETURNING id", Long.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO profil.spieler_skill (spieler_id, kategorie, wert) VALUES (?, 'TORWART', 5)", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code V009} ergaenzt {@code auswechsel_modus} mit Vorgabe und CHECK-Constraint (A20b).
     *
     * <p>Der Default steht in der Spalte, nicht nur im Java-Enum: {@code V007} hat die Zeile
     * bereits angelegt, ein {@code ALTER TABLE ... NOT NULL} ohne {@code DEFAULT} waere daran
     * gescheitert. Geprueft wird deshalb beides - dass die bestehende Zeile den Vorgabewert
     * traegt und dass die Datenbank einen Fremdwert ablehnt.
     *
     * <p>Der zweite Teil ist der wichtigere: Er belegt, dass {@code ck_app_config_auswechsel}
     * wirklich angelegt wurde. Ohne ihn faende ein Schreibzugriff aus {@code psql} oder einem
     * spaeteren Skript keinen Widerstand, und die Anwendung braeche erst beim naechsten Lesen -
     * dann mit einer Ausnahme aus dem Enum-Mapping statt an der Stelle des Fehlers.
     */
    @Test
    void auswechselModusHatVorgabeUndCheckConstraint() {
        String vorgabe = jdbc.queryForObject(
                "SELECT auswechsel_modus FROM configs.app_config WHERE id = 1", String.class);
        assertThat(vorgabe).isEqualTo("SCHWAECHSTER_UEBERZAHL");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE configs.app_config SET auswechsel_modus = 'IRGENDWER' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * NULLS NOT DISTINCT: Zwei Kontingentzeilen mit demselben Gast-Slot kollidieren,
     * obwohl akteur_spieler_id in beiden Zeilen NULL ist.
     */
    @Test
    void kontingentSchluesselIstAuchMitNullEindeutig() {
        Long terminId = jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit)
                VALUES (DATE '2099-01-01', TIME '20:00')
                RETURNING id
                """, Long.class);

        String einfuegen = """
                INSERT INTO spieltag.generierung_kontingent
                       (termin_id, akteur_spieler_id, akteur_gast_slot_id, teilnehmer_version, anzahl)
                VALUES (?, NULL, 1, 0, 1)
                """;

        jdbc.update(einfuegen, terminId);

        assertThatThrownBy(() -> jdbc.update(einfuegen, terminId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** Beispielprofile mit je fuenf Skillwerten, keine Dummy-Vorlage als Spieler. */
    @Test
    void beispielprofileSindVollstaendigGeseedet() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profil.spieler", Integer.class))
                .isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profil.spieler_skill", Integer.class))
                .isEqualTo(60);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler WHERE name LIKE 'Dummy%'", Integer.class))
                .isZero();
    }

    /** Der Torwart-Wertebereich 0..3 gilt auch fuer die Beispieldaten. */
    @Test
    void torwartWerteLiegenImGueltigenBereich() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM profil.spieler_skill
             WHERE kategorie = 'TORWART' AND wert NOT BETWEEN 0 AND 3
            """, Integer.class))
                .isZero();
    }
}