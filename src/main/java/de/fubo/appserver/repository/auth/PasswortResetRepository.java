package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.AnforderungsFenster;
import de.fubo.appserver.domain.auth.OffenerReset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Zugriff auf {@code profil.passwort_reset} - die Vorgaenge zum Zuruecksetzen des
 * Admin-Passworts (A22).
 *
 * <p><b>Warum kein Spring-Data-Repository und keine Entity?</b> Auf der Tabelle finden
 * ausschliesslich Einfuegungen und bedingte Aktualisierungen statt; ein einzelner Datensatz
 * wird nie geladen, geaendert und zurueckgeschrieben. Dieselbe Ueberlegung wie bei
 * {@code AuditLogRepository} und {@code GastSlotRepository} - und hier zusaetzlich
 * zwingend: Der Versuchszaehler muss in <i>einem</i> Statement steigen und pruefen, sonst
 * liegt zwischen Lesen und Schreiben ein Fenster zum Raten.
 *
 * <p>Das Datenmodell stammt unveraendert aus {@code V003}; S2b braucht <b>keine
 * Migration</b>.
 */
@Repository
public class PasswortResetRepository {

    /**
     * Entwertet alle noch offenen Vorgaenge.
     *
     * <p><b>Nicht optional.</b> Ohne diesen Schritt sammelten sich bei mehrfachem Anfordern
     * mehrere gueltige PINs an, und jede zusaetzliche vervielfachte die Trefferchance beim
     * Raten. {@code verbraucht_am} wird dabei fuer denselben Zweck genutzt wie beim Erfolg:
     * Die Spalte bedeutet "dieser Vorgang ist erledigt", nicht "dieser Vorgang war
     * erfolgreich".
     */
    private static final String SQL_OFFENE_ENTWERTEN = """
            UPDATE profil.passwort_reset
               SET verbraucht_am = now()
             WHERE verbraucht_am IS NULL
            """;

    /**
     * Legt einen Vorgang an. Gespeichert wird ausschliesslich der BCrypt-Hash der PIN;
     * {@code erstellt_am} kommt aus dem Spaltendefault, damit fuer alle Vorgaenge dieselbe
     * Uhr gilt.
     */
    private static final String SQL_ANLEGEN = """
            INSERT INTO profil.passwort_reset (pin_hash, gueltig_bis, angefordert_von_ip)
                 VALUES (:pinHash, :gueltigBis, :clientIp)
            """;

    /**
     * Zaehlt einen Versuch auf dem juengsten offenen Vorgang und liefert dessen Hash zurueck.
     *
     * <p><b>Der Zaehler steigt, bevor die PIN geprueft wird</b> - nicht danach. Sonst
     * koennte ein Angreifer den Aufruf nach dem Lesen abbrechen und beliebig oft raten,
     * ohne dass der Zaehler steigt.
     *
     * <p><b>{@code versuche < :maxVersuche} ist nicht nur Logik, sondern schuetzt vor einem
     * Datenbankfehler.</b> {@code ck_passwort_reset_versuche} erlaubt Werte von 0 bis 5.
     * Ohne die Bedingung liefe der sechste Versuch in eine Constraint-Verletzung und damit
     * in einen {@code 500} statt in eine saubere Ablehnung.
     *
     * <p>Kommt keine Zeile zurueck, ist der Vorgang unbrauchbar - abgelaufen, verbraucht,
     * erschoepft oder gar nicht vorhanden. Fuer den Aufrufer ist das dasselbe.
     */
    private static final String SQL_VERSUCH_ZAEHLEN = """
            UPDATE profil.passwort_reset
               SET versuche = versuche + 1
             WHERE id = (SELECT id
                           FROM profil.passwort_reset
                          WHERE verbraucht_am IS NULL
                          ORDER BY erstellt_am DESC
                          LIMIT 1)
               AND verbraucht_am IS NULL
               AND gueltig_bis > now()
               AND versuche < :maxVersuche
         RETURNING id, pin_hash
            """;

    /** Schliesst genau einen Vorgang ab; die Bedingung verhindert ein doppeltes Einloesen. */
    private static final String SQL_VERBRAUCHEN = """
            UPDATE profil.passwort_reset
               SET verbraucht_am = now()
             WHERE id = :id
               AND verbraucht_am IS NULL
            """;

    /**
     * Zaehlt die Anforderungen einer Adresse in der letzten Stunde und liefert die aelteste
     * davon.
     *
     * <p>Der Index {@code ix_passwort_reset_ip (angefordert_von_ip, erstellt_am)} deckt die
     * Abfrage genau ab.
     *
     * <p><b>Warum hier eine Zaehlabfrage vertretbar ist</b>, obwohl der Gastplatz in S2 ein
     * bedingtes {@code UPDATE} verlangte: Dort entschied die Abfrage ueber eine knappe
     * Ressource, und zwei gleichzeitige Aufrufe haetten denselben Platz belegt. Hier ist die
     * Zaehlung eine Drosselung - kommen zwei Anforderungen im selben Moment durch, ist die
     * vierte statt der dritten die letzte. Ein Fehler von eins, der niemanden gefaehrdet.
     */
    private static final String SQL_DROSSELUNG = """
            SELECT count(*)          AS anzahl,
                   min(erstellt_am)  AS aeltestes
              FROM profil.passwort_reset
             WHERE angefordert_von_ip = :clientIp
               AND erstellt_am > now() - interval '1 hour'
            """;

    private final JdbcClient jdbc;

    public PasswortResetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Entwertet alle offenen Vorgaenge.
     *
     * @return Anzahl entwerteter Vorgaenge
     */
    public int offeneEntwerten() {
        return jdbc.sql(SQL_OFFENE_ENTWERTEN).update();
    }

    /**
     * Legt einen neuen Vorgang an.
     *
     * @param pinHash    BCrypt-Hash der Bestaetigungs-PIN
     * @param gueltigBis Ablaufzeitpunkt
     * @param clientIp   anfordernde Adresse; Grundlage der Drosselung
     */
    public void anlegen(String pinHash, OffsetDateTime gueltigBis, String clientIp) {
        jdbc.sql(SQL_ANLEGEN)
                .param("pinHash", pinHash)
                .param("gueltigBis", gueltigBis)
                .param("clientIp", clientIp)
                .update();
    }

    /**
     * Zaehlt einen Versuch und liefert den Vorgang, gegen den zu pruefen ist.
     *
     * <h2>Warum {@code REQUIRES_NEW}</h2>
     * Der Zaehler muss den Rollback der abgelehnten Anfrage <b>ueberleben</b>. Rollte er
     * mit zurueck, waere die Begrenzung auf fuenf Versuche wirkungslos: Jeder Fehlversuch
     * endet in einer Ausnahme, und die rollt die Transaktion zurueck.
     *
     * <p>Das ist die <b>einzige</b> Stelle des Projekts, an der die Ausnahme gerechtfertigt
     * ist (entschieden zu offenem Punkt 3 der S2b-Anleitung). Die Regel in
     * {@code AGENT_SERVER.md} gilt dem <i>Audit-Log</i>: Ein Protokolleintrag belegt eine
     * vollzogene Aenderung und darf nach einem Rollback nicht stehen bleiben. Ein Zaehler
     * belegt nichts - er misst einen Versuch, und der hat stattgefunden.
     *
     * <p>Zur Sicherheit: Der Aufrufer ruft die Methode als <i>erste</i> Anweisung auf, bevor
     * seine eigene Transaktion die Tabelle beruehrt. Andernfalls wartete die neue
     * Transaktion auf eine Zeilensperre, die die aeussere haelt - ein Selbstblockieren.
     *
     * @param maxVersuche Obergrenze aus {@code fubo.reset.max-versuche}; hoechstens 5,
     *                    weil {@code ck_passwort_reset_versuche} nicht mehr zulaesst
     * @return der Vorgang mit seinem Hash, oder leer, wenn keiner brauchbar ist
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OffenerReset> versuchZaehlen(int maxVersuche) {
        return jdbc.sql(SQL_VERSUCH_ZAEHLEN)
                .param("maxVersuche", maxVersuche)
                .query((rs, zeile) -> new OffenerReset(rs.getLong("id"), rs.getString("pin_hash")))
                .optional();
    }

    /**
     * Schliesst einen Vorgang ab.
     *
     * @param id Vorgang
     * @return Anzahl geaenderter Zeilen; 0 bedeutet, dass er zwischenzeitlich von einem
     *         anderen Aufruf eingeloest oder entwertet wurde
     */
    public int verbrauchen(Long id) {
        return jdbc.sql(SQL_VERBRAUCHEN)
                .param("id", id)
                .update();
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
                .query((rs, zeile) -> new AnforderungsFenster(
                        rs.getInt("anzahl"),
                        rs.getObject("aeltestes", OffsetDateTime.class)))
                .single();
    }
}
