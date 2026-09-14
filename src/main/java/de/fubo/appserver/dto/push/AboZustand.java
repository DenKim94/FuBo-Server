package de.fubo.appserver.dto.push;

/**
 * Antwort von {@code /push/abo/anlegen} und {@code /push/abo/entfernen} (A25c).
 *
 * <h2>Warum die Antwort nicht leer ist</h2>
 * Ein {@code 204} waere kuerzer und liesse den Client raten, was jetzt gilt. <b>Das Feld
 * benennt den erreichten Zustand</b>, nicht den Vorgang: Nach dem Anlegen {@code true}, nach
 * dem Entfernen {@code false} - <i>auch dann</i>, wenn gar keine Zeile betroffen war. Beide
 * Aufrufe sind idempotent, und beide Male ist die Aussage dieselbe: So steht es jetzt.
 *
 * <p><b>Es ist die Geraeteebene und nicht die Person.</b> {@code /push/status/lesen} liefert
 * die beiden serverseitigen Ebenen; diese hier betrifft ausschliesslich das aufrufende Geraet.
 *
 * @param aboVorhanden ob fuer dieses Geraet jetzt ein aktives Abonnement besteht
 */
public record AboZustand(boolean aboVorhanden) {

    /** Der Zustand nach dem Anlegen oder Auffrischen. */
    public static AboZustand vorhanden() {
        return new AboZustand(true);
    }

    /** Der Zustand nach dem Widerruf - auch dann, wenn es nichts zu widerrufen gab. */
    public static AboZustand entfernt() {
        return new AboZustand(false);
    }
}
