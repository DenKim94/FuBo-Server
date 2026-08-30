package de.fubo.appserver.domain.spieltag;

/**
 * Ein zugesagter Teilnehmer eines Termins samt seiner Position (S4, Abschnitt 7.3).
 *
 * <p><b>Die Position wird abgeleitet, nie gespeichert</b> - {@code row_number()} ueber
 * {@code ORDER BY gemeldet_am, id}. Eine Positionsspalte muesste bei jeder Absage neu
 * durchnummeriert werden, mit Schreiblast und einem Wettlauf bei parallelen Meldungen; sie
 * traege dabei keine Information, die sich nicht ohnehin aus der Meldezeit ergibt. Die
 * Festlegung steht seit dem 02.08.2026 im Datenmodell.
 *
 * <p><b>Ohne Skillwerte und ohne die Gast-Stufe.</b> Die Liste erreicht jede Rolle, auch
 * {@code GAST}; A12 verlangt, dass Bewertungen den Server dorthin nicht verlassen. Die
 * Selbsteinschaetzung eines Gastes ist eine solche Bewertung - sie steht in
 * {@code teilnahme.gast_stufe} und bleibt dort.
 *
 * <p><b>Ob jemand wartet, steht hier nicht.</b> Das ergibt sich erst aus dem Vergleich mit
 * {@code max_teilnehmer} und wird beim Bauen des DTOs berechnet: Die Grenze ist
 * Konfiguration und aendert sich ohne Zutun der Teilnehmer.
 *
 * @param id          technischer Schluessel der Teilnahme
 * @param spielerId   Profil-Id; {@code null} bei einem Gast
 * @param gastName    temporaerer Name des Gastes; {@code null} bei einem Spieler
 * @param anzeigeName Name aus {@code profil.spieler} beziehungsweise der Gastname
 * @param position    Rang in der Meldereihenfolge, beginnend bei 1
 */
public record Teilnehmereintrag(Long id,
                                Long spielerId,
                                String gastName,
                                String anzeigeName,
                                int position) {

    /** Ein Gast hat kein Profil - das Anhaengen von "(Gast)" ist Sache des Frontends (A8). */
    public boolean istGast() {
        return spielerId == null;
    }
}
