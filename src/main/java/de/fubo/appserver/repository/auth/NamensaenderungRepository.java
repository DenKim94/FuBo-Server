package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.AnforderungsFenster;
import de.fubo.appserver.domain.auth.OffeneNamensaenderung;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Zugriff auf {@code profil.name_aenderung} - die Anforderungen zur Aenderung des
 * Admin-Anmeldenamens (A22, Ergaenzung vom 04.10.2026, {@code V015}).
 *
 * <p><b>Aufbau und Gruende wie {@link PasswortResetRepository}</b>, auf das hier fuer die
 * ausfuehrlichen Herleitungen verwiesen wird: keine Entity, weil Zeilen nur angehaengt und
 * bedingt aktualisiert werden; der Versuchszaehler steigt und prueft in <i>einer</i> Anweisung.
 *
 * <p><b>Eine eigene Tabelle und ein eigenes Repository</b> (Entscheidung vom 04.10.2026): Die
 * PIN des einen Vorgangs soll beim anderen baulich wertlos sein, nicht nur ueber eine
 * Bedingung, die jede Abfrage mitfuehren muesste.
 *
 * <p><b>Zwei Abweichungen vom Vorbild, beide mit Absicht:</b>
 * <ul>
 *   <li><b>Der Ablaufzeitpunkt entsteht in der Datenbank</b> ({@code now() + make_interval}).
 *       Geprueft wird er dort ebenfalls ({@code gueltig_bis > now()}); mit einem Wert aus der
 *       Anwendung rechneten Schreiben und Pruefen gegen zwei verschiedene Uhren.</li>
 *   <li><b>Das Einloesen liefert den gewuenschten Namen zurueck</b>, in derselben Anweisung, die
 *       den Vorgang schliesst. Damit gibt es keinen Augenblick, in dem der Name gelesen, der
 *       Vorgang aber noch offen ist.</li>
 * </ul>
 */
@Repository
public class NamensaenderungRepository {

    /**
     * Entwertet alle noch offenen Anforderungen. <b>Nicht optional</b> - sonst waeren mehrere
     * PINs fuer mehrere Namen gleichzeitig gueltig, und jede zusaetzliche vervielfachte die
     * Trefferchance beim Raten. {@code verbraucht_am} heisst "erledigt", nicht "erfolgreich".
     */
    private static final String SQL_OFFENE_ENTWERTEN = """
            UPDATE profil.name_aenderung
               SET verbraucht_am = now()
             WHERE verbraucht_am IS NULL
            """;

    /** Legt eine Anforderung an; gespeichert wird ausschliesslich der BCrypt-Hash der PIN. */
    private static final String SQL_ANLEGEN = """
            INSERT INTO profil.name_aenderung (pin_hash, neuer_name, gueltig_bis, angefordert_von_ip)
                 VALUES (:pinHash, :neuerName, now() + make_interval(mins => :minuten), :clientIp)
            """;

    /**
     * Zaehlt einen Versuch auf der juengsten offenen Anforderung und liefert deren Hash.
     *
     * <p>Der Zaehler steigt <b>vor</b> der Pruefung der PIN, und
     * {@code versuche < :maxVersuche} haelt ihn unter der Grenze von
     * {@code ck_name_aenderung_versuche} - Herleitung bei {@link PasswortResetRepository}.
     */
    private static final String SQL_VERSUCH_ZAEHLEN = """
               UPDATE profil.name_aenderung
                  SET versuche = versuche + 1
                WHERE id = (SELECT id
                              FROM profil.name_aenderung
                             WHERE verbraucht_am IS NULL
                             ORDER BY erstellt_am DESC, id DESC
                             LIMIT 1)
                  AND verbraucht_am IS NULL
                  AND gueltig_bis > now()
                  AND versuche < :maxVersuche
            RETURNING id, pin_hash
            """;

    /**
     * Schliesst genau eine Anforderung ab und liefert den Namen, den sie bestaetigt.
     *
     * <p>{@code gueltig_bis > now()} steht hier ein zweites Mal: Zwischen Versuchszaehlung und
     * Einloesen liegt ein eigener Aufruf, und eine Anforderung, die in diesem Moment ablaeuft,
     * soll nicht doch noch durchgehen.
     */
    private static final String SQL_EINLOESEN = """
               UPDATE profil.name_aenderung
                  SET verbraucht_am = now()
                WHERE id = :id
                  AND verbraucht_am IS NULL
                  AND gueltig_bis > now()
            RETURNING neuer_name
            """;

    /**
     * Zahl und aeltester Zeitpunkt der Anforderungen einer Adresse in der letzten Stunde;
     * abgedeckt durch {@code ix_name_aenderung_ip}.
     */
    private static final String SQL_DROSSELUNG = """
            SELECT count(*)          AS anzahl,
                   min(erstellt_am)  AS aeltestes
              FROM profil.name_aenderung
             WHERE angefordert_von_ip = :clientIp
               AND erstellt_am > now() - interval '1 hour'
            """;

    /** Entfernt Anforderungen jenseits der Aufbewahrungsfrist; massgeblich ist {@code erstellt_am}. */
    private static final String SQL_LOESCHEN = """
            DELETE FROM profil.name_aenderung
             WHERE erstellt_am < :stichtag
            """;

    private final JdbcClient jdbc;

    public NamensaenderungRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Entwertet alle offenen Anforderungen.
     *
     * <p>Ohne Rueckgabewert: Wie viele es waren, entscheidet nichts - der Aufrufer legt danach in
     * jedem Fall eine neue Anforderung an.
     */
    public void offeneEntwerten() {
        jdbc.sql(SQL_OFFENE_ENTWERTEN).update();
    }

    /**
     * Legt eine neue Anforderung an.
     *
     * @param pinHash            BCrypt-Hash der Bestaetigungs-PIN
     * @param neuerName          bereits getrimmter und gepruefter Name
     * @param gueltigkeitMinuten Lebensdauer ab jetzt, gerechnet mit der Uhr der Datenbank
     * @param clientIp           anfordernde Adresse; Grundlage der Drosselung
     */
    public void anlegen(String pinHash, String neuerName, int gueltigkeitMinuten, String clientIp) {
        jdbc.sql(SQL_ANLEGEN)
                .param("pinHash", pinHash)
                .param("neuerName", neuerName)
                .param("minuten", gueltigkeitMinuten)
                .param("clientIp", clientIp)
                .update();
    }

    /**
     * Zaehlt einen Versuch und liefert die Anforderung, gegen die zu pruefen ist.
     *
     * <p><b>{@code REQUIRES_NEW}</b> aus demselben Grund wie
     * {@link PasswortResetRepository#versuchZaehlen(int)}: Der Zaehler muss den Rollback der
     * abgelehnten Anfrage ueberleben, sonst waere die Begrenzung auf fuenf Versuche wirkungslos.
     * Er misst einen Versuch, der stattgefunden hat - kein Protokolleintrag, fuer den die Regel
     * "immer {@code REQUIRED}" gilt.
     *
     * @param maxVersuche Obergrenze aus {@code fubo.reset.max-versuche}; hoechstens 5
     * @return die Anforderung mit ihrem Hash, oder leer, wenn keine brauchbar ist
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OffeneNamensaenderung> versuchZaehlen(int maxVersuche) {
        return jdbc.sql(SQL_VERSUCH_ZAEHLEN)
                .param("maxVersuche", maxVersuche)
                .query((rs, _) -> new OffeneNamensaenderung(rs.getLong("id"), rs.getString("pin_hash")))
                .optional();
    }

    /**
     * Schliesst eine Anforderung ab.
     *
     * @param id Anforderung aus {@link #versuchZaehlen(int)}
     * @return der bestaetigte Name, oder leer, wenn die Anforderung inzwischen eingeloest,
     *         entwertet oder abgelaufen ist
     */
    public Optional<String> einloesen(Long id) {
        return jdbc.sql(SQL_EINLOESEN)
                .param("id", id)
                .query((rs, _) -> rs.getString("neuer_name"))
                .optional();
    }

    /**
     * Liefert die Anforderungen einer Adresse in der letzten Stunde.
     *
     * @param clientIp anfordernde Adresse
     * @return Anzahl und aeltester Zeitpunkt; die Anzahl ist 0, wenn es keine gibt
     */
    public AnforderungsFenster anforderungenDerLetztenStunde(String clientIp) {
        return jdbc.sql(SQL_DROSSELUNG)
                .param("clientIp", clientIp)
                .query((rs, _) -> new AnforderungsFenster(
                        rs.getInt("anzahl"),
                        rs.getObject("aeltestes", OffsetDateTime.class)))
                .single();
    }

    /**
     * Entfernt Anforderungen, die aelter sind als der Stichtag.
     *
     * @param stichtag Zeitpunkt, vor dem geloescht wird
     * @return Anzahl geloeschter Zeilen
     */
    public int loescheAelterAls(OffsetDateTime stichtag) {
        return jdbc.sql(SQL_LOESCHEN)
                .param("stichtag", stichtag)
                .update();
    }
}
