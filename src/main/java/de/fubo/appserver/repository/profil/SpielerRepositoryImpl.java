package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.profil.NamensEintrag;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * JDBC-Implementierung des handgeschriebenen Repository-Teils.
 *
 * <p><b>Der Klassenname ist nicht frei waehlbar:</b> Spring Data findet die Implementierung
 * ausschliesslich ueber die Konvention "Name des Repository-Interface + Impl". Eine Klasse
 * mit anderem Namen wird stillschweigend ignoriert - das Repository laesst sich dann zwar
 * erzeugen, der Aufruf scheitert aber zur Laufzeit.
 */
class SpielerRepositoryImpl implements SpielerRepositoryCustom {

    /**
     * Der Belegtstatus wird nicht gespeichert, sondern aus den aktiven Sitzungen abgeleitet
     * (A6). Damit kann er nicht veralten: Laeuft eine Sitzung ab oder wird sie widerrufen,
     * ist der Name im naechsten Abruf wieder frei, ganz ohne Aufraeumschritt.
     *
     * <p>{@code EXISTS} statt {@code JOIN} oder {@code count(*)}: PostgreSQL bricht die
     * Unterabfrage beim ersten Treffer ab, und ein {@code JOIN} lieferte je aktiver Sitzung
     * eine Zeile - der Name erschiene dann mehrfach.
     *
     * <p>Die drei Bedingungen der Unterabfrage sind dieselben wie in der Sitzungspruefung
     * ({@code SessionRepositoryImpl}). Der partielle Index {@code ix_session_aktiv}
     * ({@code WHERE widerrufen_am IS NULL}) haelt sie klein, auch wenn die Tabelle mit
     * abgelaufenen Sitzungen waechst.
     *
     * <p>{@code now()} ist die Datenbankuhr - dieselbe, gegen die auch die Sitzung geprueft
     * wird. Eine abweichende JVM-Uhr kann den Belegtstatus damit nicht verfaelschen.
     */
    private static final String SQL_NAMENSLISTE = """
            SELECT s.id,
                   s.name,
                   EXISTS (SELECT 1
                             FROM profil.session se
                            WHERE se.spieler_id = s.id
                              AND se.widerrufen_am IS NULL
                              AND se.gueltig_bis > now()
                              AND se.absolut_gueltig_bis > now()) AS belegt
              FROM profil.spieler s
             WHERE s.aktiv
             ORDER BY s.name
            """;

    private final JdbcClient jdbc;

    SpielerRepositoryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NamensEintrag> findeNamensliste() {
        return jdbc.sql(SQL_NAMENSLISTE)
                .query((rs, zeile) -> new NamensEintrag(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBoolean("belegt")))
                .list();
    }
}
