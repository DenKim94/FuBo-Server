package de.fubo.appserver.dto.admin;

/**
 * Antwort von {@code POST /api/v1/admin/user/anlegen} (S2b Abschnitt 8).
 *
 * <p>Die Id ist der einzige Wert, den der Aufrufer nicht schon kennt - ohne sie muesste er
 * die Namensliste erneut lesen, um das eben angelegte Profil sperren oder entfernen zu
 * koennen.
 *
 * <p><b>Ohne Skillwerte</b>, auch gegenueber dem Admin: Es gibt bisher keinen Endpunkt, der
 * sie ausliefert, und dieser soll nicht der erste sein. Was gesetzt wurde, steht im
 * Anfragekoerper beziehungsweise in der dokumentierten Vorgabe.
 *
 * @param spielerId Id des neuen Profils
 * @param name      uebernommener Name, bereits von Randleerzeichen befreit
 */
public record SpielerAngelegt(Long spielerId, String name) {
}
