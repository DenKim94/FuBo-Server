package de.fubo.appserver.domain.spieltag;

/**
 * Das Generierungskontingent der aufrufenden Sitzung an einem Termin (A15), wie die
 * Einzelansicht es ausweist.
 *
 * <p><b>Eine Anzeige und keine Zusicherung.</b> Der Wert ist eine Momentaufnahme; durchgesetzt
 * wird das Kontingent allein von {@code KontingentRepository#verbrauche}, einem bedingten
 * {@code UPDATE}. Zwei gleichzeitige Laeufe klaert weiterhin die Datenbank, nicht diese Zahl.
 *
 * @param erlaubt Laeufe je Nutzer, je Termin und je Kontingentstand
 *                ({@code configs.app_config.anz_team_generator})
 * @param uebrig  {@code max(0, erlaubt - verbraucht)}. <b>Nie negativ:</b> Senkt der Admin die
 *                Grenze unter die verbrauchte Anzahl, steht hier {@code 0}. {@code 0} heisst,
 *                dass ein Lauf jetzt - sofern nichts Frueheres greift - an
 *                {@code KONTINGENT_ERSCHOEPFT} scheitert
 */
public record Restkontingent(int erlaubt, int uebrig) {
}
