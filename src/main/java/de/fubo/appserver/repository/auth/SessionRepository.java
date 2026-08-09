package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Zugriff auf {@code profil.session}.
 *
 * <p>Die Massen-Updates sind bewusst als natives SQL formuliert statt ueber geladene
 * Entities: {@code deleteAll(liste)} wuerde jede Zeile einzeln laden und ein
 * {@code DELETE} je Datensatz absetzen.
 *
 * <p>Alle schreibenden Abfragen laufen mit {@code flushAutomatically = true} und
 * {@code clearAutomatically = true}. Der erste Schalter sorgt dafuer, dass ausstehende
 * JPA-Aenderungen vor dem nativen Statement in der Datenbank landen; der zweite leert
 * anschliessend den Persistence-Context, damit keine veraltete Entity weiterverwendet
 * wird. Ohne beides ist der Mischbetrieb aus JPA und nativem SQL eine Fehlerquelle.
 */
public interface SessionRepository extends JpaRepository<Session, Long>, SessionRepositoryCustom {

    /** Liefert eine Sitzung anhand ihres Token-Hashes, ohne sie zu verlaengern. */
    Optional<Session> findByTokenHash(String tokenHash);

    /**
     * Ersetzt den Token-Hash einer bestehenden Sitzung (Rotation, Abschnitt 3.3).
     * Die {@code id} bleibt erhalten und damit auch die Verknuepfung
     * {@code gast_slot.session_id}.
     *
     * @return Anzahl geaenderter Zeilen; 0 bedeutet, dass es die Sitzung nicht gibt
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE profil.session
               SET token_hash = :hash
             WHERE id = :id
               AND widerrufen_am IS NULL
            """, nativeQuery = true)
    int tokenErsetzen(@Param("id") Long id, @Param("hash") String hash);

    /**
     * Setzt eine Sitzung auf {@code PROFILE_AUTHENTICATED} und traegt die gewaehlte
     * Identitaet ein. Wird in Abschnitt 7 (Namensauswahl) verwendet.
     *
     * @return Anzahl geaenderter Zeilen
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE profil.session
               SET stage      = 'PROFILE_AUTHENTICATED',
                   spieler_id = :spielerId,
                   rolle      = :rolle
             WHERE id = :id
               AND stage = 'PIN_VERIFIED'
               AND widerrufen_am IS NULL
            """, nativeQuery = true)
    int aufProfileAuthenticatedSetzen(@Param("id") Long id,
                                      @Param("spielerId") Long spielerId,
                                      @Param("rolle") String rolle);

    /** Widerruft eine einzelne Sitzung (Logout). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE profil.session
               SET widerrufen_am = now()
             WHERE id = :id
               AND widerrufen_am IS NULL
            """, nativeQuery = true)
    int widerrufen(@Param("id") Long id);

    /** Widerruft alle offenen Sitzungen, etwa nach einem Wechsel der zentralen PIN. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE profil.session
               SET widerrufen_am = now()
             WHERE widerrufen_am IS NULL
            """, nativeQuery = true)
    int alleWiderrufen();

    /** Widerruft alle offenen Sitzungen eines Profils, etwa bei Deaktivierung. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE profil.session
               SET widerrufen_am = now()
             WHERE spieler_id = :spielerId
               AND widerrufen_am IS NULL
            """, nativeQuery = true)
    int widerrufenFuerSpieler(@Param("spielerId") Long spielerId);

    /**
     * Prueft, ob fuer ein Profil bereits eine aktive Sitzung besteht (Namensbelegung, A6).
     * Nutzt den partiellen Index {@code ix_session_aktiv}.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1
                             FROM profil.session
                            WHERE spieler_id = :spielerId
                              AND widerrufen_am IS NULL
                              AND gueltig_bis > now()
                              AND absolut_gueltig_bis > now())
            """, nativeQuery = true)
    boolean existiertAktiveSitzungFuer(@Param("spielerId") Long spielerId);

    /**
     * Entfernt abgelaufene Sitzungen (Aufraeumjob, Abschnitt 3.5). Zeilen werden nicht
     * beim Logout geloescht, sondern laufen ab - ohne diesen Job waechst die Tabelle
     * unbegrenzt.
     *
     * @return Anzahl geloeschter Zeilen
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM profil.session
             WHERE absolut_gueltig_bis < :stichtag
            """, nativeQuery = true)
    int loescheAelterAls(@Param("stichtag") OffsetDateTime stichtag);
}
