package de.fubo.appserver.service.config;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die Abbildung von {@code configs.app_config} auf {@link AppConfig}.
 *
 * <p>Dieser Test ist kein Selbstzweck: {@code ddl-auto=validate} vergleicht nur
 * Spaltenexistenz und JDBC-Typcode. Zwei vertauschte Spalten gleichen Typs - etwa
 * {@code min_teilnehmer} und {@code max_teilnehmer} oder die beiden Session-Timer -
 * faenden dabei nicht auf. Der Abgleich gegen die Seed-Defaults aus {@code V007}
 * schliesst diese Luecke.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ConfigServiceTests {

    @Autowired
    private ConfigService configService;

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
        assertThat(cfg.getAnzTeamGenerator()).isEqualTo((short) 1);

        // Zwei-Timer-Modell (A14)
        assertThat(cfg.getSessionLeerlaufMinuten()).isEqualTo((short) 15);
        assertThat(cfg.getSessionMaximalStunden()).isEqualTo((short) 1);

        // Hallenmodus (A23) - die beiden Textfelder sind im Seed nicht belegt
        assertThat(cfg.getHalleEmail()).isNull();
        assertThat(cfg.getHalleAbsageVorlage()).isNull();
        assertThat(cfg.getHalleVorlaufStunden()).isEqualTo((short) 48);

        // Aenderungsverfolgung
        assertThat(cfg.getGeaendertVon()).isNull();
        assertThat(cfg.getGeaendertAm()).isNotNull();
        assertThat(cfg.getVersion()).isZero();
    }

    /** Der Enum-Wert muss sich aus der Textspalte zurueckuebersetzen lassen. */
    @Test
    void algorithmusWirdAlsEnumAbgebildet() {
        assertThat(AlgorithmType.valueOf("EXHAUSTIV")).isEqualTo(AlgorithmType.EXHAUSTIV);
        assertThat(AlgorithmType.valueOf("HEURISTIK")).isEqualTo(AlgorithmType.HEURISTIK);
        assertThat(AlgorithmType.values()).hasSize(2);
    }
}
