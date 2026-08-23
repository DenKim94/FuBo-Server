package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.profil.SkillKategorie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Lesezugriff auf {@code profil.skill_kategorie} (S2b, Abschnitt 8).
 *
 * <p><b>Warum kein Spring-Data-Repository?</b> Die Tabelle ist Referenzdatenbestand: Sie wird
 * gelesen, nie geschrieben. Eine Entity brauchte es erst, wenn der Admin Kategorien pflegen
 * koennte - das ist frueherstens S3. Vorbild sind {@code AuditLogRepository} und
 * {@code GastSlotRepository}, die aus demselben Grund direkt {@code JdbcClient} nutzen.
 */
@Repository
public class SkillKategorieRepository {

    /**
     * Nur aktive Kategorien. Eine abgeschaltete Kategorie soll weder in einer neuen
     * Skillzeile landen noch eine Eingabe rechtfertigen.
     */
    private static final String SQL_AKTIVE = """
            SELECT schluessel, min_wert, max_wert
              FROM profil.skill_kategorie
             WHERE aktiv
             ORDER BY reihenfolge
            """;

    private final JdbcClient jdbc;

    public SkillKategorieRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Liefert die aktiven Kategorien in ihrer fachlichen Reihenfolge.
     *
     * @return Kategorien mit Wertebereich; nie {@code null}, hoechstens leer
     */
    public List<SkillKategorie> aktive() {
        return jdbc.sql(SQL_AKTIVE)
                .query((rs, zeile) -> new SkillKategorie(
                        rs.getString("schluessel"),
                        rs.getInt("min_wert"),
                        rs.getInt("max_wert")))
                .list();
    }
}
