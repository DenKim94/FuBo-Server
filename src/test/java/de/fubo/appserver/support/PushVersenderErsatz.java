package de.fubo.appserver.support;

import de.fubo.appserver.domain.push.PushAbo;
import de.fubo.appserver.domain.push.PushAntwort;
import de.fubo.appserver.domain.push.PushNutzlast;
import de.fubo.appserver.service.push.PushVersender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handgeschriebener Ersatz fuer den {@link PushVersender}.
 *
 * <p>Kein Mockito und keine zusaetzliche Abhaengigkeit - dasselbe Vorgehen wie beim
 * {@link MailErsatz} und beim {@code SessionService}-Ersatz in {@code SessionAuthFilterTests}.
 * Der Ersatz merkt sich die Aufrufe und antwortet mit einem einstellbaren Status.
 *
 * <h2>Warum ueberhaupt ein Ersatz und nicht der echte Adapter</h2>
 * {@code WebPushVersender} spricht die Push-Dienste der Browserhersteller an. Im Testlauf gibt es
 * die nicht, und selbst wenn: <b>Ein Push-Dienst antwortet auch auf eine falsch verschluesselte
 * Nachricht mit {@code 201}</b> - er koennte also gar nicht belegen, dass der Versand richtig
 * arbeitet. Was der echte Adapter leistet, prueft {@code PushVerschluesselungTests} gegen die
 * Testvektoren aus RFC 8291; was diese Klasse leistet, ist die <i>Auswahl</i> der Empfaenger und
 * die <i>Buchung</i> der Antworten - und dafuer muss der Status frei waehlbar sein.
 *
 * <h2>Der Status wird nicht selbst ausgewertet</h2>
 * Die Zuordnung "welcher HTTP-Status bedeutet was" steht in {@link PushAntwort#von(int)} und
 * damit an genau einer Stelle. <b>Wuerde der Ersatz sie nachbauen, pruefte der Test seine eigene
 * Kopie</b> - und liefe still auseinander, sobald der echte Adapter einen Status anders einordnet.
 *
 * <h2>Die Aufrufliste ist synchronisiert</h2>
 * {@code PushVersandService} stoesst alle Empfaenger nebenlaeufig an. Auch wenn dieser Ersatz
 * sofort ein fertiges Future liefert und die Aufrufe damit heute in der aufrufenden Kette
 * stattfinden: Eine unsynchronisierte {@link ArrayList} waere ein Fehler, der erst bei einer
 * spaeteren Umstellung auftritt - und dann als sporadisch fehlender Eintrag, nicht als Ausnahme.
 */
public class PushVersenderErsatz implements PushVersender {

    /** Ein angenommener Versandauftrag: an welches Abonnement mit welcher Nutzlast. */
    public record Aufruf(PushAbo abo, PushNutzlast nutzlast) {
    }

    private final List<Aufruf> aufrufe = Collections.synchronizedList(new ArrayList<>());

    /**
     * Der Status, mit dem geantwortet wird; {@code 201} ist die uebliche Zusage der Dienste.
     *
     * <p>{@code volatile}, weil er im Testfaden gesetzt und moeglicherweise in einem anderen
     * gelesen wird.
     */
    private volatile int status = 201;

    /** Die bisher angenommenen Auftraege, in Aufrufreihenfolge. */
    public List<Aufruf> aufrufe() {
        return aufrufe;
    }

    /** Die angesprochenen Abonnement-Ids - der haeufigste Vergleich in den Testfaellen. */
    public List<Long> angesprocheneAbos() {
        synchronized (aufrufe) {
            return aufrufe.stream().map(aufruf -> aufruf.abo().id()).toList();
        }
    }

    /** Leert die Liste und stellt den Status auf {@code 201}; gehoert ins Aufbauen. */
    public void zuruecksetzen() {
        aufrufe.clear();
        status = 201;
    }

    /**
     * Stellt die Antwort des Push-Dienstes ein.
     *
     * @param status HTTP-Status, etwa {@code 410} fuer ein erloschenes Abonnement
     */
    public void antwortetMit(int status) {
        this.status = status;
    }

    @Override
    public CompletableFuture<PushAntwort> versende(PushAbo abo, PushNutzlast nutzlast) {
        aufrufe.add(new Aufruf(abo, nutzlast));
        return CompletableFuture.completedFuture(PushAntwort.von(status));
    }
}
