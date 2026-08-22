package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.Spieler;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Zugriff auf {@code profil.spieler}.
 *
 * <p>Spring Data setzt dieses Interface und {@link SpielerRepositoryCustom} zu einem Bean
 * zusammen; der Service kennt nur diesen Typ und sieht nicht, welcher Teil ueber JPA und
 * welcher ueber JDBC laeuft.
 */
public interface SpielerRepository extends JpaRepository<Spieler, Long>, SpielerRepositoryCustom {

    /**
     * Meldet, ob ein Profil mit diesem Namen existiert - unabhaengig von Gross- und
     * Kleinschreibung und unabhaengig davon, ob es aktiv ist.
     *
     * <p>Wird beim Gast-Login gebraucht: Ein Gast darf sich nicht so nennen wie ein
     * angelegtes Profil, sonst sind die beiden in Teilnehmerliste und Teameinteilung nicht
     * mehr auseinanderzuhalten. Auch inaktive Profile zaehlen, weil sie jederzeit wieder
     * aktiviert werden koennen.
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Sucht ein Profil ueber seinen Namen. Der Name ist ueber {@code uq_spieler_name}
     * eindeutig.
     *
     * <p>Wird vom Admin-Bootstrap benoetigt, der das Adminprofil ueber {@code ADMIN_NAME}
     * aus der Umgebung auswaehlt. Die Suche ignoriert Gross- und Kleinschreibung, damit
     * eine abweichende Schreibweise in der {@code .env} nicht zum Startabbruch fuehrt.
     */
    Optional<Spieler> findByNameIgnoreCase(String name);

    /**
     * Liefert das Profil mit der Rolle {@code ADMIN}, falls es eines gibt.
     *
     * <p>Hoechstens ein Treffer ist datenbankseitig garantiert: Der partielle Unique-Index
     * {@code uq_spieler_genau_ein_admin} laesst keinen zweiten zu. Der Rueckgabetyp
     * {@code Optional} bildet das ab, ohne dass die Anwendung die Eindeutigkeit selbst
     * pruefen muesste.
     */
    Optional<Spieler> findByRolle(Rolle rolle);

    /**
     * Legt fuer ein Profil je aktiver Skillkategorie eine Zeile mit dem Wert {@code 0} an.
     *
     * <p>Gebraucht vom Admin-Bootstrap: Das Adminprofil ist ein technisches Konto und nimmt
     * weder an der Namensauswahl noch an der Teamgenerierung teil (Entscheidung des
     * Haupt-Entwicklers vom 22.08.2026). Die Werte sind deshalb fachlich bedeutungslos - sie
     * stehen trotzdem da, damit das Profil vollstaendig ist und eine spaetere Auswertung
     * nicht ueber fehlende Zeilen stolpert.
     *
     * <p><b>Die Kategorien kommen aus der Datenbank, nicht aus einer Liste im Code.</b>
     * {@code profil.skill_kategorie} ist datengetrieben; eine fest verdrahtete Aufzaehlung
     * liefe auseinander, sobald eine Kategorie hinzukommt oder abgeschaltet wird.
     *
     * <p>Der Wert {@code 0} passt in jede Kategorie: Der Trigger
     * {@code pruefe_skill_wertebereich} prueft gegen {@code min_wert}, und der liegt fuer
     * alle Kategorien bei 0 - auch fuer Torwart mit dem Bereich 0 bis 3.
     *
     * <p>{@code ON CONFLICT} macht den Aufruf wiederholbar: Vorhandene Werte bleiben
     * unangetastet, es wird nichts ueberschrieben.
     *
     * @param spielerId Profil, dessen Skillzeilen entstehen sollen
     * @return Anzahl angelegter Zeilen
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO profil.spieler_skill (spieler_id, kategorie, wert)
            SELECT :spielerId, k.schluessel, 0
              FROM profil.skill_kategorie k
             WHERE k.aktiv
            ON CONFLICT ON CONSTRAINT uq_spieler_skill DO NOTHING
            """, nativeQuery = true)
    int nullwerteAnlegen(@Param("spielerId") Long spielerId);
}
