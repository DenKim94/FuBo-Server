package de.fubo.appserver.service.team;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Liefert den Seed eines Generierungslaufs (A15, S5 Abschnitt 6.5).
 *
 * <h2>Gezogen mit {@code SecureRandom}, verbraucht mit {@code java.util.Random}</h2>
 * Zwei verschiedene Generatoren, und beides mit Grund. <b>Gezogen</b> wird unvorhersagbar:
 * Waere der Seed aus der Termin-Id, der Uhrzeit oder einem Zaehler abgeleitet, reproduzierte
 * der zweite Lauf den ersten - und {@code anz_team_generator > 1} verloere seinen Sinn.
 * <b>Verbraucht</b> wird er in den Verfahren mit {@code java.util.Random}, dessen Algorithmus
 * in der Javadoc spezifiziert ist: Nur so laesst sich ein gespeicherter Lauf Jahre spaeter
 * noch nachrechnen. {@code RandomGenerator.getDefault()} darf sich zwischen Java-Versionen
 * aendern.
 *
 * <h2>Eine eigene Bean und kein {@code static}</h2>
 * Sie laesst sich im Test durch eine feste Folge ersetzen - andernfalls muesste jeder Test,
 * der ein bestimmtes Ergebnis erwartet, den gezogenen Seed erst aus der Datenbank lesen.
 *
 * <p><b>Der Seed ist kein Geheimnis.</b> Er steht in {@code team_generierung.seed} und im
 * Audit-Eintrag des manuellen Laufs; er soll nur nicht <i>vorhersagbar</i> sein.
 */
@Component
public class SeedQuelle {

    /**
     * Eine Instanz fuer die Anwendung. {@code SecureRandom} ist threadsicher, und das
     * Nachsaeen kostet beim ersten Aufruf einmalig Zeit - je Lauf eine neue Instanz zu bauen
     * hiesse, diesen Preis dauernd zu zahlen.
     */
    private final SecureRandom quelle = new SecureRandom();

    /** Ein frischer Seed je Lauf. */
    public long naechster() {
        return quelle.nextLong();
    }
}
