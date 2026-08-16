package de.fubo.appserver.domain.profil;

/**
 * Ein Eintrag der Namensliste mit abgeleitetem Belegtstatus (A4, A6).
 *
 * <p>Wie {@code AktiveSitzung} ein schlankes Wertobjekt und <b>kein</b> JPA-Entity: Die
 * Zeile entsteht aus einer Abfrage mit Unterabfrage ueber zwei Tabellen und ist nicht vom
 * Persistence-Context verwaltet.
 *
 * <p>Der Typ ueberschreitet die API-Grenze nicht; der Controller liefert
 * {@code dto.profil.NameOption}. Die beiden sehen heute gleich aus - getrennt bleiben sie,
 * weil sie unterschiedlichen Zwecken folgen: Der eine bildet das Abfrageergebnis ab, der
 * andere den Vertrag zum Frontend. Sobald die Abfrage ein Feld mehr liefert, das nicht
 * nach aussen darf, ist die Trennung genau das, was den Fehler verhindert.
 *
 * @param id     Profil-Id, wird bei der Namensauswahl zurueckgesendet
 * @param name   Anzeigename
 * @param belegt {@code true}, wenn zu diesem Profil eine aktive Sitzung besteht
 */
public record NamensEintrag(Long id, String name, boolean belegt) {
}
