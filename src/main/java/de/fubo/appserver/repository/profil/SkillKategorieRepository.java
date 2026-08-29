package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.profil.SkillKategorie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Lesezugriff auf {@code profil.skill_kategorie} (S2b Abschnitt 8, erweitert in S3 Abschnitt 4).
 *
 * <p><b>Warum kein Spring-Data-Repository?</b> Die Tabelle ist Referenzdatenbestand: Sie wird
 * gelesen, nie geschrieben. Eine Entity brauchte es erst, wenn der Admin Kategorien pflegen
 * koennte - und das steht ausdruecklich nicht in S3 (Anleitung, Abschnitt 4.3: eine Senkung von
 * {@code max_wert} machte bestehende Werte ungueltig, eine neue Kategorie machte jedes Profil
 * unvollstaendig, und {@code gewicht} zu aendern verstellte die Zielfunktion des Teamgenerators).
 * Vorbild sind {@code AuditLogRepository} und {@code GastSlotRepository}, die aus demselben Grund
 * direkt {@code JdbcClient} nutzen.
 */
@Repository
public class SkillKategorieRepository {

    /**
     * Nur aktive Kategorien. Eine abgeschaltete Kategorie soll weder in einer neuen
     * Skillzeile landen noch eine Eingabe rechtfertigen - und im Adminformular nicht erscheinen.
     *
     * <p><b>Seit S3 werden alle sechs Spalten gelesen</b>, nicht mehr nur drei. Die Abfrage
     * bedient zwei Aufrufer mit unterschiedlichem Bedarf: Die Pruefung beim Anlegen und
     * Bearbeiten braucht Schluessel und Wertebereich, der Endpunkt {@code /admin/skills/lesen}
     * zusaetzlich Bezeichnung, Gewicht und Reihenfolge. Eine zweite Abfrage fuer denselben
     * Datenbestand waere zwei Wahrheiten ueber dieselben fuenf Zeilen - die Tabelle ist so
     * klein, dass drei zusaetzliche Spalten nicht ins Gewicht fallen.
     */
    private static final String SQL_AKTIVE = """
            SELECT schluessel, bezeichnung, gewicht, reihenfolge, min_wert, max_wert
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
     * <p>{@code gewicht} wird als {@code BigDecimal} gelesen, nicht als {@code double}: Die
     * Spalte ist {@code NUMERIC(4,2)}, und 0.30 laesst sich binaer nicht exakt darstellen. In
     * der Zielfunktion des Teamgenerators (S5) summiert sich ein solcher Rundungsfehler ueber
     * alle Kategorien und Spieler auf.
     *
     * @return Kategorien mit Wertebereich; nie {@code null}, hoechstens leer
     */
    public List<SkillKategorie> aktive() {
        return jdbc.sql(SQL_AKTIVE)
                .query((rs, zeile) -> new SkillKategorie(
                        rs.getString("schluessel"),
                        rs.getString("bezeichnung"),
                        rs.getBigDecimal("gewicht"),
                        rs.getInt("reihenfolge"),
                        rs.getInt("min_wert"),
                        rs.getInt("max_wert")))
                .list();
    }
}
