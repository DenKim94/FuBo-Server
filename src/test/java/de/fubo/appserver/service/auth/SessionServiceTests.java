package de.fubo.appserver.service.auth;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Session;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.utils.TokenGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft das Zwei-Timer-Modell aus {@code S2_UMSETZUNG.md}, Abschnitt 3.
 *
 * <p>Fuer die Zeitpunkte werden bewusst keine {@code Thread.sleep}-Konstruktionen
 * verwendet, sondern die Ablaufspalten direkt per {@link JdbcTemplate} gesetzt. Das ist
 * schnell und deterministisch: PostgreSQL liefert innerhalb einer Transaktion fuer
 * {@code now()} stets denselben Wert (transaction_timestamp), Test und Produktionscode
 * rechnen also gegen dieselbe Uhr.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional      // jeder Test wird nach dem Durchlauf zurueckgerollt
class SessionServiceTests {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ Anlegen

    /** Der Klartext-Token darf nirgends in der Datenbank auftauchen, nur sein Hash. */
    @Test
    void nurDerHashWirdGespeichert() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);

        assertThat(token).hasSize(43);   // 32 Byte, Base64url ohne Padding

        Integer mitKlartext = jdbc.queryForObject(
                "SELECT count(*) FROM profil.session WHERE token_hash = ?", Integer.class, token);
        assertThat(mitKlartext).isZero();

        Integer mitHash = jdbc.queryForObject(
                "SELECT count(*) FROM profil.session WHERE token_hash = ?",
                Integer.class, TokenGenerator.hash(token));
        assertThat(mitHash).isEqualTo(1);
    }

    /**
     * Eine Sitzung in PIN_VERIFIED hat noch keine Identitaet und keine Rolle.
     * Das ist der Fall, den Migration V008 ueberhaupt erst moeglich macht.
     */
    @Test
    void sitzungInPinVerifiedHatKeineRolle() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);

        Optional<AktiveSitzung> sitzung = sessionService.pruefenUndVerlaengern(token);

        assertThat(sitzung).isPresent();
        assertThat(sitzung.get().stage()).isEqualTo(Stage.PIN_VERIFIED);
        assertThat(sitzung.get().rolle()).isNull();
        assertThat(sitzung.get().spielerId()).isNull();
    }

    // ------------------------------------------------------- Pruefen und Verlaengern

    /** Ein unbekannter Token liefert kein Ergebnis - ohne Unterscheidung des Grundes. */
    @Test
    void unbekannterTokenWirdAbgelehnt() {
        assertThat(sessionService.pruefenUndVerlaengern("gibt-es-nicht")).isEmpty();
        assertThat(sessionService.pruefenUndVerlaengern(null)).isEmpty();
        assertThat(sessionService.pruefenUndVerlaengern("  ")).isEmpty();
    }

    /** Das gleitende Fenster wird bei jedem Zugriff nach hinten geschoben. */
    @Test
    void leerlauffensterWirdVerlaengert() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
        String hash = TokenGenerator.hash(token);

        // Fenster kuenstlich auf eine Minute verkuerzen, damit die Verlaengerung
        // eindeutig messbar ist (Default-Leerlauf: 15 Minuten).
        jdbc.update("UPDATE profil.session SET gueltig_bis = now() + interval '1 minute' "
                + "WHERE token_hash = ?", hash);

        assertThat(sessionService.pruefenUndVerlaengern(token)).isPresent();

        Boolean verlaengert = jdbc.queryForObject(
                "SELECT gueltig_bis > now() + interval '10 minutes' FROM profil.session "
                        + "WHERE token_hash = ?", Boolean.class, hash);
        assertThat(verlaengert).isTrue();
    }

    /**
     * LEAST(...) deckelt das Leerlauf-Fenster an der harten Obergrenze. Ohne den Deckel
     * wanderte gueltig_bis ueber absolut_gueltig_bis hinaus und die Daten waeren
     * widerspruechlich.
     */
    @Test
    void leerlauffensterUeberschreitetDieHarteObergrenzeNicht() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
        String hash = TokenGenerator.hash(token);

        // Obergrenze auf 5 Minuten setzen - kuerzer als der Leerlauf von 15 Minuten.
        jdbc.update("UPDATE profil.session "
                + "   SET gueltig_bis = now() + interval '1 minute', "
                + "       absolut_gueltig_bis = now() + interval '5 minutes' "
                + " WHERE token_hash = ?", hash);

        assertThat(sessionService.pruefenUndVerlaengern(token)).isPresent();

        Boolean gedeckelt = jdbc.queryForObject(
                "SELECT gueltig_bis = absolut_gueltig_bis FROM profil.session WHERE token_hash = ?",
                Boolean.class, hash);
        assertThat(gedeckelt).isTrue();
    }

    /** Erster Timer: abgelaufenes Leerlauf-Fenster. */
    @Test
    void abgelaufenesLeerlauffensterWirdAbgelehnt() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);

        jdbc.update("UPDATE profil.session SET gueltig_bis = now() - interval '1 second' "
                + "WHERE token_hash = ?", TokenGenerator.hash(token));

        assertThat(sessionService.pruefenUndVerlaengern(token)).isEmpty();
    }

    /** Zweiter Timer: die harte Obergrenze laesst sich nicht verlaengern. */
    @Test
    void ueberschritteneHarteObergrenzeWirdAbgelehnt() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);

        jdbc.update("UPDATE profil.session "
                + "   SET gueltig_bis = now() + interval '10 minutes', "
                + "       absolut_gueltig_bis = now() - interval '1 second' "
                + " WHERE token_hash = ?", TokenGenerator.hash(token));

        assertThat(sessionService.pruefenUndVerlaengern(token)).isEmpty();
    }

    /** Eine widerrufene Sitzung wird sofort abgelehnt - der Grund fuer den opaken Token. */
    @Test
    void widerrufeneSitzungWirdAbgelehnt() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
        Long id = sitzungsIdZu(token);

        sessionService.widerrufen(id);

        assertThat(sessionService.pruefenUndVerlaengern(token)).isEmpty();
    }

    // ------------------------------------------------------------------ Rotation

    /** Nach der Rotation gilt nur noch der neue Token; die Sitzungs-Id bleibt erhalten. */
    @Test
    void rotationErsetztDenTokenUndBehaeltDieId() {
        String alterToken = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
        Long id = sitzungsIdZu(alterToken);

        String neuerToken = sessionService.rotieren(id);

        assertThat(neuerToken).isNotEqualTo(alterToken);
        assertThat(sessionService.pruefenUndVerlaengern(alterToken)).isEmpty();

        Optional<AktiveSitzung> sitzung = sessionService.pruefenUndVerlaengern(neuerToken);
        assertThat(sitzung).isPresent();
        assertThat(sitzung.get().id()).isEqualTo(id);
    }

    /** Eine unbekannte Sitzung zu rotieren ist ein Programmierfehler, kein Normalfall. */
    @Test
    void rotationEinerUnbekanntenSitzungSchlaegtFehl() {
        assertThatThrownBy(() -> sessionService.rotieren(-1L))
                .isInstanceOf(IllegalStateException.class);
    }

    // --------------------------------------------------------- Identitaet und Belegung

    /** Namensbelegung (A6): Der Status ergibt sich aus den aktiven Sitzungen. */
    @Test
    void aktiveSitzungMachtDenNamenBelegt() {
        Long spielerId = ersterSpieler();

        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId, Rolle.USER);
        assertThat(sessionRepository.existiertAktiveSitzungFuer(spielerId)).isTrue();

        sessionService.widerrufen(sitzungsIdZu(token));
        assertThat(sessionRepository.existiertAktiveSitzungFuer(spielerId)).isFalse();
    }

    /** Stufenwechsel: aus PIN_VERIFIED wird PROFILE_AUTHENTICATED mit Identitaet. */
    @Test
    void stufenwechselSetztIdentitaetUndRolle() {
        Long spielerId = ersterSpieler();
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
        Long id = sitzungsIdZu(token);

        int geaendert = sessionRepository.aufProfileAuthenticatedSetzen(
                id, spielerId, Rolle.USER.name());
        assertThat(geaendert).isEqualTo(1);

        Optional<AktiveSitzung> sitzung = sessionService.pruefenUndVerlaengern(token);
        assertThat(sitzung).isPresent();
        assertThat(sitzung.get().stage()).isEqualTo(Stage.PROFILE_AUTHENTICATED);
        assertThat(sitzung.get().rolle()).isEqualTo(Rolle.USER);
        assertThat(sitzung.get().spielerId()).isEqualTo(spielerId);
    }

    /**
     * Gegenprobe zu V008: In der zweiten Stufe ist die Rolle Pflicht.
     * Die verletzende Anweisung steht bewusst am Ende - danach ist die Transaktion
     * als "rollback only" markiert und laesst keine weiteren Anweisungen mehr zu.
     */
    @Test
    void profileAuthenticatedOhneRolleWirdAbgelehnt() {
        Long spielerId = ersterSpieler();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO profil.session
                       (token_hash, spieler_id, stage, gueltig_bis, absolut_gueltig_bis)
                VALUES (?, ?, 'PROFILE_AUTHENTICATED', now() + interval '15 minutes',
                        now() + interval '1 hour')
                """, TokenGenerator.hash("beliebig"), spielerId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ Aufraeumen

    /** Der Aufraeumjob entfernt nur Sitzungen jenseits der Aufbewahrungsfrist. */
    @Test
    void aufraeumjobEntferntNurAlteSitzungen() {
        String aktuellerToken = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);

        jdbc.update("""
                INSERT INTO profil.session
                       (token_hash, stage, gueltig_bis, absolut_gueltig_bis)
                VALUES (?, 'PIN_VERIFIED', now() - interval '40 days', now() - interval '40 days')
                """, TokenGenerator.hash("alte-sitzung"));

        sessionService.alteSitzungenEntfernen();

        Integer alte = jdbc.queryForObject(
                "SELECT count(*) FROM profil.session WHERE token_hash = ?",
                Integer.class, TokenGenerator.hash("alte-sitzung"));
        assertThat(alte).isZero();

        Integer aktuelle = jdbc.queryForObject(
                "SELECT count(*) FROM profil.session WHERE token_hash = ?",
                Integer.class, TokenGenerator.hash(aktuellerToken));
        assertThat(aktuelle).isEqualTo(1);
    }

    // ------------------------------------------------------------------ Hilfsmittel

    /** Liefert die Id der zum Token gehoerenden Sitzung. */
    private Long sitzungsIdZu(String token) {
        return sessionRepository.findByTokenHash(TokenGenerator.hash(token))
                .map(Session::getId)
                .orElseThrow(() -> new AssertionError("Sitzung zum Token nicht gefunden"));
    }

    /** Liefert eine beliebige Profil-Id aus den Demodaten (keine realen Namen). */
    private Long ersterSpieler() {
        return jdbc.queryForObject(
                "SELECT id FROM profil.spieler ORDER BY id LIMIT 1", Long.class);
    }
}
