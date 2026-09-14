package de.fubo.appserver.common.config;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * Das aufbereitete VAPID-Schluesselpaar der Anwendung (A25b, S8).
 *
 * <p>Entsteht einmalig beim Start in {@link PushConfig} und wird als Bean weitergereicht.
 * <b>Die Schluessel werden genau einmal dekodiert</b>, nicht bei jedem Versand: Ein Aufbau aus
 * base64url ueber {@code KeyFactory} kostet zwar wenig, aber er kann scheitern - und ein
 * Fehler, der beim Start auffaellt, ist ungleich billiger als einer, der pro Nachricht
 * auftritt und dort nur eine ausgebliebene Benachrichtigung erzeugt.
 *
 * <h2>Warum es diesen Typ ueberhaupt gibt</h2>
 * Ohne ihn muesste jede Stelle, die versendet, aus {@link FuboProperties.Push} erst wieder
 * Schluesselobjekte bauen und dabei erneut entscheiden, was bei einem fehlenden Wert gilt.
 * Diese Entscheidung faellt hier <b>einmal</b> und ist am Feld {@link #eingerichtet} ablesbar.
 *
 * <h2>Der Zustand "nicht eingerichtet" ist kein Fehler</h2>
 * Fehlt einer der drei Werte - oder ist er ein unaufgeloester Platzhalter -, entsteht die Bean
 * trotzdem, mit {@code eingerichtet = false} und {@code null} in den drei Schluesselfeldern.
 * <b>Der Start bricht nicht ab</b>, denn der Kernbetrieb laeuft ohne Push vollstaendig. Jeder
 * Aufrufer prueft deshalb {@link #eingerichtet}, bevor er die Schluessel anfasst; die
 * Endpunkte antworten in diesem Fall {@code 503 PUSH_NICHT_KONFIGURIERT}, der
 * Erinnerungsauftrag steigt frueh aus.
 *
 * @param oeffentlich         oeffentlicher Schluessel als Objekt, fuer die Gegenprobe beim Start
 * @param privat              privater Schluessel als Objekt, fuer die Signatur des VAPID-JWT.
 *                            <b>Geheimnis</b> - er erscheint in keiner Antwort und keiner
 *                            Logzeile
 * @param oeffentlichBase64Url derselbe oeffentliche Schluessel in der Form, in der er nach
 *                            aussen geht: base64url ohne Polsterung. Er steht im
 *                            {@code Authorization}-Kopf jeder Push-Anfrage
 *                            ({@code k=<Schluessel>}) und wird dem Client zum Abonnieren
 *                            geliefert. Bewusst mitgefuehrt statt neu kodiert - eine zweite
 *                            Kodierung waere eine zweite Gelegenheit, sie anders zu machen
 * @param subject             Kontaktadresse des Betreibers als URI, der {@code sub}-Anspruch
 *                            des JWT
 * @param eingerichtet        ob alle drei Werte vorhanden, dekodierbar und zueinander passend
 *                            sind
 */
public record VapidSchluessel(ECPublicKey oeffentlich,
                              ECPrivateKey privat,
                              String oeffentlichBase64Url,
                              String subject,
                              boolean eingerichtet) {

    /** Der Zustand "Push ist auf diesem Server nicht eingerichtet". */
    static VapidSchluessel nichtEingerichtet() {
        return new VapidSchluessel(null, null, null, null, false);
    }
}
