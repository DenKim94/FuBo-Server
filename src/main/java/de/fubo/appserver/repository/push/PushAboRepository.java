package de.fubo.appserver.repository.push;

import de.fubo.appserver.domain.push.PushAbo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Zugriff auf {@code profil.push_abo} - die Abonnements je Spieler und Geraet (A25c,
 * {@code V014}; S8 Abschnitt 7.1).
 *
 * <h2>Kein Spring-Data-Repository und keine Entity</h2>
 * Auf der Tabelle wird angehaengt ({@code INSERT ... ON CONFLICT}), bedingt aktualisiert
 * (Versandergebnis je Zeile) und geloescht; ein einzelner Datensatz wird nie geladen,
 * geaendert und zurueckgeschrieben. <b>{@code @Version} waere hier sogar nachteilig:</b>
 * Optimistic Locking meldete den Wettlauf zweier gleichzeitiger Anmeldungen desselben Geraets
 * erst beim Schreiben und verlangte eine Wiederholung; der bedingte {@code UPDATE}
 * entscheidet ihn ohne. Vorbilder sind {@code GastSlotRepository} und
 * {@code TeilnahmeRepository}.
 *
 * <p><b>Folge: {@code version} wird von Hand fortgeschrieben</b>, in jeder schreibenden
 * Anweisung. Ohne das bliebe die Spalte stehen, und eine spaetere Entity auf derselben Tabelle
 * saehe unveraenderte Versionen.
 *
 * <h2>Die Empfaengerabfragen liegen hier, nicht in {@code repository/spieltag}</h2>
 * Sie lesen {@code profil.spieler} und {@code spieltag.teilnahme} mit, <b>liefern aber
 * Abonnements</b> - und die Ablage richtet sich nach dem Ergebnis, nicht nach der Herkunft der
 * Bedingungen. Dieselbe Ueberlegung wie bei {@code BilanzRepository}, das vier
 * Spieltagstabellen liest und in {@code repository/profil} liegt, weil es
 * {@code profil.spieler} schreibt.
 *
 * <p><b>Sie liefern die Abonnements und nicht erst die Spieler-Ids</b> - eine Abfrage statt
 * zweier. Drei Gruende: Der Fall "keine Empfaenger" braucht keine Sonderbehandlung (eine
 * leere Id-Liste ergaebe {@code IN ()} und damit einen Syntaxfehler - dieselbe Falle wie bei
 * {@code AufstellungRepository}), die drei Versandbedingungen stehen an <i>einer</i> Stelle
 * beieinander, und die Zahl der <i>Personen</i> bleibt ableitbar, weil {@code spieler_id}
 * mitkommt und sortiert ist.
 */
@Repository
public class PushAboRepository {

    /**
     * Legt ein Abonnement an oder frischt es auf (A25c).
     *
     * <h2>Idempotent ueber {@code endpoint_hash}</h2>
     * Der Client ruft den Endpunkt <b>bei jedem Anwendungsstart</b> auf. Ohne
     * {@code ON CONFLICT} entstuende jedes Mal eine Zeile oder ein {@code 500} am
     * Unique-Constraint.
     *
     * <p><b>{@code ON CONFLICT ON CONSTRAINT} statt ueber die Spaltenliste:</b> Der Name ist
     * hier tragend und nicht Kosmetik - er sagt ausdruecklich, welche Eindeutigkeit gemeint
     * ist. Dieselbe Schreibweise wie bei {@code uq_termin_zeit} und {@code uq_kontingent}.
     *
     * <h2>{@code deaktiviert_am} und {@code fehlversuche} werden zurueckgesetzt</h2>
     * <b>Das heilt genau einen Fall</b>, und er ist der Normalfall: Der Server hat das
     * Abonnement nach einem {@code 410} deaktiviert, der Browser fuehrt es aber noch. Ohne das
     * Zuruecksetzen bekaeme dieser Spieler nie wieder eine Nachricht - und niemand koennte
     * sagen, warum, weil "keine Nachricht" der Normalzustand vieler Spieler ist.
     *
     * <h2>{@code spieler_id} wandert mit</h2>
     * Der Unique-Constraint gilt <b>global</b>, nicht je Spieler: Die Adresse identifiziert
     * eine Browserinstallation, keine Person. Meldet sich auf einem geteilten Geraet ein
     * anderer Spieler an, muss das Abonnement die Person wechseln - sonst empfaengt der
     * vorherige weiter.
     *
     * <p><b>{@code erstellt_am} bleibt unberuehrt.</b> Es beantwortet "seit wann kennt der
     * Server dieses Geraet"; bei jedem Anwendungsstart neu gesetzt beantwortete es nur noch
     * "wann war der letzte Start".
     */
    private static final String SQL_ANLEGEN = """
            INSERT INTO profil.push_abo
                        (spieler_id, endpoint, endpoint_hash, p256dh, auth, geraet_bezeichnung)
                 VALUES (:spielerId, :endpoint, :hash, :p256dh, :auth, :geraet)
            ON CONFLICT ON CONSTRAINT uq_push_abo_endpoint_hash DO UPDATE
                    SET spieler_id         = EXCLUDED.spieler_id,
                        endpoint           = EXCLUDED.endpoint,
                        p256dh             = EXCLUDED.p256dh,
                        auth               = EXCLUDED.auth,
                        geraet_bezeichnung = EXCLUDED.geraet_bezeichnung,
                        deaktiviert_am     = NULL,
                        fehlversuche       = 0,
                        version            = push_abo.version + 1
            """;

    /**
     * Widerruft das Abonnement des aufrufenden Geraets (A25c).
     *
     * <p><b>{@code AND spieler_id = :spielerId} ist nicht optional.</b> Ohne die zweite
     * Bedingung entfernte ein Aufrufer mit einer fremden Endpoint-Adresse das Abonnement eines
     * anderen - der Endpunkt liegt ausserhalb von {@code /admin/} und steht jedem Angemeldeten
     * offen.
     *
     * <p>Null betroffene Zeilen sind trotzdem {@code 200}: Loeschen ist idempotent, und ein
     * {@code 404} zwaenge den Client zu einer Fallunterscheidung ohne Nutzen.
     *
     * <p><b>Hier wird wirklich geloescht</b>, nicht deaktiviert - anders als beim
     * {@code 410} eines Push-Dienstes. Der Zwischenzustand dient der Unterscheidung "erloschen
     * gegen nie abonniert"; ein ausdruecklicher Widerruf braucht sie nicht.
     */
    private static final String SQL_ENTFERNEN = """
            DELETE FROM profil.push_abo
                  WHERE endpoint_hash = :hash
                    AND spieler_id    = :spielerId
            """;

    /** Die Spalten, die der Versand braucht; {@link PushAbo} nennt den Grund je Feld. */
    private static final String SPALTEN_VERSAND =
            "a.id, a.spieler_id, a.endpoint, a.p256dh, a.auth";

    /**
     * Die aktiven Abonnements eines Spielers - Datengrundlage des Probeversands
     * (S8 Abschnitt 10.2).
     *
     * <p>Ohne Pruefung der beiden Schalter: Der Probeversand ignoriert sie (Weggabelung D).
     */
    private static final String SQL_AKTIVE_FUER_SPIELER = """
            SELECT %s
              FROM profil.push_abo a
             WHERE a.spieler_id     = :spielerId
               AND a.deaktiviert_am IS NULL
             ORDER BY a.id
            """.formatted(SPALTEN_VERSAND);

    /**
     * Empfaenger der Erinnerung: wer zu diesem Termin noch nicht geantwortet hat (A25b,
     * Anlass 1; S8 Abschnitt 8.4).
     *
     * <h2>Die drei Versandbedingungen stehen hier beieinander - bis auf eine</h2>
     * Person ({@code s.push_erwuenscht}) und Geraet ({@code a.deaktiviert_am IS NULL}) stehen
     * in dieser Abfrage. <b>Der Anlagenschalter {@code app_config.push_aktiv} steht bewusst
     * nicht darin:</b> Er gilt fuer den ganzen Lauf, und der Dienst steigt damit aus, bevor er
     * die Datenbank ueberhaupt fragt.
     *
     * <h2>{@code s.rolle <> 'ADMIN'} ist keine Formalie</h2>
     * Das Adminprofil ist ein technisches Konto. Es traegt {@code push_erwuenscht = true} aus
     * der Migration, hat <b>nie</b> eine Teilnahmezeile und erfuellt die Bedingung "hat noch
     * nicht geantwortet" damit fuer <b>jeden</b> Termin - waehrend ihm die Rueckmeldung selbst
     * mit {@code 409 PROFIL_GESCHUETZT} verweigert wird. Ohne den Filter bekaeme es zu jedem
     * Training die Aufforderung, etwas zu tun, was der Server ihm verbietet. Es ist derselbe
     * Filter, den Namensliste, Teilnehmerliste und Generator-Datengrundlage schon tragen.
     *
     * <p><b>Als Abonnent bleibt das Adminprofil zulaessig</b> - {@code /admin/push/test}
     * versendet an seine eigenen Geraete.
     *
     * <h2>Gaeste kommen gar nicht vor</h2>
     * Sie haben keine Zeile in {@code profil.spieler} (A25d); der Fremdschluessel von
     * {@code push_abo} schliesst sie ohne eigene Bedingung aus.
     *
     * <h2>{@code NOT EXISTS} und nicht {@code zusage = false}</h2>
     * Gesucht ist, <b>wer nicht geantwortet hat</b> - nicht, wer abgesagt hat. Eine Absage ist
     * eine Antwort; wer sie bekaeme, wuerde zu Recht fragen, warum.
     */
    private static final String SQL_EMPFAENGER_ERINNERUNG = """
            SELECT %s
              FROM profil.push_abo a
              JOIN profil.spieler  s ON s.id = a.spieler_id
             WHERE a.deaktiviert_am  IS NULL
               AND s.aktiv           = true
               AND s.rolle          <> 'ADMIN'
               AND s.push_erwuenscht = true
               AND NOT EXISTS (SELECT 1
                                 FROM spieltag.teilnahme t
                                WHERE t.termin_id  = :terminId
                                  AND t.spieler_id = s.id)
             ORDER BY a.spieler_id, a.id
            """.formatted(SPALTEN_VERSAND);

    /**
     * Empfaenger der Absage: wer zu diesem Termin zugesagt hat (A25b, Anlass 2; S8
     * Abschnitt 9.3).
     *
     * <h2>Warum hier kein {@code rolle <> 'ADMIN'} steht</h2>
     * Weil der {@code JOIN} es erledigt: Das Adminprofil kann nicht zusagen
     * ({@code 409 PROFIL_GESCHUETZT}) und hat deshalb nie eine Teilnahmezeile mit
     * {@code zusage = true}. <b>Eine zusaetzliche Bedingung waere hier nicht falsch, aber
     * irrefuehrend</b> - sie liesse vermuten, es gaebe einen Fall, in dem sie greift.
     *
     * <h2>Gaeste fallen heraus</h2>
     * Die Teilnahme eines Gastes traegt keine {@code spieler_id} ({@code ck_teilnahme_akteur}),
     * der {@code JOIN} findet sie also nicht. A25d verlangt genau das.
     *
     * <p>Wer abgesagt hat, bekommt nichts: Er weiss bereits, dass er nicht dabei ist.
     */
    private static final String SQL_EMPFAENGER_ABSAGE = """
            SELECT %s
              FROM profil.push_abo    a
              JOIN profil.spieler     s ON s.id = a.spieler_id
              JOIN spieltag.teilnahme t ON t.spieler_id = s.id
                                       AND t.termin_id  = :terminId
             WHERE a.deaktiviert_am  IS NULL
               AND s.aktiv           = true
               AND s.push_erwuenscht = true
               AND t.zusage          = true
             ORDER BY a.spieler_id, a.id
            """.formatted(SPALTEN_VERSAND);

    /**
     * Vermerkt einen erfolgreichen Versand.
     *
     * <p><b>{@code letzter_versand_am} belegt den Versuch, nicht die Zustellung.</b> Web Push
     * kennt keine Zustellbestaetigung: {@code 201} heisst "vom Dienst angenommen". Dieselbe
     * Einschraenkung wie bei {@code termin.halle_abgesagt_am} in S7.
     *
     * <p>{@code fehlversuche} faellt auf null zurueck - der Zaehler misst die Gesundheit der
     * Adresse, und eine erfolgreiche Nachricht ist der Beleg dafuer, dass sie lebt.
     */
    private static final String SQL_ERFOLG = """
            UPDATE profil.push_abo
               SET letzter_versand_am = :jetzt,
                   fehlversuche       = 0,
                   version            = version + 1
             WHERE id = :id
            """;

    /**
     * Zaehlt einen Fehlversuch und deaktiviert das Abonnement, wenn die Grenze erreicht ist.
     *
     * <h2>Eine Anweisung, keine zwei</h2>
     * <b>Die Entscheidung "ab fuenf Fehlversuchen deaktivieren" faellt in der Datenbank.</b>
     * Sie in Java nachzurechnen brauchte erst ein Lesen, dann ein Schreiben - und zwei
     * gleichzeitige Laeufe lasen dann denselben Zaehler und schrieben denselben Wert. Das
     * {@code CASE} sieht den Wert, den es selbst gerade erhoeht.
     *
     * <p><b>{@code deaktiviert_am} wird sonst nicht angetastet</b>, auch nicht auf {@code NULL}
     * gesetzt: Ein bereits deaktiviertes Abonnement bleibt es und behaelt seinen Zeitpunkt -
     * die Loeschfrist des Aufraeumlaufs haengt daran.
     */
    private static final String SQL_FEHLER = """
            UPDATE profil.push_abo
               SET fehlversuche   = fehlversuche + 1,
                   deaktiviert_am = CASE WHEN fehlversuche + 1 >= :grenze
                                         THEN :jetzt
                                         ELSE deaktiviert_am
                                    END,
                   version        = version + 1
             WHERE id = :id
            """;

    /**
     * Deaktiviert ein Abonnement sofort - die Antwort auf {@code 404} und {@code 410}.
     *
     * <p>{@code AND deaktiviert_am IS NULL} macht die Anweisung wiederholbar und haelt den
     * Zeitpunkt ehrlich: Ohne die Bedingung schoebe jeder weitere Lauf das Datum nach hinten,
     * und der Aufraeumlauf entfernte die Zeile nie.
     *
     * <p><b>Geloescht wird nicht</b>: {@code /push/abo/anlegen} heilt den Eintrag, sobald der
     * Browser ihn erneut anmeldet - und genau das tut der Client bei jedem Anwendungsstart.
     */
    private static final String SQL_DEAKTIVIEREN = """
            UPDATE profil.push_abo
               SET deaktiviert_am = :jetzt,
                   version        = version + 1
             WHERE id = :id
               AND deaktiviert_am IS NULL
            """;

    /**
     * Entfernt erloschene Abonnements endgueltig (Datenmodell 19; S8 Abschnitt 10.1).
     *
     * <p>Betroffen sind ausschliesslich Zeilen mit gesetztem {@code deaktiviert_am} - ein
     * aktives Abonnement wird nie entfernt, gleichgueltig wie alt es ist. Die Endpoint-Adresse
     * ist personenbezogen; eine Zeile, die niemandem mehr dient, gehoert weg.
     */
    private static final String SQL_ERLOSCHENE_ENTFERNEN = """
            DELETE FROM profil.push_abo
                  WHERE deaktiviert_am IS NOT NULL
                    AND deaktiviert_am < :grenze
            """;

    private final JdbcClient jdbc;

    public PushAboRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Legt ein Abonnement an oder frischt ein bestehendes auf.
     *
     * @param spielerId Eigentuemer aus der Sitzung, nie aus dem Anfragekoerper
     * @param endpoint  Adresse beim Push-Dienst
     * @param hash      SHA-256-Hex der Adresse, 64 Zeichen
     * @param p256dh    oeffentlicher Schluessel des Browsers, base64url
     * @param auth      Geheimnis des Abonnements, base64url
     * @param geraet    gekuerzter {@code User-Agent} oder {@code null}
     * @return Anzahl betroffener Zeilen; immer {@code 1}
     */
    public int anlegenOderAuffrischen(Long spielerId, String endpoint, String hash,
                                      String p256dh, String auth, String geraet) {
        return jdbc.sql(SQL_ANLEGEN)
                .param("spielerId", spielerId)
                .param("endpoint", endpoint)
                .param("hash", hash)
                .param("p256dh", p256dh)
                .param("auth", auth)
                .param("geraet", geraet)
                .update();
    }

    /**
     * Entfernt das Abonnement eines Geraets, sofern es dem Aufrufer gehoert.
     *
     * @param hash      SHA-256-Hex der Endpoint-Adresse
     * @param spielerId Aufrufer aus der Sitzung
     * @return Anzahl entfernter Zeilen; {@code 0}, wenn es die Adresse nicht gab oder sie
     *         einem anderen Spieler gehoert - <b>beide Faelle sind bewusst nicht
     *         unterscheidbar</b>, sonst wuerde die Antwort verraten, dass eine fremde Adresse
     *         existiert
     */
    public int entfernen(String hash, Long spielerId) {
        return jdbc.sql(SQL_ENTFERNEN)
                .param("hash", hash)
                .param("spielerId", spielerId)
                .update();
    }

    /**
     * Die aktiven Abonnements eines Spielers.
     *
     * @param spielerId betroffenes Profil
     * @return Abonnements, nach Id sortiert; leere Liste, wenn keines aktiv ist
     */
    public List<PushAbo> aktiveFuerSpieler(Long spielerId) {
        return jdbc.sql(SQL_AKTIVE_FUER_SPIELER)
                .param("spielerId", spielerId)
                .query(PushAboRepository::alsAbo)
                .list();
    }

    /**
     * Die Empfaenger der Erinnerung an eine offene Rueckmeldung.
     *
     * @param terminId betroffener Termin
     * @return Abonnements, nach Spieler und Id sortiert; leere Liste, wenn niemand in Frage
     *         kommt - <b>der haeufigste Fall und kein Fehler</b>
     */
    public List<PushAbo> empfaengerErinnerung(Long terminId) {
        return jdbc.sql(SQL_EMPFAENGER_ERINNERUNG)
                .param("terminId", terminId)
                .query(PushAboRepository::alsAbo)
                .list();
    }

    /**
     * Die Empfaenger der Terminabsage.
     *
     * @param terminId abgesagter Termin
     * @return Abonnements der Zusager, nach Spieler und Id sortiert
     */
    public List<PushAbo> empfaengerAbsage(Long terminId) {
        return jdbc.sql(SQL_EMPFAENGER_ABSAGE)
                .param("terminId", terminId)
                .query(PushAboRepository::alsAbo)
                .list();
    }

    /**
     * Vermerkt einen erfolgreichen Versand.
     *
     * @param id    betroffenes Abonnement
     * @param jetzt Zeitpunkt aus der {@code Clock}-Bean
     * @return Anzahl geaenderter Zeilen
     */
    public int erfolgVermerken(Long id, OffsetDateTime jetzt) {
        return jdbc.sql(SQL_ERFOLG).param("id", id).param("jetzt", jetzt).update();
    }

    /**
     * Zaehlt einen Fehlversuch und deaktiviert bei Erreichen der Grenze.
     *
     * @param id     betroffenes Abonnement
     * @param grenze Zahl der Fehlversuche, ab der deaktiviert wird
     * @param jetzt  Zeitpunkt aus der {@code Clock}-Bean
     * @return Anzahl geaenderter Zeilen
     */
    public int fehlerVermerken(Long id, int grenze, OffsetDateTime jetzt) {
        return jdbc.sql(SQL_FEHLER)
                .param("id", id)
                .param("grenze", grenze)
                .param("jetzt", jetzt)
                .update();
    }

    /**
     * Deaktiviert ein Abonnement, das beim Push-Dienst erloschen ist.
     *
     * @param id    betroffenes Abonnement
     * @param jetzt Zeitpunkt aus der {@code Clock}-Bean
     * @return Anzahl geaenderter Zeilen; {@code 0}, wenn es bereits deaktiviert war
     */
    public int deaktivieren(Long id, OffsetDateTime jetzt) {
        return jdbc.sql(SQL_DEAKTIVIEREN).param("id", id).param("jetzt", jetzt).update();
    }

    /**
     * Entfernt erloschene Abonnements jenseits der Aufbewahrungsfrist.
     *
     * @param grenze aeltester Deaktivierungszeitpunkt, der noch bleiben darf
     * @return Anzahl entfernter Zeilen
     */
    public int erloscheneEntfernen(OffsetDateTime grenze) {
        return jdbc.sql(SQL_ERLOSCHENE_ENTFERNEN).param("grenze", grenze).update();
    }

    /** Baut ein Abonnement aus einer Ergebniszeile. */
    private static PushAbo alsAbo(java.sql.ResultSet rs, int zeile) throws java.sql.SQLException {
        return new PushAbo(
                rs.getLong("id"),
                rs.getLong("spieler_id"),
                rs.getString("endpoint"),
                rs.getString("p256dh"),
                rs.getString("auth"));
    }
}
