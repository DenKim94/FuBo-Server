package de.fubo.appserver.service.config;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.dto.admin.KonfigurationAendernRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft die Abbildung von {@code configs.app_config} auf {@link AppConfig} und die
 * Aenderungsverfolgung beim Schreiben (S3, Abschnitt 5).
 *
 * <p>Dieser Test ist kein Selbstzweck: {@code ddl-auto=validate} vergleicht nur
 * Spaltenexistenz und JDBC-Typcode. Zwei vertauschte Spalten gleichen Typs - etwa
 * {@code min_teilnehmer} und {@code max_teilnehmer} oder die beiden Session-Timer -
 * faenden dabei nicht auf. Der Abgleich gegen die Seed-Defaults aus {@code V007}
 * schliesst diese Luecke.
 *
 * <p>Das <b>Verhalten</b> der Endpunkte - Voll-Update, Plausibilitaeten, Gastplaetze, Protokoll -
 * steht in {@code KonfigurationControllerTests}. Hier bleibt, was ohne HTTP zu pruefen ist.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ConfigServiceTests {

    /** Adresse aus dem Dokumentationsbereich nach RFC 5737. */
    private static final String TEST_IP = "203.0.113.9";

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Alle Felder muessen die in V004 definierten Defaults liefern. */
    @Test
    void seedDefaultsWerdenVollstaendigGelesen() {
        AppConfig cfg = configService.lesen();

        assertThat(cfg.getId()).isEqualTo((short) 1);

        // Teilnehmer (A10/A11) - Reihenfolge absichtlich getrennt geprueft
        assertThat(cfg.getMinTeilnehmer()).isEqualTo((short) 6);
        assertThat(cfg.getMaxTeilnehmer()).isEqualTo((short) 22);
        assertThat(cfg.getAnzGuests()).isEqualTo((short) 4);

        // Teamgenerator (A15)
        assertThat(cfg.getAlgorithmType()).isEqualTo(AlgorithmType.EXHAUSTIV);
        assertThat(cfg.getAuswechselModus()).isEqualTo(AuswechselModus.SCHWAECHSTER_UEBERZAHL);
        assertThat(cfg.getAnzTeamGenerator()).isEqualTo((short) 1);

        // Zwei-Timer-Modell (A14)
        assertThat(cfg.getSessionLeerlaufMinuten()).isEqualTo((short) 15);
        assertThat(cfg.getSessionMaximalStunden()).isEqualTo((short) 1);

        // Hallenmodus (A23) - die Adresse ist installationsabhaengig und bleibt leer,
        // die Vorlage traegt seit V010 einen Vorgabetext
        assertThat(cfg.getHalleEmail()).isNull();
        assertThat(cfg.getHalleAbsageVorlage())
                .isNotNull();
        assertThat(cfg.getHalleVorlaufStunden()).isEqualTo((short) 48);

        // Aenderungsverfolgung
        assertThat(cfg.getGeaendertVon()).isNull();
        assertThat(cfg.getGeaendertAm()).isNotNull();
        assertThat(cfg.getVersion()).isZero();
    }

    /**
     * Die Enum-Werte muessen sich aus der Textspalte zurueckuebersetzen lassen.
     *
     * <p>Geprueft wird zugleich die Anzahl. Ein dritter Wert im Java-Enum ohne Nachzug des
     * CHECK-Constraints ({@code ck_app_config_algo}, {@code ck_app_config_auswechsel}) liefe
     * beim Lesen fehlerfrei und schlage erst beim Schreiben fehl - dort dann als {@code 500}
     * mit einem Constraint-Namen statt einer Meldung. Der Fall haelt beide Seiten zusammen.
     */
    @Test
    void enumWerteWerdenAusDerTextspalteAbgebildet() {
        assertThat(AlgorithmType.valueOf("EXHAUSTIV")).isEqualTo(AlgorithmType.EXHAUSTIV);
        assertThat(AlgorithmType.valueOf("HEURISTIK")).isEqualTo(AlgorithmType.HEURISTIK);
        assertThat(AlgorithmType.values()).hasSize(2);

        assertThat(AuswechselModus.valueOf("SCHWAECHSTER_UEBERZAHL"))
                .isEqualTo(AuswechselModus.SCHWAECHSTER_UEBERZAHL);
        assertThat(AuswechselModus.valueOf("ZULETZT_ANGEMELDET"))
                .isEqualTo(AuswechselModus.ZULETZT_ANGEMELDET);
        assertThat(AuswechselModus.values()).hasSize(2);
    }

    /**
     * Jeder Speichervorgang fuellt {@code geaendert_von} und schiebt {@code geaendert_am} vor -
     * auch dann, wenn sich kein fachlicher Wert aendert.
     *
     * <p>{@code geaendert_von} verweist ueber {@code fk_app_config_admin} auf
     * {@code profil.admin_konto}, nicht auf {@code profil.spieler}: Dort steht die 1, waehrend im
     * Audit-Log die Profil-Id des Handelnden steht. Die beiden Zahlen sind verschieden und werden
     * leicht verwechselt - deshalb dieser Fall.
     */
    @Test
    void aktualisierenSetztGeaendertVonUndGeaendertAm() {
        AppConfig bestand = configService.lesen();
        OffsetDateTime alterStand = bestand.getGeaendertAm();

        configService.aktualisieren(anfrageAus(bestand), adminSpielerId(), TEST_IP);

        Short geaendertVon = jdbc.queryForObject(
                "SELECT geaendert_von FROM configs.app_config WHERE id = 1", Short.class);
        OffsetDateTime neuerStand = jdbc.queryForObject(
                "SELECT geaendert_am FROM configs.app_config WHERE id = 1", OffsetDateTime.class);

        assertThat(geaendertVon).isEqualTo((short) 1);
        assertThat(neuerStand).isAfter(alterStand);
    }

    /**
     * Aendert sich die Zeile zwischen Vergleich und Schreibvorgang, meldet Hibernate den
     * Sperrkonflikt - und zwar als {@link ObjectOptimisticLockingFailureException}.
     *
     * <p><b>Das ist der Grund, aus dem der Versionsvergleich im Dienst allein nicht genuegt.</b>
     * Er liest, der Schreibvorgang schreibt, und dazwischen liegt ein Fenster. Hier wird es
     * kuenstlich geoeffnet: Die Entity liegt bereits im Persistence-Context, ein direkter
     * {@code UPDATE} erhoeht die Version daneben, und der Vergleich im Dienst sieht davon nichts.
     *
     * <p>Dass daraus ein {@code 409} und kein {@code 500} wird, haengt am Handler im
     * {@code GlobalExceptionHandler} - geprueft in {@code KonfigurationControllerTests}. Hier
     * steht nur die Zusicherung, auf die sich dieser Handler stuetzt: dass der Dienst genau diese
     * Ausnahme durchreicht und sie nicht selbst wegfaengt.
     */
    @Test
    void sperrkonfliktBeimSchreibenWirdDurchgereicht() {
        AppConfig bestand = configService.lesen();
        KonfigurationAendernRequest anfrage = anfrageAus(bestand);
        Long adminSpielerId = adminSpielerId();

        jdbc.update("UPDATE configs.app_config SET version = version + 1 WHERE id = 1");

        assertThatThrownBy(() -> configService.aktualisieren(anfrage, adminSpielerId, TEST_IP))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /**
     * Baut eine Anfrage, die den Bestand unveraendert zurueckschickt.
     *
     * <p>Genau der Ablauf des Vertrags: lesen, gegebenenfalls einzelne Werte ersetzen, das Ganze
     * zurueckschicken.
     */
    private static KonfigurationAendernRequest anfrageAus(AppConfig bestand) {
        return new KonfigurationAendernRequest(
                bestand.getVersion(),
                bestand.getMinTeilnehmer(),
                bestand.getMaxTeilnehmer(),
                bestand.getAnzGuests(),
                bestand.getAlgorithmType(),
                bestand.getAuswechselModus(),
                bestand.getAnzTeamGenerator(),
                bestand.getSessionLeerlaufMinuten(),
                bestand.getSessionMaximalStunden(),
                bestand.getHalleEmail(),
                bestand.getHalleAbsageVorlage(),
                bestand.getHalleVorlaufStunden());
    }

    /** Profil-Id des Admins - Fremdschluessel des Audit-Eintrags. */
    private Long adminSpielerId() {
        return jdbc.queryForObject(
                "SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }
}
