package de.fubo.appserver.domain.spieltag;

/**
 * Eine Absage zu einem Termin (Ergaenzung vom 06.11.2026).
 *
 * <p><b>Ohne Position und ohne Wartestatus.</b> Beides gehoert zur Warteschlange, und in der
 * steht nur, wer zugesagt hat. Eine Absage hat auch keinen Zeitpunkt, nach dem sich sortieren
 * liesse: {@code gemeldet_am} bleibt bei einer Absage unberuehrt und traegt dann den Zeitpunkt
 * der letzten Zusage. Die Liste ist deshalb alphabetisch.
 *
 * <p><b>Ohne Gast-Stufe, auch fuer den Admin.</b> Die Stufe braucht er an der Zusage, die der
 * Generator rechnet; eine Absage rechnet niemand.
 *
 * <p><b>Der Name unterscheidet sich vom DTO {@code dto.spieltag.AbsageEintrag} nur in der
 * Schreibweise</b> - wie {@link Teilnehmereintrag} und {@code TeilnehmerEintrag}. Die beiden
 * liegen in verschiedenen Paketen und damit in verschiedenen Verzeichnissen; in <i>einem</i>
 * Verzeichnis waeren sie auf einem Dateisystem ohne Gross-/Kleinunterscheidung (macOS)
 * dieselbe Datei.
 *
 * @param anzeigeName Profilname beziehungsweise Gastname
 * @param gast        {@code true}, wenn hinter der Absage kein Profil steht
 */
public record Absageeintrag(String anzeigeName, boolean gast) {
}
