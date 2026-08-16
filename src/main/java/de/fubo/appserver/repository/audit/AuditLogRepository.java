package de.fubo.appserver.repository.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/**
 * Schreibzugriff auf {@code profil.audit_log}.
 *
 * <p><b>Warum kein Spring-Data-Repository?</b> Das Audit-Log wird ausschliesslich
 * angehaengt und nie ueber JPA gelesen oder geaendert. Eine Entity braechte zwei Nachteile
 * ohne Nutzen: Die Spalte {@code details} ist {@code jsonb} und benoetigte eine eigene
 * Typabbildung, und ein verwaltetes Objekt im Persistence-Context legte nahe, dass sich
 * Eintraege nachtraeglich aendern liessen - genau das soll ein Audit-Log nicht.
 * Vorbild ist {@code SessionRepositoryImpl}, das aus demselben Grund direkt JDBC nutzt.
 */
@Repository
public class AuditLogRepository {

    /**
     * Die Umwandlungen sind ausdruecklich notiert ({@code CAST(... AS ...)}), nicht nur
     * beim {@code jsonb}: PostgreSQL kann den Typ eines Parameters, der {@code NULL} sein
     * darf, sonst nicht bestimmen und bricht mit "could not determine data type of
     * parameter" ab.
     */
    private static final String SQL_EINFUEGEN = """
            INSERT INTO profil.audit_log
                        (akteur_spieler_id, akteur_bezeichnung, aktion, entitaet, entitaet_id, details)
                 VALUES (CAST(:akteurSpielerId AS bigint),
                         :akteurBezeichnung,
                         :aktion,
                         CAST(:entitaet AS varchar),
                         CAST(:entitaetId AS bigint),
                         CAST(:details AS jsonb))
            """;

    /**
     * Loescht Eintraege jenseits der Aufbewahrungsfrist.
     *
     * <p>Der Stichtag wird als Parameter uebergeben und nicht in SQL gerechnet
     * ({@code now() - interval …}), damit die Frist an einer Stelle steht: in der
     * Konfiguration. Ein zweiter Ort fuer dieselbe Zahl liefe frueher oder spaeter
     * auseinander.
     */
    private static final String SQL_LOESCHEN = """
            DELETE FROM profil.audit_log
             WHERE zeitpunkt < :stichtag
            """;

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Haengt einen Eintrag an. Der Zeitpunkt kommt aus dem Spaltendefault
     * ({@code now()}) - damit gilt fuer alle Eintraege dieselbe Uhr.
     *
     * @param akteurSpielerId   Profil-Id des Handelnden oder {@code null}, wenn keine
     *                          Identitaet feststeht (etwa beim PIN-Fehlversuch)
     * @param akteurBezeichnung sprechende Bezeichnung des Handelnden, hoechstens 60 Zeichen
     * @param aktion            Vorgang, hoechstens 50 Zeichen
     * @param entitaet          betroffener Objekttyp oder {@code null}
     * @param entitaetId        betroffene Objekt-Id oder {@code null}
     * @param detailsJson       zusaetzliche Angaben als JSON-Text oder {@code null}
     */
    public void einfuegen(Long akteurSpielerId, String akteurBezeichnung, String aktion,
                          String entitaet, Long entitaetId, String detailsJson) {
        jdbc.sql(SQL_EINFUEGEN)
                .param("akteurSpielerId", akteurSpielerId)
                .param("akteurBezeichnung", akteurBezeichnung)
                .param("aktion", aktion)
                .param("entitaet", entitaet)
                .param("entitaetId", entitaetId)
                .param("details", detailsJson)
                .update();
    }

    /**
     * Entfernt Eintraege, die aelter sind als der Stichtag.
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
