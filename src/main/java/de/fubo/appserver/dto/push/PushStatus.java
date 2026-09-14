package de.fubo.appserver.dto.push;

/**
 * Antwort von {@code GET /api/v1/push/status/lesen} (A25e, A25f; S8 Abschnitt 7.2).
 *
 * <h2>Zwei Ebenen, getrennt - und das ist der ganze Zweck</h2>
 * Der Versand haengt an <b>drei</b> Bedingungen als {@code AND}, auf drei Ebenen mit drei
 * verschiedenen Entscheidern: Anlage (der Admin), Person (der Spieler selbst) und Geraet (der
 * Browser). <b>Faellt eine weg, unterbleibt der Versand stillschweigend</b> - kein Fehlerfall,
 * sondern der Normalzustand vieler Spieler.
 *
 * <p>Genau deshalb stehen die beiden serverseitigen Ebenen hier <i>einzeln</i> und nicht als
 * ein zusammengefasstes "Push ist an". Eine Meldung "keine Benachrichtigungen" ohne Angabe der
 * Ebene schickt den Nutzer an die falsche Stelle: "vom Admin abgeschaltet" und "von dir
 * abgeschaltet" verlangen verschiedene Handlungen.
 *
 * <h2>Die Geraeteebene fehlt, und sie fehlt mit Absicht</h2>
 * Ein {@code GET} koennte das aufrufende Geraet gar nicht identifizieren, ohne die
 * Endpoint-Adresse in die URL zu schreiben. <b>Der Client liest sie lokal</b> ueber
 * {@code pushManager.getSubscription()} - dort steht sie ohnehin und ist aktueller als jede
 * Serverauskunft.
 *
 * @param anlageAktiv    {@code configs.app_config.push_aktiv}: ob die Anlage ueberhaupt
 *                       versendet. Der Admin entscheidet darueber (A25e)
 * @param pushErwuenscht {@code profil.spieler.push_erwuenscht}: ob dieser Spieler empfangen
 *                       will. Nur er selbst entscheidet darueber (A25f)
 */
public record PushStatus(boolean anlageAktiv, boolean pushErwuenscht) {
}
