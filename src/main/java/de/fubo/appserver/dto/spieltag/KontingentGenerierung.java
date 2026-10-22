package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.Restkontingent;

/**
 * Das Restkontingent der Teamgenerierung des Aufrufers, Teil von {@link TerminDetails}
 * (A15, Ergaenzung vom 22.10.2026).
 *
 * <p>Der Client sperrt die Aktion "Teams generieren" damit sofort nach dem letzten erlaubten
 * Lauf, statt erst am {@code 409 KONTINGENT_ERSCHOEPFT} des naechsten Versuchs zu scheitern.
 *
 * <h2>Nur Zahlen der eigenen Sitzung</h2>
 * Weder Skillwerte noch Angaben zu anderen Akteuren: Jeder sieht ausschliesslich, wie viele
 * Laeufe er selbst noch hat. {@code erlaubt} ist die Konfigurationsgrenze, die sonst nur
 * {@code GET /admin/config/lesen} nennt; sie ist kein Geheimnis, aber ohne sie liesse sich
 * "noch x von y" nicht anzeigen.
 *
 * <p><b>Eine Anzeige, keine Zusicherung:</b> Zwei gleichzeitige Laeufe klaert weiterhin das
 * bedingte {@code UPDATE} in der Datenbank.
 *
 * @param erlaubt Laeufe je Nutzer, je Termin und je Kontingentstand
 * @param uebrig  noch moegliche Laeufe, nie negativ; {@code 0}, wenn ein Lauf jetzt mit
 *                {@code 409 KONTINGENT_ERSCHOEPFT} endete
 */
public record KontingentGenerierung(int erlaubt, int uebrig) {

    /** Bildet das Restkontingent auf den Vertrag ab. */
    public static KontingentGenerierung von(Restkontingent kontingent) {
        return new KontingentGenerierung(kontingent.erlaubt(), kontingent.uebrig());
    }
}
