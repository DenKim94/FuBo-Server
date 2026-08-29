package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.Spieler;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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
     * Sucht ein Profil ueber seinen Namen - <b>zeichengenau</b>. Der Name ist ueber
     * {@code uq_spieler_name} eindeutig, und dieser Index ist in PostgreSQL
     * gross-/kleinschreibungsempfindlich; hoechstens ein Treffer ist damit garantiert.
     *
     * <p>Wird vom {@code AdminBootstrap} benoetigt, der das Adminprofil ueber
     * {@code ADMIN_NAME} auswaehlt. <b>Seit dem 29.08.2026 exakt statt unempfindlich:</b>
     * Der Anmeldename des Admins wird zeichengenau geprueft, also muss der Bootstrap den
     * Profilnamen auch zeichengenau so ablegen, wie {@code ADMIN_NAME} ihn nennt. Eine
     * unempfindliche Suche uebernaehme sonst ein Profil mit abweichender Schreibweise, und
     * der Betreiber koennte sich mit dem Wert aus seiner eigenen {@code .env} nicht anmelden.
     */
    Optional<Spieler> findByName(String name);

    /**
     * Sucht <b>alle</b> Profile, deren Name ohne Ruecksicht auf Gross- und Kleinschreibung
     * uebereinstimmt.
     *
     * <p>Dient im {@code AdminBootstrap} allein der Diagnose: Findet
     * {@link #findByName(String)} nichts, diese Suche aber doch, weicht ein vorhandenes
     * Profil nur in der Schreibweise ab - fast immer ein Vertipper in {@code ADMIN_NAME}.
     * Der Bootstrap bricht dann ab, statt ein zweites, nahezu gleichnamiges Profil anzulegen.
     *
     * <p><b>Rueckgabetyp {@code List}, nicht {@code Optional}, und das mit Absicht:</b>
     * {@code uq_spieler_name} ist in PostgreSQL gross-/kleinschreibungsempfindlich, "Admin"
     * und "admin" duerfen also nebeneinander stehen. Ein {@code Optional} liefe dann in eine
     * {@code IncorrectResultSizeDataAccessException} - ein Startabbruch mit einer Meldung
     * ueber Ergebnismengen statt ueber die Ursache.
     *
     * <p><b>Nie fuer die Auswahl oder die Anmeldung verwenden</b> - beide brauchen die
     * zeichengenaue Suche.
     */
    List<Spieler> findAllByNameIgnoreCase(String name);

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

    /**
     * Legt fuer ein Profil die Skillwerte einer Gastvorlage an (S2b, Abschnitt 8).
     *
     * <p><b>Warum die Vorgaben aus {@code profil.gast_vorlage} kommen und nicht aus Nullen:</b>
     * Ein Profil mit lauter Nullen bekaeme in der Teamgenerierung ein Team ohne jede Staerke
     * zugeteilt, ohne dass jemand den Grund saehe. Die Stufe {@code MITTEL} ist genau der
     * Wert, mit dem auch ein Gast ohne Selbsteinschaetzung eingeht - eine ehrliche Annahme
     * statt einer stillen Verzerrung. Das Adminprofil bleibt der Sonderfall: Es ist ein
     * technisches Konto, wird nie eingeteilt und behaelt deshalb
     * {@link #nullwerteAnlegen(Long)}.
     *
     * <p>{@code ON CONFLICT DO NOTHING} macht den Aufruf wiederholbar und laesst bereits
     * gesetzte Werte unangetastet - der Aufrufer kann also erst die Vorgaben legen und
     * danach einzelne Kategorien ueberschreiben, oder umgekehrt.
     *
     * @param spielerId Profil, dessen Skillzeilen entstehen sollen
     * @param stufe     Stufe der Vorlage: {@code STARK}, {@code MITTEL} oder {@code SCHWACH}
     * @return Anzahl angelegter Zeilen
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO profil.spieler_skill (spieler_id, kategorie, wert)
            SELECT :spielerId, v.kategorie, v.wert
              FROM profil.gast_vorlage v
              JOIN profil.skill_kategorie k ON k.schluessel = v.kategorie
             WHERE v.stufe = :stufe
               AND k.aktiv
            ON CONFLICT ON CONSTRAINT uq_spieler_skill DO NOTHING
            """, nativeQuery = true)
    int vorgabewerteAnlegen(@Param("spielerId") Long spielerId, @Param("stufe") String stufe);

    /**
     * Setzt einen einzelnen Skillwert (S2b, Abschnitt 8).
     *
     * <p>{@code ON CONFLICT ... DO UPDATE} statt eines vorherigen Lesezugriffs: Ob die Zeile
     * schon existiert, ist fuer den Aufrufer ohne Belang, und zwei Anweisungen waeren ein
     * Fenster fuer einen Wettlauf.
     *
     * <p><b>Der Wertebereich wird hier nicht geprueft.</b> Das erledigt der Trigger
     * {@code pruefe_skill_wertebereich} als letzte Instanz - und vor ihm der Service, der
     * gegen {@code profil.skill_kategorie} prueft und einen sauberen {@code 400} liefern
     * kann. Der Trigger allein braechte einen {@code 500}.
     *
     * @return Anzahl geschriebener Zeilen; immer 1
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO profil.spieler_skill (spieler_id, kategorie, wert)
                 VALUES (:spielerId, :kategorie, :wert)
            ON CONFLICT ON CONSTRAINT uq_spieler_skill
              DO UPDATE SET wert = EXCLUDED.wert
            """, nativeQuery = true)
    int skillwertSetzen(@Param("spielerId") Long spielerId,
                        @Param("kategorie") String kategorie,
                        @Param("wert") int wert);

    /**
     * Meldet, ob auf das Profil noch fachliche Daten verweisen (S2b, Abschnitt 8).
     *
     * <p>Alle aufgezaehlten Fremdschluessel sind ohne {@code ON DELETE} angelegt; ein
     * {@code DELETE} scheiterte an ihnen mit einer Meldung, die den Index nennt und nicht
     * die Ursache. Die Abfrage nimmt dem Aufrufer diese Meldung ab und erlaubt eine
     * verstaendliche Ablehnung.
     *
     * <p><b>Sitzungen stehen bewusst nicht in der Liste.</b> Sie sind fluechtig und gehoeren
     * der Anwendung; der Service raeumt sie vorher selbst ab. Alles Uebrige sind Belege -
     * Teilnahmen, Terminserien, Generierungslaeufe, Kontingente, Ergebnisse und das
     * Audit-Log -, und ein Beleg darf nicht verschwinden, weil jemand ein Profil aufraeumt.
     *
     * <p>{@code profil.spieler_skill} fehlt ebenfalls: Die Zeilen haengen mit
     * {@code ON DELETE CASCADE} am Profil und verschwinden mit ihm.
     *
     * <p><b>Achtung bei Erweiterungen:</b> Die Tabellennamen stimmen hier nicht mit den Namen
     * ihrer Fremdschluessel ueberein - {@code fk_terminserie_spieler} gehoert zu
     * {@code spieltag.terminserie}, {@code fk_kontingent_spieler} zu
     * {@code spieltag.generierung_kontingent}. Wer die Liste ergaenzt, liest die Namen aus den
     * {@code CREATE TABLE}-Zeilen der Migration, nicht aus den Constraint-Namen. Ein Tippfehler
     * faellt erst zur Laufzeit auf, und zwar als {@code 500}.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM profil.admin_konto        WHERE spieler_id             = :spielerId)
                OR EXISTS (SELECT 1 FROM profil.audit_log          WHERE akteur_spieler_id      = :spielerId)
                OR EXISTS (SELECT 1 FROM spieltag.teilnahme        WHERE spieler_id             = :spielerId)
                OR EXISTS (SELECT 1 FROM spieltag.terminserie            WHERE angelegt_von           = :spielerId)
                OR EXISTS (SELECT 1 FROM spieltag.team_generierung       WHERE erzeugt_von_spieler_id = :spielerId)
                OR EXISTS (SELECT 1 FROM spieltag.generierung_kontingent WHERE akteur_spieler_id      = :spielerId)
                OR EXISTS (SELECT 1 FROM spieltag.ergebnis         WHERE erfasst_von_spieler_id = :spielerId)
            """, nativeQuery = true)
    boolean istReferenziert(@Param("spielerId") Long spielerId);
}
