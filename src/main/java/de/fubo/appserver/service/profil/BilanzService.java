package de.fubo.appserver.service.profil;

import de.fubo.appserver.domain.profil.Bilanzstand;
import de.fubo.appserver.repository.profil.BilanzRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Die Bilanz-Zaehler: Neuberechnung und Auskunft (A21, S6 Abschnitt 4).
 *
 * <h2>Warum dieser Dienst in {@code service.profil} liegt</h2>
 * Geschrieben wird {@code profil.spieler}, und hier haengt der Zwischenspeicher der
 * Profilstammdaten. Der <i>Anlass</i> kommt aus {@code spieltag} - der Ergebnisdienst ruft an -,
 * aber die Zustaendigkeit fuer die Profilzeile und ihren Zwischenspeicher bleibt, wo sie
 * bisher lag. So kennt der Ergebnisdienst den {@link ProfilStammdatenCache} gar nicht erst.
 *
 * <h2>Das Verwerfen des Zwischenspeichers ist der leicht zu vergessende Teil</h2>
 * Die Bilanz erscheint in {@code SpielerDetails}, und diese Uebersicht liest die Stammdaten
 * aus dem Zwischenspeicher. <b>Jeder schreibende Vorgang verwirft, ausnahmslos</b>; eine
 * vergessene Stelle liefert unbegrenzt lange veraltete Daten, weil es keine Frist gibt, die
 * den Fehler von selbst heilte.
 *
 * <p><b>Bewusst kein {@code @CacheEvict} an dieser Methode</b>, sondern der Aufruf am Ende -
 * dieselbe Entscheidung wie im {@link SpielerVerwaltungService}: So bleibt sichtbar, dass das
 * Verwerfen zum Vorgang gehoert, und die Richtung stimmt. Ein zu <i>frueh</i> geleerter
 * Speicher kostet eine Abfrage, ein zu spaet geleerter zeigte falsche Werte.
 */
@Service
public class BilanzService {

    private final BilanzRepository bilanzRepository;
    private final ProfilStammdatenCache profilStammdatenCache;

    public BilanzService(BilanzRepository bilanzRepository,
                         ProfilStammdatenCache profilStammdatenCache) {
        this.bilanzRepository = bilanzRepository;
        this.profilStammdatenCache = profilStammdatenCache;
    }

    /**
     * Berechnet die Bilanz aller Beteiligten eines Termins neu (4.1, 4.2, 4.6).
     *
     * <p><b>Ohne eigene Transaktionsgrenze gedacht:</b> {@code @Transactional} mit der
     * voreingestellten Ausbreitung {@code REQUIRED} schliesst sich der Transaktion des
     * Aufrufers an - und das ist der Punkt. Die Neuberechnung laeuft auf dem Eintrags- und auf
     * dem Korrekturpfad jeweils <i>zusammen</i> mit der Aenderung an
     * {@code spieltag.ergebnis}. Sonst gaebe es einen Moment, in dem das Ergebnis steht und
     * die Bilanz es noch nicht kennt; und wenn der zweite Schritt scheitert, dauerhaft.
     *
     * <p><b>Der Aufrufer muss seine Aenderung vorher geschrieben haben.</b> Die Abfrage liest
     * {@code spieltag.ergebnis} und sieht nur, was in der Datenbank steht: Der Eintragspfad
     * schreibt nativ und ist damit ohnehin durch; der Korrekturpfad laeuft ueber die Entity
     * und muss deshalb <b>vor</b> diesem Aufruf flushen. Das ist die eine Reihenfolge, die
     * sich nicht von selbst ergibt.
     *
     * @param terminId Termin, dessen Einteilung die Betroffenen bestimmt
     * @return Zahl der geaenderten Profile - die Zahl der eingeteilten Spieler ohne Gaeste
     */
    @Transactional
    public int neuBerechnen(Long terminId) {
        int betroffen = bilanzRepository.neuBerechnen(terminId);
        profilStammdatenCache.verwerfen();
        return betroffen;
    }

    /**
     * Liefert die Bilanz eines Profils (4.5, Entscheidung des Haupt-Entwicklers).
     *
     * <p><b>{@code null} als Id ist der Gast</b> und kein Fehler: Er hat keine Zeile in
     * {@code profil.spieler} und fuehrt deshalb keine Bilanz ({@code V011}). Er bekommt die
     * leere Bilanz - dieselbe Antwort wie ein Spieler, dessen Termine noch kein Ergebnis
     * haben. Beides bedeutet "nichts zu zeigen", und ein eigener Fehlercode verriete, wer Gast
     * ist, ohne dass jemand danach gefragt hat.
     *
     * <p><b>Kein A12-Fall:</b> Die Bilanz ist Statistik und kein Skillwert - sie darf den
     * Server auch in Richtung {@code USER} verlassen. A16 bleibt unberuehrt; Ergebnisse wirken
     * nicht auf Skills zurueck.
     *
     * @param spielerId Profil-Id aus der Sitzung, oder {@code null} bei einer Gastsitzung
     * @return die Bilanz; nie {@code null}
     */
    @Transactional(readOnly = true)
    public Bilanzstand fuerSpieler(Long spielerId) {
        if (spielerId == null) {
            return Bilanzstand.LEER;
        }
        // Ein verschwundenes Profil bei gueltiger Sitzung ist kein Eingabefall: Das Entfernen
        // widerruft die Sitzungen. Die leere Bilanz ist hier trotzdem die bessere Antwort als
        // ein 404 - der Aufrufer hat nach sich selbst gefragt.
        return bilanzRepository.lesen(spielerId).orElse(Bilanzstand.LEER);
    }
}
