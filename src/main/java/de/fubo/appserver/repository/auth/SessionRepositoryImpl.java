package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import org.springframework.jdbc.core.simple.JdbcClient;

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
            RETURNING id, spieler_id, gast_name, rolle, stage
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
                .query((rs, zeile) -> {
                    // getObject(..., Long.class) statt getLong(...): getLong liefert bei
                    // NULL eine 0 - und 0 waere eine gueltig aussehende Spieler-Id.
                    Long spielerId = rs.getObject("spieler_id", Long.class);
                    String rolleText = rs.getString("rolle");
                    return new AktiveSitzung(
                            rs.getLong("id"),
                            spielerId,
                            rs.getString("gast_name"),
                            // In der Stufe PIN_VERIFIED ist die Rolle NULL.
                            rolleText == null ? null : Rolle.valueOf(rolleText),
                            Stage.valueOf(rs.getString("stage")));
                })
                .optional();
    }
}
