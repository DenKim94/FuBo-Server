package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.config.AppConfig;

import java.time.OffsetDateTime;

/**
 * Antwortobjekt von {@code GET /api/v1/admin/config/lesen} (S3, Abschnitt 5).
 *
 * <h2>Elf aenderbare Felder, zwei Zusatzangaben</h2>
 * Die ersten elf Komponenten stehen in derselben Reihenfolge und unter denselben Namen wie in
 * {@link KonfigurationAendernRequest}: Der Client laedt diese Antwort, aendert einzelne Werte im
 * Formular und schickt das veraenderte Ganze zurueck. Waeren die Namen verschieden, muesste er
 * eine Umbenennungstabelle pflegen.
 *
 * <p>Dazu kommen {@code geaendertAm} und {@code version}. <b>{@code geaendertVon} bleibt
 * draussen:</b> Es gibt genau einen Admin ({@code uq_spieler_genau_ein_admin}), die Auskunft
 * "geaendert von 1" waere inhaltsleer. Wer wirklich wissen will, wer wann was geaendert hat,
 * liest das Audit-Log - dort steht auch der vorherige Wert.
 *
 * <h2>Warum die Version nach aussen geht</h2>
 * {@code /admin/config/aendern} ist ein Voll-Update und verlangt sie zurueck. Ohne diesen Wert
 * ueberschriebe der zuletzt gespeicherte Browser-Tab die Aenderungen des anderen lautlos - ein
 * Szenario, das auch mit einem einzigen Admin vorkommt. Der Wert ist kein Geheimnis: Er zaehlt
 * Schreibvorgaenge und verraet nichts ueber ihren Inhalt.
 *
 * <h2>Kurzform statt {@code int}</h2>
 * Die Spalten sind {@code SMALLINT}; die Entity fuehrt sie als {@code short}. Das DTO uebernimmt
 * den Typ, damit die Kette von der Spalte bis zur API-Grenze denselben Wertebereich hat. In JSON
 * ist der Unterschied unsichtbar - dort steht in beiden Faellen eine Zahl.
 *
 * @param minTeilnehmer          Mindestteilnehmerzahl (A10)
 * @param maxTeilnehmer          Hoechstteilnehmerzahl (A11)
 * @param anzGuests              Obergrenze gleichzeitig angemeldeter Gaeste (A17)
 * @param algorithmType          Verfahren der Teamgenerierung (A15)
 * @param auswechselModus        Auswahl des Auswechselspielers bei ungerader Zahl (A20b)
 * @param anzTeamGenerator       Kontingent an Generierungslaeufen je Nutzer und Spieltag (A15)
 * @param sessionLeerlaufMinuten gleitendes Leerlauf-Fenster in Minuten (A14)
 * @param sessionMaximalStunden  harte Obergrenze der Sitzungsdauer in Stunden (A14)
 * @param halleEmail             Empfaengeradresse des Hallenbetreibers oder {@code null} (A23)
 * @param halleAbsageVorlage     vordefinierter Absagetext oder {@code null} (A23)
 * @param halleVorlaufStunden    Vorlauf, bis zu dem eine Absage zulaessig ist (A23)
 * @param geaendertAm            Zeitpunkt des letzten Speichervorgangs
 * @param version                Stand des Datensatzes; Eingabewert von {@code aendern}
 */
public record Konfiguration(short minTeilnehmer,
                            short maxTeilnehmer,
                            short anzGuests,
                            AlgorithmType algorithmType,
                            AuswechselModus auswechselModus,
                            short anzTeamGenerator,
                            short sessionLeerlaufMinuten,
                            short sessionMaximalStunden,
                            String halleEmail,
                            String halleAbsageVorlage,
                            short halleVorlaufStunden,
                            OffsetDateTime geaendertAm,
                            Long version) {

    /**
     * Uebersetzt die Entity in das Antwortobjekt der API-Grenze.
     *
     * <p>Die Abbildung steht hier und nicht im Dienst - dieselbe Aufteilung wie bei
     * {@link SkillKategorieInfo#von} und {@link SpielerDetails#von}: Der Dienst soll nicht wissen
     * muessen, wie der Vertrag aussieht. Damit bleibt zugleich die Regel gewahrt, dass eine
     * JPA-Entity die API-Grenze nie verlaesst; sie wird hier gelesen, nicht weitergereicht.
     *
     * <p><b>Ein Zwischentyp waere hier Ballast.</b> Bei den Profilen steht zwischen Abfrage und
     * DTO ein Wertobjekt, weil die Abfrage mehr liefert, als nach aussen darf. Die Konfiguration
     * hat nichts Geheimes: Von fuenfzehn Spalten bleiben nur {@code id} und {@code geaendertVon}
     * draussen, und beide sind inhaltsleer statt vertraulich.
     */
    public static Konfiguration von(AppConfig konfiguration) {
        return new Konfiguration(
                konfiguration.getMinTeilnehmer(),
                konfiguration.getMaxTeilnehmer(),
                konfiguration.getAnzGuests(),
                konfiguration.getAlgorithmType(),
                konfiguration.getAuswechselModus(),
                konfiguration.getAnzTeamGenerator(),
                konfiguration.getSessionLeerlaufMinuten(),
                konfiguration.getSessionMaximalStunden(),
                konfiguration.getHalleEmail(),
                konfiguration.getHalleAbsageVorlage(),
                konfiguration.getHalleVorlaufStunden(),
                konfiguration.getGeaendertAm(),
                konfiguration.getVersion());
    }
}
