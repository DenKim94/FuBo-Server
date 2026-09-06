package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.GastStufe;
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
     * <p><b>Der Gastplatz kommt als Unterabfrage in der RETURNING-Liste</b>, nicht ueber
     * einen JOIN: UPDATE ... FROM verbindet wie ein INNER JOIN, und eine Spielersitzung -
     * die keinen Gastplatz hat - wuerde dann gar nicht mehr aktualisiert. Die Sitzung waere
     * ungueltig, sobald jemand ohne Gastplatz sie benutzt. uq_gast_slot_session sichert zu,
     * dass die Unterabfrage hoechstens eine Zeile liefert.
     *
     * <p>now() ist die Datenbankuhr. Alle Ablaufzeitpunkte werden damit gegen dieselbe
     * Uhr geprueft; eine abweichende JVM-Uhr kann das Ergebnis nicht verfaelschen.
     * Innerhalb einer Transaktion ist now() konstant (transaction_timestamp), alle vier
     * Bedingungen beziehen sich also auf denselben Zeitpunkt.
     */
    private static final String SQL_PRUEFEN_UND_VERLAENGERN = """
            UPDATE profil.session s
               SET letzte_aktivitaet_am = now(),
                   gueltig_bis = LEAST(
                       now() + make_interval(mins => CAST(:leerlauf AS integer)),
                       s.absolut_gueltig_bis)
             WHERE s.token_hash = :hash
               AND s.widerrufen_am IS NULL
               AND s.gueltig_bis > now()
               AND s.absolut_gueltig_bis > now()
            RETURNING s.id, s.spieler_id, s.gast_name, s.gast_stufe, s.rolle, s.stage,
                      s.gueltig_bis, s.absolut_gueltig_bis,
                      (SELECT gs.id FROM profil.gast_slot gs WHERE gs.session_id = s.id)
                          AS gast_slot_id
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
            SELECT s.id, s.spieler_id, s.gast_name, s.gast_stufe, s.rolle, s.stage,
                   s.gueltig_bis, s.absolut_gueltig_bis,
                   (SELECT gs.id FROM profil.gast_slot gs WHERE gs.session_id = s.id)
                       AS gast_slot_id
              FROM profil.session s
             WHERE s.token_hash = :hash
               AND s.widerrufen_am IS NULL
               AND s.gueltig_bis > now()
               AND s.absolut_gueltig_bis > now()
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
        // gast_stufe ist NULL-faehig und bei Spieler- und PIN_VERIFIED-Sitzungen leer;
        // valueOf(null) liefe in eine NullPointerException.
        String stufeText = rs.getString("gast_stufe");
        return new AktiveSitzung(
                rs.getLong("id"),
                spielerId,
                rs.getString("gast_name"),
                stufeText == null ? null : GastStufe.valueOf(stufeText),
                // Wie bei spieler_id: getShort lieferte fuer NULL eine 0, und 0 ist keine
                // gueltige Platznummer - gast_slot beginnt bei 1.
                rs.getObject("gast_slot_id", Short.class),
                // In der Stufe PIN_VERIFIED ist die Rolle NULL.
                rolleText == null ? null : Rolle.valueOf(rolleText),
                Stage.valueOf(rs.getString("stage")),
                rs.getObject("gueltig_bis", OffsetDateTime.class),
                rs.getObject("absolut_gueltig_bis", OffsetDateTime.class));
    };
}
