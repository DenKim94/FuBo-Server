package de.fubo.appserver.domain.push;

/**
 * Was ein Versandlauf erreicht hat (A25b, S8 Abschnitte 8.6 und 10.2).
 *
 * <h2>Drei Zahlen, weil zwei davon leicht verwechselt werden</h2>
 * {@link #empfaenger} zaehlt <b>Personen</b>, {@link #geraete} zaehlt <b>Abonnements</b>. Der
 * Unterschied ist nicht akademisch: Ein Spieler mit Telefon und Rechner ist ein Empfaenger und
 * zwei Geraete. <b>Im Audit-Eintrag steht die Empfaengerzahl</b> - "zwölf Spieler erinnert" ist
 * die Auskunft, die man spaeter sucht; "neunzehn Nachrichten versendet" waere eine Zahl ohne
 * fachliche Bedeutung.
 *
 * <h2>{@link #zugestellt} belegt die Annahme, nicht die Zustellung</h2>
 * Web Push kennt keine Zustellbestaetigung: Ein {@code 201} heisst "vom Push-Dienst
 * angenommen". Ob die Nachricht auf dem Sperrbildschirm erscheint, erfaehrt der Server nie -
 * dieselbe Einschraenkung wie bei {@code termin.halle_abgesagt_am} in S7, das den Versand
 * belegt und nicht den Empfang.
 *
 * <p><b>Eine Abweichung von {@link #geraete} ist kein Fehlerfall.</b> Ein erloschenes
 * Abonnement, ein ueberlasteter Dienst, eine abgelaufene Gesamtfrist - alle drei sind im
 * Betrieb normal. Der Grund je Abonnement steht im Anwendungsprotokoll.
 *
 * @param empfaenger Zahl verschiedener Spieler, an die etwas hinausging
 * @param geraete    Zahl angesprochener Abonnements
 * @param zugestellt Zahl der Abonnements, deren Push-Dienst die Nachricht angenommen hat
 */
public record Versandbilanz(int empfaenger, int geraete, int zugestellt) {

    /** Der Lauf ohne Empfaenger - der haeufigste Fall und kein Fehler. */
    public static final Versandbilanz LEER = new Versandbilanz(0, 0, 0);

    /** Ob ueberhaupt etwas hinausgegangen ist; entscheidet ueber den Audit-Eintrag. */
    public boolean etwasVersandt() {
        return geraete > 0;
    }
}
