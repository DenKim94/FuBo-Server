package de.fubo.appserver.domain.spieltag;

import java.util.List;

/**
 * Die Teilnehmer eines Termins samt der Grenzen, an denen sie zu messen sind
 * (A10, A11; S4 Abschnitt 7).
 *
 * <p><b>Die Grenzen gehoeren dazu, nicht nur die Liste.</b> {@code minTeilnehmer} und
 * {@code maxTeilnehmer} stammen aus {@code configs.app_config} und sind jederzeit aenderbar;
 * ohne sie liesse sich weder sagen, ab welcher Position jemand wartet, noch ob der Termin
 * ueberhaupt stattfindet. Sie werden bei jedem Abruf frisch gelesen - {@code ConfigService}
 * hat bewusst keinen Zwischenspeicher.
 *
 * <p><b>Nur Zusagen.</b> Wer abgesagt hat, steht nicht in der Liste; die Absage ist in der
 * eigenen Rueckmeldung des Terminobjekts sichtbar.
 *
 * @param minTeilnehmer Mindestteilnehmerzahl, unter der ein Termin nicht stattfindet (A10)
 * @param maxTeilnehmer Hoechstzahl; alles darueber ist Warteschlange (A11)
 * @param teilnehmer    die Zusagen in Meldereihenfolge, Position ab 1
 */
public record Teilnehmeruebersicht(short minTeilnehmer,
                                   short maxTeilnehmer,
                                   List<Teilnehmereintrag> teilnehmer) {

    /**
     * Beantwortet A10 unmittelbar: Ist die Mindestzahl erreicht?
     *
     * <p>Gezaehlt werden <b>alle</b> Zusagen, auch die in der Warteschlange. Wer wartet, hat
     * zugesagt und rueckt nach, sobald jemand absagt - er zaehlt also fuer die Frage, ob
     * genug Leute zusammenkommen.
     *
     * <p>Die Farbe des Balkens gehoert nicht in den Vertrag; der Server liefert die Tatsache.
     */
    public boolean mindestzahlErreicht() {
        return teilnehmer.size() >= minTeilnehmer;
    }

    /** Ein Eintrag wartet, sobald seine Position ueber der Hoechstzahl liegt. */
    public boolean wartet(Teilnehmereintrag eintrag) {
        return eintrag.position() > maxTeilnehmer;
    }
}
