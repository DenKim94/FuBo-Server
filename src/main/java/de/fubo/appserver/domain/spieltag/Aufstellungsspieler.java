package de.fubo.appserver.domain.spieltag;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Ein Teilnehmer, wie der Teamgenerator ihn sieht: Herkunft, Anzeigename und die Skillwerte,
 * die zum Zeitpunkt des Laufs galten (S5, Abschnitt 2.1).
 *
 * <h2>Zwei Quellen, ein Typ</h2>
 * Die Aufstellung entsteht entweder aus den Zusagen eines Termins (2.2) oder aus der freien
 * Auswahl des Admins (2.5, A24). <b>Ab hier kennt niemand mehr den Unterschied</b> - weder die
 * Zielfunktion noch eines der beiden Verfahren erfaehrt, woher die Liste stammt. Genau deshalb
 * beruehrt A24 den Algorithmusteil nicht.
 *
 * <h2>Warum eine Karte statt fuenf Feldern</h2>
 * {@code profil.skill_kategorie.aktiv} ist administrierbar. Ein Record mit
 * {@code angriff, verteidigung, ...} waere eine zweite, im Code festgeschriebene Wahrheit ueber
 * die Kategorien und stuende still, sobald jemand eine deaktiviert oder eine sechste anlegt.
 * Die Zielfunktion iteriert ohnehin ueber {@code SkillKategorieRepository#aktive()}.
 *
 * <h2>Die Identitaet innerhalb eines Laufs ist die Position in der Liste</h2>
 * Nicht {@link #teilnahmeId()}. Beide Verfahren rechnen auf Indizes einer flachen Matrix
 * ({@code S5_ALGORITHMUS.md}, 3.2); die Teilnahme-Id wird erst beim Schreiben der Zuteilung
 * gebraucht und darf deshalb {@code null} sein. <b>Wer den Lauf speichert, prueft einmal, dass
 * keine {@code null} dabei ist</b> - lieber eine {@code IllegalStateException} als eine halb
 * geschriebene Einteilung.
 *
 * <p>Wie alle Typen in {@code domain} ueberschreitet auch dieser die API-Grenze nie.
 *
 * @param teilnahmeId Schluessel in {@code spieltag.teilnahme}; {@code null} im manuellen Lauf
 *                    (A24), der nichts speichert
 * @param spielerId   Profil-Id; {@code null} bei Gaesten. Im Termin-Lauf redundant, im manuellen
 *                    Lauf die einzige Kennung eines Spielers - und sie traegt die Pruefungen
 *                    aus 2.5
 * @param anzeigeName Profilname oder Gastname. Beim Gast im manuellen Lauf kein Zierrat: Der
 *                    Admin liest die Teams vor und muss sagen koennen, wer gemeint ist
 * @param gast        {@code true}, wenn die Werte aus {@code profil.gast_vorlage} stammen
 * @param gemeldetAm  Zeitpunkt der Zusage; {@code null} im manuellen Lauf, weil sich dort
 *                    niemand gemeldet hat. Folge fuer den Auswechselspieler in 8.4
 * @param werte       Skillwert je Kategorieschluessel, vollstaendig ueber alle aktiven
 *                    Kategorien - die Vollstaendigkeit prueft der {@code AufstellungService},
 *                    nicht die Zielfunktion
 */
public record Aufstellungsspieler(Long teilnahmeId,
                                  Long spielerId,
                                  String anzeigeName,
                                  boolean gast,
                                  OffsetDateTime gemeldetAm,
                                  Map<String, Integer> werte) {
}
