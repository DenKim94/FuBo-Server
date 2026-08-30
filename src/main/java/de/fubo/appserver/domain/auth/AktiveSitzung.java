package de.fubo.appserver.domain.auth;

import java.time.OffsetDateTime;

/**
 * Ergebnis einer erfolgreichen Sitzungspruefung: genau die Felder, welche die
 * Filterchain und der Endpunkt {@code GET /auth/session/lesen} benoetigen.
 *
 * <p>Bewusst <b>kein</b> JPA-Entity. Die Zeile stammt aus der {@code RETURNING}-Klausel
 * eines nativen {@code UPDATE} (beziehungsweise aus dem rein lesenden Gegenstueck) und ist
 * nicht vom Persistence-Context verwaltet. Eine teilweise befuellte {@link Session}-Entity
 * zurueckzugeben waere irrefuehrend: Sie saehe aus wie ein vollstaendiges Objekt, haette
 * aber leere Felder, und ein {@code save()} darauf wuerde Daten ueberschreiben.
 *
 * <p><b>Ergaenzt am 30.08.2026 um {@code gastStufe} (S4, Paket 6).</b> Bei der Zusage eines
 * Gastes wird die Stufe in {@code spieltag.teilnahme.gast_stufe} <b>kopiert</b>, nicht
 * verwiesen: Die Sitzung endet, die Teilnahme bleibt, und der Teamgenerator in S5 braucht die
 * Stufe zum Zeitpunkt des Spiels. Sie steht deshalb hier und nicht nur in der Datenbankzeile -
 * der Dienst holt sie aus der Sitzung, nie aus dem Anfragekoerper. Sonst koennte ein Gast sich
 * selbst zum starken Spieler erklaeren, nachdem der Admin ihn korrigiert hat.
 *
 * <p><b>Ergaenzt am 22.08.2026 um die beiden Ablaufzeitpunkte.</b> Der Endpunkt
 * {@code GET /auth/session/lesen} liefert sie an das Frontend, das daraus den Countdown und
 * die Schaltflaeche "Sitzung verlaengern" ableitet (Abschnitt 10.7 der Umsetzungsanleitung).
 * Sie kommen aus derselben Abfrage, die die Sitzung ohnehin bei jedem Request prueft - ein
 * zweiter Lesezugriff nur fuer die Anzeige waere Verschwendung, und die Werte kaemen von
 * derselben Uhr, aber zu einem anderen Zeitpunkt.
 *
 * @param id                technischer Schluessel der Sitzung
 * @param spielerId         Profil-Id; {@code null} bei Gastsitzungen und in {@link Stage#PIN_VERIFIED}
 * @param gastName          temporaerer Name eines Gastes; sonst {@code null}
 * @param gastStufe         Selbsteinschaetzung des Gastes; sonst {@code null}
 * @param rolle             {@code null}, solange die Sitzung in {@link Stage#PIN_VERIFIED} ist
 * @param stage             erreichte Login-Stufe
 * @param gueltigBis        Ende des gleitenden Leerlauf-Fensters; wandert bei Aktivitaet nach hinten
 * @param absolutGueltigBis harte Obergrenze; wird nie verlaengert (Zwei-Timer-Modell, A14)
 */
public record AktiveSitzung(Long id,
                            Long spielerId,
                            String gastName,
                            GastStufe gastStufe,
                            Rolle rolle,
                            Stage stage,
                            OffsetDateTime gueltigBis,
                            OffsetDateTime absolutGueltigBis) {
}
