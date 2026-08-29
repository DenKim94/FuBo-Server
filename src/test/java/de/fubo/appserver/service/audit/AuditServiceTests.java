package de.fubo.appserver.service.audit;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.audit.AuditAktion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft das Audit-Log: Feldabbildung, Transaktionskopplung und Aufbewahrungsfrist.
 *
 * <p><b>Diese Klasse traegt bewusst kein {@code @Transactional}.</b> Zwei der Faelle
 * pruefen gerade das Transaktionsverhalten - lieferte der Test selbst eine umgebende
 * Transaktion, waere das Ergebnis vorbestimmt und die Aussage wertlos. Die entstehenden
 * Zeilen werden deshalb nach jedem Fall von Hand entfernt; sie tragen dazu ein
 * erkennbares Praefix in {@code akteur_bezeichnung}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditServiceTests {

    /** Praefix, an dem der Aufraeumschritt die Zeilen dieses Tests erkennt. */
    private static final String PRAEFIX = "Pruefakteur";
    private static final String AKTEUR = PRAEFIX + " 203.0.113.77";

    @Autowired
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transaktionsverwaltung;

    private TransactionTemplate transaktion;

    @BeforeEach
    void aufbauen() {
        transaktion = new TransactionTemplate(transaktionsverwaltung);
        eigeneZeilenEntfernen();
    }

    @AfterEach
    void aufraeumen() {
        eigeneZeilenEntfernen();
    }

    // ------------------------------------------------------------------ Feldabbildung

    @Test
    void eintragWirdMitAllenFeldernGeschrieben() {
        auditService.protokolliere(null, AKTEUR, AuditAktion.PIN_FEHLVERSUCH,
                "session", 42L, Map.of("endpunkt", "/auth/pin/pruefen"));

        Map<String, Object> zeile = jdbc.queryForMap("""
                SELECT akteur_spieler_id, akteur_bezeichnung, aktion, entitaet, entitaet_id
                  FROM profil.audit_log
                 WHERE akteur_bezeichnung = ?
                """, AKTEUR);

        assertThat(zeile.get("akteur_spieler_id")).isNull();
        assertThat(zeile.get("aktion")).isEqualTo(AuditAktion.PIN_FEHLVERSUCH.name());
        assertThat(zeile.get("entitaet")).isEqualTo("session");
        assertThat(zeile.get("entitaet_id")).isEqualTo(42L);
    }

    /**
     * Die Zusatzangaben landen als {@code jsonb}, nicht als Text - sonst liesse sich
     * spaeter nicht danach filtern. Der Operator {@code ->>} liest einen Wert heraus und
     * beweist damit, dass die Spalte tatsaechlich als JSON geparst wurde.
     */
    @Test
    void detailsLandenAlsJsonInDerDatenbank() {
        auditService.protokolliere(AKTEUR, AuditAktion.PIN_GESPERRT,
                Map.of("endpunkt", "/auth/pin/pruefen"));

        String endpunkt = jdbc.queryForObject("""
                SELECT details->>'endpunkt' FROM profil.audit_log WHERE akteur_bezeichnung = ?
                """, String.class, AKTEUR);

        assertThat(endpunkt).isEqualTo("/auth/pin/pruefen");
    }

    /**
     * Eine <b>verschachtelte</b> Karte wird zu einem JSON-Objekt, nicht zu einer
     * Zeichenkette (Korrektur vom 29.08.2026).
     *
     * <p>Vorher landete sie im Textzweig des handgeschriebenen Serialisierers, und
     * {@code Map#toString} lieferte {@code "{TORWART=1}"} - etwas, das aussieht wie JSON und
     * keines ist. In der Datenbank stand dann ein <i>Text</i>: {@code details->'skills'} traf
     * zwar, aber {@code ->>'TORWART'} darauf lieferte {@code null}. Der Eintrag war
     * unbrauchbar, ohne dass irgendwo ein Fehler auftrat.
     *
     * <p>Geprueft wird deshalb zweierlei: dass der Wert herauszulesen ist <b>und</b> dass
     * {@code jsonb_typeof} tatsaechlich {@code object} meldet. Die erste Zusicherung allein
     * genuegte nicht - sie liefe auch gegen ein Objekt, das nur zufaellig richtig aussieht.
     */
    @Test
    void verschachtelteKarteLandetAlsObjektUndNichtAlsText() {
        auditService.protokolliere(AKTEUR, AuditAktion.PROFIL_GEAENDERT,
                Map.of("skills", Map.of("TORWART", 1)));

        Map<String, Object> zeile = jdbc.queryForMap("""
                SELECT details->'skills'->>'TORWART' AS torwart,
                       jsonb_typeof(details->'skills') AS typ
                  FROM profil.audit_log
                 WHERE akteur_bezeichnung = ?
                """, AKTEUR);

        assertThat(zeile.get("torwart")).isEqualTo("1");
        assertThat(zeile.get("typ"))
                .as("Ein Text hiesse hier 'string' - dann war die Karte nur stringifiziert")
                .isEqualTo("object");
    }

    // ------------------------------------------------------------ Transaktionskopplung

    /**
     * Der Kernfall der Entscheidung vom 16.08.2026: Scheitert die fachliche Aenderung,
     * darf der Protokolleintrag nicht stehen bleiben.
     *
     * <p>Mit {@code Propagation.REQUIRES_NEW} liefe der Eintrag in einer eigenen
     * Transaktion und ueberlebte den Rollback - dieser Test schlaegt dann fehl und ist
     * damit die Absicherung gegen eine spaetere, gut gemeinte Umstellung.
     */
    @Test
    void eintragWirdMitDemFehlgeschlagenenAenderungsversuchZurueckgerollt() {
        assertThatThrownBy(() -> transaktion.executeWithoutResult(status -> {
            auditService.protokolliere(AKTEUR, AuditAktion.PIN_FEHLVERSUCH, Map.of());
            throw new IllegalStateException("fachliche Aenderung schlaegt fehl");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(anzahlEigenerZeilen())
                .as("Ein Protokolleintrag darf keine Aenderung belegen, die zurueckgerollt wurde")
                .isZero();
    }

    /** Gegenprobe: Ohne Fehler wird der Eintrag mit der Transaktion festgeschrieben. */
    @Test
    void eintragWirdMitErfolgreicherTransaktionFestgeschrieben() {
        transaktion.executeWithoutResult(status ->
                auditService.protokolliere(AKTEUR, AuditAktion.PIN_FEHLVERSUCH, Map.of()));

        assertThat(anzahlEigenerZeilen()).isEqualTo(1);
    }

    // ------------------------------------------------------------- Aufbewahrungsfrist

    /**
     * Eintraege jenseits der Frist werden entfernt, juengere bleiben.
     *
     * <p>Die Zeitstempel werden direkt gesetzt statt die Frist zu verkuerzen: Der Test
     * bleibt damit unabhaengig vom konfigurierten Wert und prueft die Regel, nicht die
     * Konfiguration.
     */
    @Test
    void eintraegeJenseitsDerFristWerdenEntfernt() {
        eintragMitAlter(100, PRAEFIX + " alt");
        eintragMitAlter(10, PRAEFIX + " neu");

        auditService.alteEintraegeEntfernen();

        assertThat(anzahlFuer(PRAEFIX + " alt")).isZero();
        assertThat(anzahlFuer(PRAEFIX + " neu")).isEqualTo(1);
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /** Legt einen Eintrag mit rueckdatiertem Zeitpunkt an. */
    private void eintragMitAlter(int tage, String akteur) {
        jdbc.update("""
                INSERT INTO profil.audit_log (zeitpunkt, akteur_bezeichnung, aktion)
                     VALUES (now() - (CAST(? AS integer) * interval '1 day'), ?, ?)
                """, tage, akteur, AuditAktion.PIN_FEHLVERSUCH.name());
    }

    private Integer anzahlEigenerZeilen() {
        return anzahlFuer(AKTEUR);
    }

    private Integer anzahlFuer(String akteur) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM profil.audit_log WHERE akteur_bezeichnung = ?",
                Integer.class, akteur);
    }

    private void eigeneZeilenEntfernen() {
        jdbc.update("DELETE FROM profil.audit_log WHERE akteur_bezeichnung LIKE ?", PRAEFIX + "%");
    }
}
