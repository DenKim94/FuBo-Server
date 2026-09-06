package de.fubo.appserver.repository.spieltag;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Das Generierungskontingent je Nutzer und Teilnehmerstand (A15, S5 Abschnitt 6).
 *
 * <h2>Ohne Entity, und das ist hier mehr als eine Vorliebe</h2>
 * Die Tabelle wird ausschliesslich <b>bedingt</b> hochgezaehlt. Eine Entity mit
 * {@code @Version} meldete den Wettlauf zweier gleichzeitiger Klicks erst beim Schreiben und
 * verlangte eine Wiederholung; das bedingte {@code UPDATE} entscheidet ihn ohne. Derselbe
 * Griff wie bei {@code GastSlotRepository} und {@code TeilnahmeRepository}.
 *
 * <h2>Zurueckgesetzt wird nie</h2>
 * {@code teilnehmer_version} steht im Schluessel. Steigt der Zaehler - jemand sagt zu, ab,
 * eine Gast-Stufe oder ein Skillwert aendert sich -, passt keine bestehende Zeile mehr, und
 * der naechste {@code INSERT} legt eine neue mit {@code anzahl = 1} an. <b>Kein Loeschjob,
 * keine Aufraeumlogik, kein Wettlauf.</b> Preis ist bis zu eine Zeile je Nutzer und
 * Teilnehmeraenderung; bei 22 Teilnehmern und 30 Aenderungen sind das 660 Zeilen je Spieltag,
 * und {@code ON DELETE CASCADE} am Termin raeumt sie mit ihm ab.
 *
 * <h2>Der manuelle Lauf des Admins kommt hier nie an</h2>
 * A24 kostet kein Kontingent (0.6, Punkt 7): A15 zaehlt je <i>Termin</i> und
 * Teilnehmerstand, und {@code termin_id} ist {@code NOT NULL}. Schutz vor Dauerlaeufen sind
 * dort der Zugang (admin-only) und {@code MAX_EXHAUSTIV}.
 */
@Repository
public class KontingentRepository {

    /**
     * Verbucht einen Lauf, sofern das Kontingent noch offen ist - in <b>einer</b> Anweisung.
     *
     * <h2>Warum nicht lesen und dann schreiben</h2>
     * Zwei Nutzer, die gleichzeitig druecken, laesen beide {@code anzahl = 0} und kaemen beide
     * durch. Die Bedingung steht deshalb in der Datenbank und nicht in einem vorherigen
     * {@code SELECT}; PostgreSQL sperrt die Zeile beim Schreiben und wertet das {@code WHERE}
     * des {@code DO UPDATE} zum Schreibzeitpunkt aus. <b>Eine leere Ergebnismenge heisst
     * "erschoepft"</b> - derselbe Griff wie beim Gast-Slot in S2.
     *
     * <h2>{@code ON CONFLICT ON CONSTRAINT}, nicht ueber die Spaltenliste</h2>
     * {@code uq_kontingent} traegt {@code NULLS NOT DISTINCT} - ohne den Zusatz waeren zwei
     * Zeilen mit {@code akteur_gast_slot_id = NULL} verschieden und der Schutz liefe leer. Ueber
     * die Spaltenliste muesste er mit angegeben werden, und eine falsche Ableitung liefert
     * "no unique or exclusion constraint matching the ON CONFLICT specification".
     *
     * <h2>Die Tabelle steht im {@code DO UPDATE} voll qualifiziert</h2>
     * Sonst ist {@code anzahl} mehrdeutig - es koennte die bestehende Zeile oder die
     * vorgeschlagene ({@code EXCLUDED}) meinen. Dieselbe Falle wie beim Teilnahme-Upsert in S4.
     *
     * <p>Die Umwandlungen sind ausdruecklich notiert: Beide Akteurspalten duerfen {@code null}
     * sein, und PostgreSQL kann den Typ eines solchen Parameters sonst nicht bestimmen.
     */
    private static final String SQL_VERBRAUCHEN = """
            INSERT INTO spieltag.generierung_kontingent
                        (termin_id, akteur_spieler_id, akteur_gast_slot_id,
                         teilnehmer_version, anzahl)
                 VALUES (:terminId,
                         CAST(:spielerId AS bigint),
                         CAST(:gastSlotId AS smallint),
                         :teilnehmerVersion,
                         1)
            ON CONFLICT ON CONSTRAINT uq_kontingent DO UPDATE
                    SET anzahl = spieltag.generierung_kontingent.anzahl + 1
                  WHERE spieltag.generierung_kontingent.anzahl < :grenze
              RETURNING anzahl
            """;

    private final JdbcClient jdbc;

    public KontingentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Verbucht einen Lauf des genannten Akteurs, sofern das Kontingent noch offen ist.
     *
     * <p>Genau eine der beiden Akteurspalten ist gesetzt; {@code ck_kontingent_akteur} laesst
     * nichts anderes zu. Der Aufrufer entscheidet das anhand der Sitzung.
     *
     * @param terminId          betroffener Termin
     * @param spielerId         Profil-Id des Aufrufers oder {@code null} bei einem Gast
     * @param gastSlotId        belegter Gastplatz oder {@code null} bei einem Spieler
     * @param teilnehmerVersion Stand des Teilnehmerkreises; Teil des Schluessels
     * @param grenze            {@code configs.app_config.anz_team_generator}
     * @return die neue Anzahl; {@link Optional#empty()} heisst {@code KONTINGENT_ERSCHOEPFT}
     */
    public Optional<Short> verbrauche(Long terminId, Long spielerId, Short gastSlotId,
                                      int teilnehmerVersion, short grenze) {
        return jdbc.sql(SQL_VERBRAUCHEN)
                .param("terminId", terminId)
                .param("spielerId", spielerId)
                .param("gastSlotId", gastSlotId)
                .param("teilnehmerVersion", teilnehmerVersion)
                .param("grenze", grenze)
                .query(Short.class)
                .optional();
    }
}
