package de.fubo.appserver.database;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

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
     * {@code V011} legt die Bilanz eines Spielers an: drei Zaehler, alle bei null (A21).
     *
     * <p>Geprueft wird der Startwert <b>und</b> die Untergrenze. Der Startwert, weil ein
     * {@code ALTER TABLE ... NOT NULL} ohne {@code DEFAULT} an den bestehenden Zeilen
     * gescheitert waere - und die Demodaten legen welche an. Die Untergrenze, weil ein
     * negativer Zaehler nur durch einen Fehler entstehen kann: Die Umsetzung in S6 berechnet
     * die Werte neu, statt sie fortzuschreiben, ein Unterlauf ist damit ausgeschlossen. Der
     * Constraint ist der Riegel gegen eine spaetere Umstellung auf {@code +1}/{@code -1},
     * nicht gegen den geplanten Weg.
     */
    @Test
    void bilanzStartetBeiNullUndBleibtNichtNegativ() {
        Long id = jdbc.queryForObject(
                "INSERT INTO profil.spieler (name) VALUES ('Testspieler D') RETURNING id", Long.class);

        Map<String, Object> bilanz = jdbc.queryForMap("""
                SELECT anz_siege, anz_niederlagen, anz_unentschieden
                  FROM profil.spieler WHERE id = ?
                """, id);
        assertThat(bilanz.values()).containsOnly(0);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE profil.spieler SET anz_siege = -1 WHERE id = ?", id))
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
        assertThat(vorgabe).as("seit V017").isEqualTo("ZUFAELLIG");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE configs.app_config SET auswechsel_modus = 'IRGENDWER' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code V017}, erster Teil: Der fruehere Wert {@code SCHWAECHSTER_UEBERZAHL} ist nicht mehr
     * zulaessig, und der Spaltenvorgabewert ist der neue (A20b, 03.11.2026).
     *
     * <p>Der alte Wert kaeme sonst ueber {@code psql} zurueck, und die Anwendung braeche beim
     * naechsten Lesen am Enum. Ein alter Vorgabewert liesse eine neu angelegte Zeile am CHECK
     * scheitern. <b>Die scheiternde Anweisung steht zuletzt</b>: Danach ist die Transaktion in
     * PostgreSQL abgebrochen und nimmt keine Abfrage mehr an.
     */
    @Test
    void v017LehntDenFrueherenModusAb() {
        assertThat(jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                 WHERE table_schema = 'configs' AND table_name = 'app_config'
                   AND column_name = 'auswechsel_modus'
                """, String.class)).startsWith("'ZUFAELLIG'");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE configs.app_config SET auswechsel_modus = 'SCHWAECHSTER_UEBERZAHL' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code V017}, zweiter Teil: {@code team_zuteilung.springer} steht auf {@code false}, und
     * je Lauf gibt es hoechstens einen Springer ({@code uq_team_zuteilung_springer}).
     *
     * <p>Ein zweiter Springer liesse die Einzelansicht zufaellig einen von beiden nennen. Ein
     * {@code CHECK} kann das nicht verhindern, weil die Bedingung mehrere Zeilen spannt - daher
     * der partielle Index, und daher dieser Fall.
     */
    @Test
    void v017ErlaubtHoechstensEinenSpringerJeLauf() {
        Long terminId = jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit)
                VALUES (DATE '2099-01-02', TIME '20:00')
                RETURNING id
                """, Long.class);
        Long generierungId = jdbc.queryForObject("""
                INSERT INTO spieltag.team_generierung
                       (termin_id, teilnehmer_version, seed, erzeugt_von_bezeichnung)
                VALUES (?, 0, 1, 'Pruefspieler M')
                RETURNING id
                """, Long.class, terminId);
        Long ohneAngabe = gastTeilnahme(terminId, "Testgast M1");
        Long ersterSpringer = gastTeilnahme(terminId, "Testgast M2");
        Long zweiterSpringer = gastTeilnahme(terminId, "Testgast M3");

        jdbc.update("""
                INSERT INTO spieltag.team_zuteilung
                       (generierung_id, teilnahme_id, team, score_snapshot, skills_snapshot)
                VALUES (?, ?, 'B', 0, '{}'::jsonb)
                """, generierungId, ohneAngabe);
        assertThat(jdbc.queryForObject("""
                SELECT springer FROM spieltag.team_zuteilung
                 WHERE generierung_id = ? AND teilnahme_id = ?
                """, Boolean.class, generierungId, ohneAngabe)).as("Vorgabe").isFalse();

        String springer = """
                INSERT INTO spieltag.team_zuteilung
                       (generierung_id, teilnahme_id, team, springer, score_snapshot, skills_snapshot)
                VALUES (?, ?, 'A', true, 0, '{}'::jsonb)
                """;
        jdbc.update(springer, generierungId, ersterSpringer);
        assertThatThrownBy(() -> jdbc.update(springer, generierungId, zweiterSpringer))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private Long gastTeilnahme(Long terminId, String gastName) {
        return jdbc.queryForObject("""
                INSERT INTO spieltag.teilnahme (termin_id, gast_name, zusage)
                VALUES (?, ?, true)
                RETURNING id
                """, Long.class, terminId, gastName);
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
                       (termin_id, akteur_spieler_id, akteur_gast_slot_id, kontingent_version, anzahl)
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