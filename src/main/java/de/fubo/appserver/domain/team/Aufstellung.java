package de.fubo.appserver.domain.team;

import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;

import java.util.List;

/**
 * Die Eingabe eines Generierungslaufs: wer spielt und nach welchen Kategorien bewertet wird
 * ({@code S5_ALGORITHMUS.md}, Abschnitte 3 bis 5).
 *
 * <h2>Sie kennt ihre Herkunft nicht</h2>
 * Weder die Zielfunktion noch eines der beiden Verfahren erfaehrt, ob die Liste aus den
 * Zusagen eines Termins ({@code S5_UMSETZUNG.md}, 2.2) oder aus der freien Auswahl des Admins
 * stammt (2.5, A24). <b>Genau deshalb beruehrt A24 den Algorithmusteil nicht</b> - und wer
 * hier eine Termin-Id, eine Sitzung oder ein Repository braucht, hat den Schnitt verlassen.
 *
 * <h2>Die Kategorien reisen mit</h2>
 * Sie kommen aus {@code profil.skill_kategorie} und tragen ihr Gewicht bei sich; eine
 * Konstante {@code 0.30} fuer den Torwart im Code waere eine zweite Wahrheit. Die Reihenfolge
 * ist die aus {@code reihenfolge} und <b>ab hier fest</b> - sie ist die Kategorieachse der
 * Matrix, auf der beide Verfahren rechnen.
 *
 * <h2>Die Identitaet eines Spielers ist seine Position in der Liste</h2>
 * Beide Verfahren rechnen auf Indizes. Was ein Index bedeutet, entscheidet allein die
 * Reihenfolge dieser Liste; sie darf sich innerhalb eines Laufs nicht aendern, sonst liefert
 * derselbe Seed ein anderes Ergebnis. Der kompakte Konstruktor kopiert deshalb beide Listen.
 *
 * @param spieler    die Teilnehmer in fester Reihenfolge, mindestens zwei
 * @param kategorien die aktiven Skillkategorien in Anzeigereihenfolge, mindestens eine
 */
public record Aufstellung(List<Aufstellungsspieler> spieler, List<SkillKategorie> kategorien) {

    /**
     * Kopiert beide Listen und lehnt eine Aufstellung ab, aus der sich keine zwei Teams
     * bilden lassen.
     *
     * <p><b>Eine {@code IllegalArgumentException} und kein {@code FachlicherFehler}:</b> Ob
     * genug Teilnehmer da sind, entscheidet {@code min_teilnehmer} im
     * {@code AufstellungService} - mit einer Meldung, die Ist und Soll nennt. Wer hier mit
     * einem Spieler ankommt, hat diese Pruefung uebersprungen; das ist ein Programmierfehler
     * und kein Zustand, den ein Nutzer herbeifuehren koennte.
     */
    public Aufstellung {
        spieler = List.copyOf(spieler);
        kategorien = List.copyOf(kategorien);
        if (spieler.size() < 2) {
            throw new IllegalArgumentException(
                    "Eine Aufstellung braucht mindestens zwei Spieler, hatte aber "
                            + spieler.size() + ".");
        }
        if (kategorien.isEmpty()) {
            throw new IllegalArgumentException(
                    "Eine Aufstellung braucht mindestens eine aktive Skillkategorie.");
        }
    }

    /** Zahl der Teilnehmer - das {@code n} beider Verfahren. */
    public int groesse() {
        return spieler.size();
    }

    /**
     * Groesse des <b>kleineren</b> Teams, {@code n / 2} mit ganzzahliger Division.
     *
     * <p><b>Daraus folgt A20a ohne eigene Pruefung:</b> Das andere Team bekommt
     * {@code n - n/2}, die Groessendifferenz ist damit bauartbedingt hoechstens 1. Und bei
     * ungerader Zahl ist das kleinere Team dasjenige, das die Optimierung auf <i>Summen</i>
     * tendenziell staerker besetzt - genau so verlangt es A20a.
     *
     * <p><b>"Kleiner" heisst nicht "Team A".</b> Welche der beiden Haelften A und welche B
     * wird, entscheidet der Seed; sonst stuende bei ungerader Zahl immer dasselbe Team in
     * Ueberzahl.
     */
    public int kleineTeamgroesse() {
        return spieler.size() / 2;
    }

    /** {@code true}, wenn ein Auswechselspieler auszuweisen ist (A20b). */
    public boolean ungerade() {
        return spieler.size() % 2 != 0;
    }
}
