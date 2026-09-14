package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.utils.Absagevorlage;

import java.time.OffsetDateTime;

/**
 * Antwortobjekt von {@code GET /api/v1/admin/config/lesen} (S3, Abschnitt 5).
 *
 * <h2>Vierzehn aenderbare Felder, drei Zusatzangaben</h2>
 * Die vierzehn aenderbaren Komponenten stehen in derselben Reihenfolge und unter denselben Namen wie in
 * {@link KonfigurationAendernRequest}: Der Client laedt diese Antwort, aendert einzelne Werte im
 * Formular und schickt das veraenderte Ganze zurueck. Waeren die Namen verschieden, muesste er
 * eine Umbenennungstabelle pflegen.
 *
 * <p><b>{@code halleAbsageVorlageEffektiv} steht dazwischen und ist trotzdem nicht aenderbar.</b>
 * Es steht neben der Vorlage, auf die es sich bezieht - wer im Formular das Textfeld leert, soll
 * daneben sehen, was der Hallenbetreiber stattdessen bekaeme, und nicht erst an dessen Rueckruf.
 * Zurueckgeschickt wird es nicht ausgewertet: {@link KonfigurationAendernRequest} kennt das Feld
 * nicht, und unbekannte Eigenschaften laesst die Serialisierung fallen. <b>Beides zu speichern
 * hoebe das Leeren der Vorlage auf</b> - und genau dafuer ist das Voll-Update gebaut.
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
 * @param halleAbsageVorlageEffektiv der Fliesstext, den die Absage tatsaechlich verwenden
 *                               wuerde: die gepflegte Vorlage oder der Ersatz des Servers (A23).
 *                               <b>Nur lesbar</b> - er gehoert nicht in
 *                               {@link KonfigurationAendernRequest}
 * @param halleVorlaufStunden    Vorlauf, bis zu dem eine Absage zulaessig ist (A23)
 * @param hallenModusAktiv       Hauptschalter des Hallenmodus (A23); steht er aus, lehnt
 *                               {@code /admin/halle/absagen} jede Absage ab
 * @param pushAktiv              Hauptschalter des Push-Versands (A25e); Anlagenebene der drei
 *                               Versandbedingungen. Vorgabe {@code true} - anders als beim
 *                               Hallenmodus, weil eine Push-Nachricht nur erreicht, wer im
 *                               Browserdialog zugestimmt hat
 * @param pushErinnerungStunden  Vorlauf der Erinnerung an offene Rueckmeldungen in Stunden
 *                               (A25); gilt anwendungsweit, nicht je Termin
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
                            String halleAbsageVorlageEffektiv,
                            short halleVorlaufStunden,
                            boolean hallenModusAktiv,
                            boolean pushAktiv,
                            short pushErinnerungStunden,
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
     * hat nichts Geheimes: Von achtzehn Spalten bleiben nur {@code id} und {@code geaendertVon}
     * draussen, und beide sind inhaltsleer statt vertraulich.
     *
     * <p><b>Die Ersatzvorlage wird hier eingesetzt und nicht im Dienst</b>: Die Entscheidung,
     * welcher Text gilt, faellt in {@link Absagevorlage#wirksam} und damit an genau einer
     * Stelle - derselben, aus der auch der Absagepfad schoepft. Zwei Orte gaeben dem Formular
     * und dem Hallenbetreiber verschiedene Texte.
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
                Absagevorlage.wirksam(konfiguration.getHalleAbsageVorlage()),
                konfiguration.getHalleVorlaufStunden(),
                konfiguration.isHallenModusAktiv(),
                konfiguration.isPushAktiv(),
                konfiguration.getPushErinnerungStunden(),
                konfiguration.getGeaendertAm(),
                konfiguration.getVersion());
    }
}
