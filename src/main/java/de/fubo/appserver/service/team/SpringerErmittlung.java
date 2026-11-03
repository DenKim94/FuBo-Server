package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.Springerwahl;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Bestimmt bei ungerader Teilnehmerzahl den Springer (A20b, Aenderung vom 03.11.2026).
 *
 * <h2>Gewaehlt wird aus der ganzen Aufstellung, vor der Einteilung</h2>
 * Der Springer gehoert keinem Team fest an; er wechselt, sobald sein aktuelles Team ein Tor
 * erzielt. Deshalb wird er zuerst bestimmt, und die uebrigen Teilnehmer werden ohne ihn zu zwei
 * gleich grossen Teams ausgeglichen. <b>Kandidaten sind alle Teilnehmer der Aufstellung</b>,
 * Spieler wie Gaeste - nicht die Warteschlange, die spielt nicht. Bis zum 03.11.2026 wurde
 * <i>nach</i> der Einteilung aus dem groesseren Team gewaehlt (Auswechselspieler); das ging,
 * weil die Wahl die Einteilung nicht beeinflusste. Jetzt beeinflusst sie sie.
 *
 * <h2>Der Zufall kommt vom Aufrufer</h2>
 * {@code Teamrechner} reicht einen eigenen Generator herein, aus dem er danach auch das
 * Startteam bei Gleichstand zieht. Ein frischer {@code new Random(seed)} hier liefe parallel
 * zum Generator des Verfahrens, der mit demselben Seed beginnt - zwei Stroeme mit derselben
 * ersten Zahl.
 *
 * <h2>Kein Nachvollziehen beim Lesen</h2>
 * Der Springer wird mit dem Lauf gespeichert ({@code team_zuteilung.springer}, {@code V017})
 * und nicht beim Lesen neu bestimmt. Diese Klasse wird deshalb nur im Lauf gerufen und braucht
 * keine zweite Datengrundlage.
 */
@Component
public class SpringerErmittlung {

    /**
     * Waehlt den Springer.
     *
     * @param aufstellung alle Teilnehmer des Laufs; die Zahl ist ungerade, der Aufrufer fragt
     *                    vorher {@link Aufstellung#ungerade()}
     * @param gewuenscht  der eingestellte Modus aus {@code configs.app_config}
     * @param zufall      Generator des Laufs fuer die Springerwahl
     * @return Position des Springers und der tatsaechlich verwendete Modus
     */
    public Springerwahl waehle(Aufstellung aufstellung, AuswechselModus gewuenscht, Random zufall) {
        List<Aufstellungsspieler> spieler = aufstellung.spieler();
        AuswechselModus modus = wirksamerModus(spieler, gewuenscht);

        if (modus == AuswechselModus.ZUFAELLIG) {
            return new Springerwahl(zufall.nextInt(spieler.size()), modus);
        }

        List<Integer> zuletzt = zuletztGemeldete(spieler);
        int position = zuletzt.size() == 1
                ? zuletzt.getFirst()
                : zuletzt.get(zufall.nextInt(zuletzt.size()));
        return new Springerwahl(position, modus);
    }

    /**
     * Faellt auf {@link AuswechselModus#ZUFAELLIG} zurueck, wenn eine Meldezeit fehlt.
     *
     * <p>Das ist genau der manuelle Lauf nach A24: Es gibt keine Teilnahme und damit keine
     * Zusage, {@code gemeldetAm} ist ueberall {@code null}. <b>Geprueft wird auf einen einzigen
     * fehlenden Wert und nicht auf alle</b> - eine gemischte Liste kann es nicht geben, und wenn
     * doch, waere "der zuletzt Gemeldete" unter den Bekannten eine Aussage ueber eine
     * Teilmenge, die niemand angeordnet hat. Bis zum 03.11.2026 fiel die Wahl hier auf den
     * Schwaechsten zurueck; den Modus gibt es nicht mehr.
     */
    private static AuswechselModus wirksamerModus(List<Aufstellungsspieler> spieler,
                                                  AuswechselModus gewuenscht) {
        if (gewuenscht != AuswechselModus.ZULETZT_ANGEMELDET) {
            return gewuenscht;
        }
        boolean meldezeitFehlt = spieler.stream().anyMatch(s -> s.gemeldetAm() == null);
        return meldezeitFehlt ? AuswechselModus.ZUFAELLIG : gewuenscht;
    }

    /**
     * Alle Positionen mit der spaetesten Meldezeit, in Aufstellungsreihenfolge.
     *
     * <p>Eine Schleife statt {@code Stream#max}: Gebraucht wird die <b>Menge</b> der Spaetesten
     * - {@code max} liefert stillschweigend einen Gleichstand, und genau der soll nicht
     * gewinnen. Bei Gleichstand entscheidet der Seed, sonst stuende Woche fuer Woche derselbe
     * als Springer da, nur weil er in der Liste weiter vorn steht.
     */
    private static List<Integer> zuletztGemeldete(List<Aufstellungsspieler> spieler) {
        List<Integer> zuletzt = new ArrayList<>();
        OffsetDateTime spaeteste = null;
        for (int i = 0; i < spieler.size(); i++) {
            OffsetDateTime gemeldet = spieler.get(i).gemeldetAm();
            int vergleich = spaeteste == null ? 1 : gemeldet.compareTo(spaeteste);
            if (vergleich > 0) {
                zuletzt.clear();
                zuletzt.add(i);
                spaeteste = gemeldet;
            } else if (vergleich == 0) {
                zuletzt.add(i);
            }
        }
        return zuletzt;
    }
}
