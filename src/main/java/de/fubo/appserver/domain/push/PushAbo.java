package de.fubo.appserver.domain.push;

/**
 * Ein aktives Push-Abonnement, so wie der Versand es braucht ({@code profil.push_abo}, A25c).
 *
 * <h2>Bewusst kein JPA-Entity</h2>
 * {@code profil.push_abo} wird angehaengt ({@code INSERT ... ON CONFLICT}), bedingt
 * aktualisiert (das Versandergebnis je Zeile) und geloescht - dasselbe Bild wie bei
 * {@code gast_slot} und {@code teilnahme}. {@code @Version} waere hier sogar nachteilig:
 * Optimistic Locking meldete den Wettlauf zweier gleichzeitiger Anmeldungen desselben
 * Geraets erst beim Schreiben und verlangte eine Wiederholung, der bedingte {@code UPDATE}
 * entscheidet ihn ohne. <b>Folge: Die Spalte {@code version} wird von Hand fortgeschrieben.</b>
 *
 * <h2>Weniger Felder als die Tabelle, und zwar genau die des Versands</h2>
 * {@code erstellt_am}, {@code letzter_versand_am}, {@code fehlversuche} und
 * {@code deaktiviert_am} fehlen hier: Sie werden <b>geschrieben</b> und nicht gelesen - je
 * Zeile in einer bedingten Anweisung, die den alten Wert gar nicht braucht. Ein Feld, das
 * der Versand mitfuehrt, ohne es zu benutzen, waere eine Einladung, die Entscheidung
 * "deaktivieren ab fuenf Fehlversuchen" in Java nachzurechnen - und damit ein Wettlauf.
 *
 * @param id        technischer Schluessel; er adressiert die Zeile beim Schreiben des
 *                  Versandergebnisses. <b>Nicht der {@code endpoint_hash}</b> - der ist
 *                  laenger und wird nur zum Wiedererkennen durch den Client gebraucht
 * @param spielerId Eigentuemer des Abonnements. Der Versand gruppiert darueber, um
 *                  Empfaenger (Personen) von Geraeten zu unterscheiden
 * @param endpoint  Adresse beim Push-Dienst des Browserherstellers (RFC 8030). Ihr
 *                  <b>Origin</b> ist zugleich der {@code aud}-Anspruch des VAPID-JWT
 * @param p256dh    oeffentlicher Schluessel des Browsers als base64url, unkomprimierter
 *                  P-256-Punkt (65 Byte)
 * @param auth      Geheimnis des Abonnements als base64url (16 Byte). Es dient in RFC 8291
 *                  als Salz der ersten HKDF-Stufe
 */
public record PushAbo(Long id,
                      Long spielerId,
                      String endpoint,
                      String p256dh,
                      String auth) {
}
