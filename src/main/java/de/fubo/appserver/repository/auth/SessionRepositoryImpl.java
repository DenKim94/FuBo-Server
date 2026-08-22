package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * JDBC-Implementierung des handgeschriebenen Repository-Teils.
 *
 * <p><b>Der Klassenname ist nicht frei waehlbar:</b> Spring Data findet die Implementierung
 * ausschliesslich ueber die Konvention "Name des Repository-Interface + Impl". Eine Klasse
 * namens {@code SessionRepositoryCustomImpl} oder {@code JdbcSessionRepository} wird
 * stillschweigend ignoriert.
 */
class SessionRepositoryImpl implements SessionRepositoryCustom {

    /**
     * Prueft vier Bedingungen und verlaengert das Leerlauf-Fenster in einem Statement.
     *
     * <p>Zwei getrennte Anweisungen ("erst lesen, dann aktualisieren") liessen zwischen
     * Lesen und Schreiben ein Fenster offen, in dem die Sitzung ablaufen oder widerrufen
     * werden kann. PostgreSQL sperrt die Zeile beim Schreiben und wertet die
     * WHERE-Bedingung zum Schreibzeitpunkt aus - kommt keine Zeile zurueck, ist die
     * Sitzung ungueltig, aus welchem Grund auch immer.
     *
     * <p>LEAST(...) ist nicht optional: Ohne den Deckel wanderte gueltig_bis ueber
     * absolut_gueltig_bis hinaus. Die Sitzung waere zwar trotzdem ungueltig, weil die
     * Pruefung beide Spalten abfragt, aber die Daten waeren widerspruechlich.
     *
     * <p>now() ist die Datenbankuhr. Alle Ablaufzeitpunkte werden damit gegen dieselbe
     * Uhr geprueft; eine abweichende JVM-Uhr kann das Ergebnis nicht verfaelschen.
     * Innerhalb einer Transaktion ist now() konstant (transaction_timestamp), alle vier
     * Bedingungen beziehen sich also auf denselben Zeitpunkt.
     */
    private static final String SQL_PRUEFEN_UND_VERLAENGERN = """
            UPDATE profil.session
               SET letzte_aktivitaet_am = now(),
                   gueltig_bis = LEAST(
                       now() + make_interval(mins => CAST(:leerlauf AS integer)),
                       absolut_gueltig_bis)
             WHERE token_hash = :hash
               AND widerrufen_am IS NULL
               AND gueltig_bis > now()
               AND absolut_gueltig_bis > now()
            RETURNING id, spieler_id, gast_name, rolle, stage, gueltig_bis, absolut_gueltig_bis
            """;

    /**
     * Rein lesendes Gegenstueck fuer Hintergrundaufrufe (Abschnitt 10.8).
     *
     * <p>Dieselben vier Bedingungen, aber ohne Schreibzugriff: Weder wandert
     * {@code gueltig_bis} nach hinten noch wird {@code letzte_aktivitaet_am} gesetzt. Der
     * Aufruf zaehlt damit nicht als Nutzeraktivitaet.
     *
     * <p>Die Wettlaufsituation, wegen der der schreibende Pfad ein einziges Statement ist,
     * gibt es hier nicht: Es wird nichts geaendert, und laeuft die Sitzung eine
     * Millisekunde nach dem Lesen ab, ist die Auskunft "gueltig" fuer genau diesen
     * Hintergrundaufruf richtig gewesen. Der naechste Aufruf sieht den Ablauf.
     */
    private static final String SQL_PRUEFEN = """
            SELECT id, spieler_id, gast_name, rolle, stage, gueltig_bis, absolut_gueltig_bis
              FROM profil.session
             WHERE token_hash = :hash
               AND widerrufen_am IS NULL
               AND gueltig_bis > now()
               AND absolut_gueltig_bis > now()
            """;

    private final JdbcClient jdbc;

    SessionRepositoryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AktiveSitzung> pruefenUndVerlaengern(String tokenHash, int leerlaufMinuten) {
        return jdbc.sql(SQL_PRUEFEN_UND_VERLAENGERN)
                .param("hash", tokenHash)
                .param("leerlauf", leerlaufMinuten)
                .query(ZEILEN_ABBILDUNG)
                .optional();
    }

    @Override
    public Optional<AktiveSitzung> pruefen(String tokenHash) {
        return jdbc.sql(SQL_PRUEFEN)
                .param("hash", tokenHash)
                .query(ZEILEN_ABBILDUNG)
                .optional();
    }

    /**
     * Bildet eine Ergebniszeile auf {@link AktiveSitzung} ab.
     *
     * <p>Gemeinsam fuer beide Abfragen: Die Spaltenliste ist identisch, und zwei getrennte
     * Abbildungen liefen frueher oder spaeter auseinander - der lesende Pfad lieferte dann
     * andere Werte als der schreibende, obwohl beide dieselbe Zeile meinen.
     */
    private static final RowMapper<AktiveSitzung> ZEILEN_ABBILDUNG = (rs, zeile) -> {
        // getObject(..., Long.class) statt getLong(...): getLong liefert bei NULL eine 0 -
        // und 0 waere eine gueltig aussehende Spieler-Id.
        Long spielerId = rs.getObject("spieler_id", Long.class);
        String rolleText = rs.getString("rolle");
        return new AktiveSitzung(
                rs.getLong("id"),
                spielerId,
                rs.getString("gast_name"),
                // In der Stufe PIN_VERIFIED ist die Rolle NULL.
                rolleText == null ? null : Rolle.valueOf(rolleText),
                Stage.valueOf(rs.getString("stage")),
                rs.getObject("gueltig_bis", OffsetDateTime.class),
                rs.getObject("absolut_gueltig_bis", OffsetDateTime.class));
    };
}
