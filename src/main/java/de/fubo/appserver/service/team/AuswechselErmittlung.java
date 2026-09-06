package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.team.Bankentscheid;
import de.fubo.appserver.domain.team.Bankkandidat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Waehlt den Auswechselspieler bei ungerader Teilnehmerzahl (A20b, S5 Abschnitt 8).
 *
 * <h2>Gewaehlt wird immer aus dem Ueberzahl-Team</h2>
 * A20b nennt bei {@code ZULETZT_ANGEMELDET} "den zuletzt angemeldeten Spieler", ohne zu sagen,
 * aus welchem Team. Woertlich genommen koennte er im <i>kleineren</i> stehen - dann liefen
 * fuenf gegen sechs, und die Regel widerspraeche sich selbst. <b>Entschieden am 06.09.2026
 * entlang der Empfehlung aus 8.2:</b> Beide Modi waehlen aus derselben Menge und
 * unterscheiden sich nur im Kriterium. Die Teamgroessen bleiben unberuehrt, A20a gilt ohne
 * eigene Pruefung weiter.
 *
 * <h2>Die Einstellung aendert die Einteilung nicht</h2>
 * Sie entscheidet nur, wer von den Aufgestellten auf der Bank sitzt. Deshalb laeuft diese
 * Wahl <b>nach</b> dem Verfahren und geht in keine Zielfunktion ein.
 *
 * <h2>Bei Gleichstand entscheidet der Seed, nicht die Id</h2>
 * Zwei gleich starke Spieler ergaeben sonst Woche fuer Woche denselben auf der Bank - die
 * Reihenfolge in der Liste ist zufaellig gewachsen, aber stabil. Gezogen wird per
 * Reservoir-Sampling ueber die Gleichstaende, mit <b>frischem</b> {@code new Random(seed)}.
 *
 * <p><b>Frisch und nicht der Generator des Verfahrens</b> - das ist die Bedingung, unter der
 * sich die Wahl spaeter aus einer gespeicherten Einteilung nachvollziehen laesst (8.3). Der
 * Verfahrensgenerator hat bis dahin unterschiedlich viele Zahlen verbraucht: bei
 * {@code EXHAUSTIV} so viele, wie es gleich gute Aufteilungen gab, bei {@code HEURISTIK} eine
 * je Iteration. Sein Zustand ist also nicht rekonstruierbar, ohne den ganzen Lauf zu
 * wiederholen - der Seed dagegen steht in {@code team_generierung.seed}.
 *
 * <h2>Warum nichts gespeichert wird</h2>
 * {@code team_zuteilung} hat keine Spalte fuer den Auswechselspieler, und sie braucht keine:
 * Teamgroessen, Modus, {@code score_snapshot} und Seed stehen vollstaendig in der Datenbank.
 * Eine Spalte kostete die Migration {@code V012} und damit die Migrationsfreiheit von S5.
 * <b>Der Preis ist diese Klasse</b> - sie muss aus beiden Datengrundlagen dasselbe liefern.
 */
@Component
public class AuswechselErmittlung {

    /**
     * Waehlt den Auswechselspieler aus den Teilnehmern des Ueberzahl-Teams.
     *
     * <p>Die Reihenfolge der Liste ist Teil der Eingabe: Bei Gleichstand entscheidet der Seed,
     * und derselbe Seed waehlt nur dann dasselbe, wenn die Kandidaten in derselben Ordnung
     * vorliegen. Der Aufrufer haelt das ein, indem er die Zuteilungen in der Reihenfolge
     * schreibt, in der sie im Lauf standen, und sie so wieder liest.
     *
     * @param ueberzahl die Teilnehmer des groesseren Teams, nicht leer
     * @param gewuenscht der eingestellte Modus aus {@code configs.app_config}
     * @param seed      der Seed des Laufs
     * @return gewaehlter Kandidat und der tatsaechlich verwendete Modus
     * @throws IllegalArgumentException bei leerer Kandidatenliste - der Aufrufer fragt vorher
     *                                  {@code Aufstellung#ungerade()}
     */
    public Bankentscheid waehle(List<Bankkandidat> ueberzahl, AuswechselModus gewuenscht, long seed) {
        if (ueberzahl.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ein Auswechselspieler wird nur bei ungerader Teilnehmerzahl bestimmt.");
        }

        AuswechselModus modus = wirksamerModus(ueberzahl, gewuenscht);
        List<Bankkandidat> beste = besteKandidaten(ueberzahl, modus);

        Bankkandidat gewaehlt = beste.size() == 1
                ? beste.getFirst()
                : beste.get(new Random(seed).nextInt(beste.size()));

        return new Bankentscheid(gewaehlt.position(), modus);
    }

    /**
     * Faellt auf {@link AuswechselModus#SCHWAECHSTER_UEBERZAHL} zurueck, wenn die Meldezeiten
     * fehlen (8.4).
     *
     * <p>Das ist genau der manuelle Lauf nach A24: Es gibt keine Teilnahme und damit keine
     * Zusage, {@code gemeldetAm} ist ueberall {@code null}. <b>Geprueft wird auf einen einzigen
     * fehlenden Wert und nicht auf alle</b> - eine gemischte Liste kann es nicht geben, und
     * wenn doch, waere "der zuletzt Gemeldete" unter den Bekannten eine Aussage ueber eine
     * Teilmenge, die niemand angeordnet hat.
     */
    private static AuswechselModus wirksamerModus(List<Bankkandidat> ueberzahl,
                                                  AuswechselModus gewuenscht) {
        if (gewuenscht != AuswechselModus.ZULETZT_ANGEMELDET) {
            return gewuenscht;
        }
        boolean meldezeitFehlt = ueberzahl.stream().anyMatch(k -> k.gemeldetAm() == null);
        return meldezeitFehlt ? AuswechselModus.SCHWAECHSTER_UEBERZAHL : gewuenscht;
    }

    /**
     * Sammelt alle Kandidaten, die nach dem Kriterium gleich gut sind.
     *
     * <p>Eine Schleife statt {@code Comparator} und {@code min}/{@code max}: Gebraucht wird
     * nicht das Beste, sondern die <b>Menge</b> der Besten - {@code Stream#min} liefert
     * stillschweigend den ersten Gleichstand, und genau der soll hier nicht gewinnen.
     */
    private static List<Bankkandidat> besteKandidaten(List<Bankkandidat> ueberzahl,
                                                      AuswechselModus modus) {
        List<Bankkandidat> beste = new ArrayList<>();
        for (Bankkandidat kandidat : ueberzahl) {
            int vergleich = beste.isEmpty() ? 1 : vergleiche(kandidat, beste.getFirst(), modus);
            if (vergleich > 0) {
                beste.clear();
                beste.add(kandidat);
            } else if (vergleich == 0) {
                beste.add(kandidat);
            }
        }
        return beste;
    }

    /**
     * Vergleicht zwei Kandidaten nach dem Kriterium des Modus.
     *
     * @return positiv, wenn {@code a} eher auf die Bank gehoert als {@code b}; {@code 0} bei
     *         Gleichstand
     */
    private static int vergleiche(Bankkandidat a, Bankkandidat b, AuswechselModus modus) {
        // Der Schwaechere gehoert auf die Bank - also der mit der kleineren Staerke, deshalb
        // die umgekehrte Reihenfolge. Bei ZULETZT_ANGEMELDET ist es die spaetere Meldezeit.
        return modus == AuswechselModus.SCHWAECHSTER_UEBERZAHL
                ? Long.compare(b.staerke(), a.staerke())
                : a.gemeldetAm().compareTo(b.gemeldetAm());
    }
}
