package de.fubo.appserver.service.auth;

import java.security.SecureRandom;

/**
 * Erzeugt die fuenfstellige Bestaetigungs-PIN fuer Passwort-Reset und Namensaenderung (A22).
 *
 * <p><b>Eine Stelle statt zweier.</b> Beide Vorgaenge verschicken dieselbe Art PIN; eine zweite
 * Erzeugung liefe frueher oder spaeter auseinander - etwa bei der fuehrenden Null, die
 * ausgerechnet dann verloren ginge, wenn niemand hinsieht.
 *
 * <p><b>Warum hier und nicht in {@code utils}:</b> Die Klasse haelt eine
 * {@link SecureRandom}-Instanz und ist damit nicht zustandslos - dieselbe Ueberlegung, die die
 * Push-Verschluesselung in {@code service/push} haelt. Paketprivat, weil nur die beiden Dienste
 * dieses Pakets sie brauchen.
 */
final class BestaetigungsPin {

    /**
     * Kryptografisch sichere Quelle. Ein {@code java.util.Random} waere hier ein Fehler:
     * Aus wenigen beobachteten Werten laesst sich sein Zustand rekonstruieren.
     */
    private static final SecureRandom ZUFALL = new SecureRandom();

    private BestaetigungsPin() {
    }

    /**
     * Fuenf Stellen, kryptografisch sicher, mit fuehrenden Nullen.
     *
     * <p><b>{@code nextInt(100_000)} und nicht {@code 10_000 + nextInt(90_000)}.</b> Die
     * zweite Variante schloesse alle PINs mit fuehrender Null aus und verkleinerte den Raum
     * um zehn Prozent - fuer eine Formatierung, die {@code %05d} ohnehin erledigt.
     *
     * @return PIN als Zeichenkette aus genau fuenf Ziffern
     */
    static String erzeugen() {
        return "%05d".formatted(ZUFALL.nextInt(100_000));
    }
}
