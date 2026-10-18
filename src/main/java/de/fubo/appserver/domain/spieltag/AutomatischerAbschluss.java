package de.fubo.appserver.domain.spieltag;

/**
 * Ergebnis eines Laufs des automatischen Abschlusses (A18, erweitert am 18.10.2026).
 *
 * <p><b>Zwei Zahlen statt einer</b>, weil der Lauf geplante Termine auf zwei verschiedene
 * Zustaende setzt: {@code ABGESCHLOSSEN}, wenn die Mindestzahl erreicht war, sonst
 * {@code ABGESAGT}. Eine einzige Summe machte die zweite Sorte unsichtbar - und eine Absage
 * ohne Handelnden ist genau die, nach der spaeter jemand im Log sucht.
 *
 * @param abgeschlossen Termine, die auf {@code ABGESCHLOSSEN} gesetzt wurden
 * @param abgesagt      Termine, die wegen unterschrittener Mindestzahl auf {@code ABGESAGT}
 *                      gesetzt wurden
 */
public record AutomatischerAbschluss(int abgeschlossen, int abgesagt) {
}
