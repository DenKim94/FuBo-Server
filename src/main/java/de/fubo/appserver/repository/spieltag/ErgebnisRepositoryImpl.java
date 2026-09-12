package de.fubo.appserver.repository.spieltag;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * JDBC-Implementierung des handgeschriebenen Repository-Teils.
 *
 * <p><b>Der Klassenname ist nicht frei waehlbar:</b> Spring Data findet die Implementierung
 * ausschliesslich ueber die Konvention "Name des Repository-Interface + Impl". Eine Klasse
 * mit anderem Namen wird stillschweigend ignoriert - das Repository laesst sich dann zwar
 * erzeugen, der Aufruf scheitert aber zur Laufzeit.
 */
class ErgebnisRepositoryImpl implements ErgebnisRepositoryCustom {

    /**
     * Der erste Eintrag gilt - in einer Anweisung (A21, S6 Abschnitt 2.3).
     *
     * <p><b>{@code ON CONFLICT ON CONSTRAINT}, nicht ueber die Spaltenliste.</b> Der benannte
     * Constraint macht sichtbar, welche Bedingung gemeint ist, und bricht auffaellig, falls
     * sie einmal umbenannt wird. Dieselbe Wahl wie beim Anlegen eines Termins und beim
     * Generierungskontingent.
     *
     * <p><b>{@code RETURNING id} ist der Traeger der Entscheidung:</b> Eine leere
     * Ergebnismenge heisst "der Termin hat schon ein Ergebnis". Ohne {@code RETURNING} bliebe
     * nur die Zahl der betroffenen Zeilen - die stimmt zwar auch, sagt aber nichts darueber,
     * welche Zeile entstanden ist.
     *
     * <p>Die Umwandlungen sind ausdruecklich notiert: {@code sieger} ist {@code CHAR(1)},
     * {@code erfasst_von_spieler_id} und der Gastname duerfen {@code null} sein, und
     * PostgreSQL kann den Typ eines solchen Parameters sonst nicht bestimmen.
     *
     * <p>{@code 'unbekannt'} als letzte Stufe des {@code COALESCE} ist kein erwarteter Fall -
     * die Spalte ist {@code NOT NULL}, und eine Sitzung ohne Profil und ohne Gastnamen laesst
     * die Filterchain nicht durch. Er steht da, damit ein solcher Zustand eine lesbare Zeile
     * hinterlaesst statt eines Constraint-Verstosses.
     */
    private static final String SQL_EINFUEGEN = """
            INSERT INTO spieltag.ergebnis
                        (termin_id, sieger, deutlich,
                         erfasst_von_spieler_id, erfasst_von_bezeichnung)
                 VALUES (:terminId,
                         CAST(:sieger AS char(1)),
                         :deutlich,
                         CAST(:spielerId AS bigint),
                         COALESCE((SELECT s.name FROM profil.spieler s
                                    WHERE s.id = CAST(:spielerId AS bigint)),
                                  CAST(:gastName AS varchar),
                                  'unbekannt'))
            ON CONFLICT ON CONSTRAINT uq_ergebnis_termin DO NOTHING
              RETURNING id
            """;

    private final JdbcClient jdbc;

    ErgebnisRepositoryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> einfuegen(Long terminId, char sieger, boolean deutlich,
                                    Long spielerId, String gastName) {
        return jdbc.sql(SQL_EINFUEGEN)
                .param("terminId", terminId)
                .param("sieger", String.valueOf(sieger))
                .param("deutlich", deutlich)
                .param("spielerId", spielerId)
                .param("gastName", gastName)
                .query(Long.class)
                .optional();
    }
}
