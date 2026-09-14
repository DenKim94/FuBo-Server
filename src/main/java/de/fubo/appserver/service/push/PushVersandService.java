package de.fubo.appserver.service.push;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.domain.push.PushAbo;
import de.fubo.appserver.domain.push.PushAntwort;
import de.fubo.appserver.domain.push.PushNutzlast;
import de.fubo.appserver.domain.push.Versandbilanz;
import de.fubo.appserver.repository.push.PushAboRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Der Versandlauf: anstossen, einsammeln, Ergebnisse buchen (A25b, S8 Abschnitt 6.3).
 *
 * <h2>Eine Versandmethode fuer alle drei Anlaesse</h2>
 * Erinnerung, Terminabsage und Probeversand benutzen {@link #versende}. <b>Zwei Umsetzungen
 * waeren zwei Orte, an denen die Antwortbehandlung steht</b> - und die Zahl der Stellen, an
 * denen ein {@code 410} richtig behandelt werden muss, ist genau eine.
 *
 * <h2>Kein {@code @Transactional} - und das ist der Kern dieser Klasse</h2>
 * <b>Innerhalb einer offenen Transaktion darf kein HTTP-Aufruf stattfinden.</b> Ein Lauf mit
 * dreissig Empfaengern hielte sonst eine Verbindung aus dem Pool ueber dreissig Netzaufrufe
 * hinweg belegt - auf einem Raspberry Pi mit einem kleinen Pool ist das der Unterschied
 * zwischen "langsam" und "die Anwendung antwortet nicht mehr".
 *
 * <p>Deshalb traegt hier <b>keine</b> Methode eine Transaktionsgrenze. Jede Anweisung des
 * {@code PushAboRepository} laeuft als eigene, kurze Transaktion - das ist keine Luecke,
 * sondern die Absicht: Drei Phasen, drei Transaktionsgrenzen, und keine von ihnen umspannt
 * einen Netzaufruf.
 *
 * <p><b>Ein {@code @Transactional} an dieser Klasse waere still schaedlich</b>: Es faengt
 * niemand ab, und der Schaden zeigt sich erst unter Last. Wer eine Transaktion braucht - etwa
 * fuer einen Audit-Eintrag -, oeffnet sie <i>ausserhalb</i> und <i>nach</i> dem Versand.
 *
 * <h2>Nebenlaeufig unter einer Gesamtfrist, nicht seriell und nicht ueber {@code @Async}</h2>
 * Alle Empfaenger werden gleichzeitig angestossen; gewartet wird auf den Abschluss aller,
 * hoechstens aber {@code fubo.push.versand-frist-millis}. <b>Die Wartezeit ist damit das
 * Maximum der Einzelaufrufe und nicht ihre Summe</b>, und sie ist nach oben begrenzt,
 * unabhaengig von der Empfaengerzahl.
 *
 * <p>Zwei Gruende fuer diesen Weg, jeder fuer sich tragend:
 * <ol>
 *   <li><b>Der Listener der Terminabsage laeuft synchron im Anfrage-Thread des Admins</b> und
 *       innerhalb des Commits - die HTTP-Antwort geht erst hinaus, wenn er fertig ist. Seriell
 *       waeren dreissig Empfaenger im schlechtesten Fall zweieinhalb Minuten Ladekreis.</li>
 *   <li><b>Spring Boots Standard-Scheduler hat Poolgroesse 1.</b> Alle
 *       {@code @Scheduled}-Methoden teilen einen Thread: der Aufraeumlauf fuer Sitzungen, der
 *       A18-Auftrag, der alle fuenf Minuten Termine abschliesst und Teams fixiert, und der
 *       Erinnerungsauftrag. Ein serieller Versand blockierte diesen Thread ueber mehrere
 *       A18-Takte hinweg.</li>
 * </ol>
 *
 * <p><b>{@code @Async} waere der naheliegende und schlechtere Weg:</b> Es kostet eine eigene
 * Aktivierungsklasse, deren Vergessen die Annotation <i>wirkungslos macht, ohne
 * Fehlermeldung</i> - derselbe Fallstrick, den {@code SchedulingConfig} und
 * {@code CacheConfig} im Projekt schon zweimal aufgestellt haben -, dazu verschwindende
 * Ausnahmen und einen Wettlauf in jedem Test. Und es stellte keine einzige Nachricht
 * zusaetzlich zu.
 *
 * <h2>Offene Aufrufe werden abgebrochen und als Fehlversuch gebucht</h2>
 * Nicht sich selbst ueberlassen: Ein weiterlaufender Aufruf wollte sein Ergebnis schreiben,
 * nachdem der Vorgang beendet ist. <b>Der Preis ist benannt</b> - ein tatsaechlich
 * zugestellter, nur langsam bestaetigter Versand wird dabei als Fehlversuch gebucht, und fuenf
 * davon deaktivieren das Abonnement. Bei zehn Sekunden Frist trifft das nur einen dauerhaft
 * kranken Push-Dienst, und {@code /push/abo/anlegen} heilt den Eintrag beim naechsten
 * Anwendungsstart ohnehin wieder.
 */
@Service
public class PushVersandService {

    private static final Logger LOG = LoggerFactory.getLogger(PushVersandService.class);

    /**
     * Fehlversuche, ab denen ein Abonnement deaktiviert wird.
     *
     * <p><b>Eine Konstante und kein Konfigurationsfeld</b> - wie die Aufbewahrung abgelaufener
     * Sitzungen und aus demselben Grund: Es gibt keinen Anlass, sie zu verstellen. Ein weiteres
     * Pflichtfeld im Voll-Update der Konfiguration waere eine brechende Vertragsaenderung fuer
     * ein Detail, das niemand einstellen will.
     *
     * <p>Fuenf ist grosszuegig gewaehlt: Ein einzelner Ausfall eines Push-Dienstes soll keinen
     * Bestand kosten. <b>Und der Zaehler faellt bei jeder erfolgreichen Nachricht auf null</b>
     * zurueck - fuenf Fehlversuche heissen also "fuenf in Folge", nicht "fuenf insgesamt".
     */
    private static final int MAX_FEHLVERSUCHE = 5;

    private final PushVersender versender;
    private final PushAboRepository pushAboRepository;
    private final long fristMillis;
    private final Clock uhr;

    public PushVersandService(PushVersender versender,
                              PushAboRepository pushAboRepository,
                              FuboProperties eigenschaften,
                              Clock uhr) {
        this.versender = versender;
        this.pushAboRepository = pushAboRepository;
        this.fristMillis = eigenschaften.push().versandFristMillis();
        this.uhr = uhr;
    }

    /**
     * Versendet eine Nutzlast an alle uebergebenen Abonnements.
     *
     * <p><b>Der Aufrufer hat die Empfaenger bereits ausgewaehlt</b> - diese Methode prueft
     * keine der drei Versandbedingungen. Sie stehen in der Empfaengerabfrage
     * ({@code PushAboRepository}) beziehungsweise im fruehen Ausstieg des Aufrufers; hier noch
     * einmal zu pruefen hiesse, sie an einem zweiten Ort zu fuehren.
     *
     * @param abos     Zielabonnements; eine leere Liste ist zulaessig und der haeufigste Fall
     * @param nutzlast zu versendender Inhalt, derselbe fuer alle Empfaenger
     * @return was erreicht wurde; nie {@code null}
     */
    public Versandbilanz versende(List<PushAbo> abos, PushNutzlast nutzlast) {
        if (abos.isEmpty()) {
            return Versandbilanz.LEER;
        }

        // Phase 2: alle gleichzeitig anstossen. Die Reihenfolge der Karte bleibt die der
        // Empfaenger - sie wird unten fuer die Zuordnung Antwort zu Abonnement gebraucht.
        Map<PushAbo, CompletableFuture<PushAntwort>> laufend = new LinkedHashMap<>();
        for (PushAbo abo : abos) {
            laufend.put(abo, versender.versende(abo, nutzlast));
        }

        warteAufAlle(laufend.values(), abos.size());

        // Phase 3: Ergebnisse je Abonnement buchen, jede Anweisung fuer sich.
        OffsetDateTime jetzt = OffsetDateTime.now(uhr);
        int zugestellt = 0;
        for (Map.Entry<PushAbo, CompletableFuture<PushAntwort>> eintrag : laufend.entrySet()) {
            if (buche(eintrag.getKey(), eingesammelt(eintrag.getValue()), jetzt)) {
                zugestellt++;
            }
        }

        int empfaenger = (int) abos.stream().map(PushAbo::spielerId).distinct().count();
        return new Versandbilanz(empfaenger, abos.size(), zugestellt);
    }

    /**
     * Wartet auf alle Aufrufe, hoechstens bis zur Gesamtfrist.
     *
     * <p>Die drei Ausnahmen werden einzeln behandelt, weil sie Verschiedenes bedeuten - und
     * keine von ihnen darf den Lauf abbrechen: Die Ergebnisse der bereits fertigen Aufrufe
     * gehoeren in jedem Fall in die Datenbank.
     */
    private void warteAufAlle(java.util.Collection<CompletableFuture<PushAntwort>> laeufe,
                              int anzahl) {
        try {
            CompletableFuture.allOf(laeufe.toArray(CompletableFuture[]::new))
                    .get(fristMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            long offen = laeufe.stream().filter(lauf -> !lauf.isDone()).count();
            LOG.warn("Push-Versandfrist von {} ms abgelaufen: {} von {} Aufrufen noch offen. "
                    + "Sie werden abgebrochen und als Fehlversuch gebucht.",
                    fristMillis, offen, anzahl);
        } catch (InterruptedException e) {
            // Das Unterbrechungszeichen wird weitergegeben, sonst verschwindet es hier - der
            // Lauf selbst wird trotzdem zu Ende gebucht, damit kein Ergebnis verlorengeht.
            Thread.currentThread().interrupt();
            LOG.warn("Push-Versand wurde unterbrochen; die vorliegenden Ergebnisse werden "
                    + "trotzdem gebucht.");
        } catch (ExecutionException e) {
            // Kann nach dem Vertrag von PushVersender nicht auftreten - die Schnittstelle
            // liefert nie ein fehlgeschlagenes Future. Steht hier, weil get() sie verlangt,
            // und ausdruecklich protokolliert: Tritt sie doch auf, hat eine Umsetzung ihren
            // Vertrag gebrochen, und das soll man sehen.
            LOG.error("Ein Push-Aufruf ist mit einer Ausnahme beendet worden, obwohl der "
                    + "Versender das nicht tun darf.", e.getCause());
        }
    }

    /**
     * Holt die Antwort eines Aufrufs ab; ein noch offener wird abgebrochen.
     *
     * @return die Antwort des Dienstes oder ein Fehlversuch, wenn keine vorliegt
     */
    private static PushAntwort eingesammelt(CompletableFuture<PushAntwort> lauf) {
        if (!lauf.isDone()) {
            // true: der laufende Austausch soll mit abgebrochen werden, nicht nur das Future.
            lauf.cancel(true);
            return PushAntwort.ohneAntwort();
        }
        try {
            return lauf.join();
        } catch (CompletionException | CancellationException e) {
            return PushAntwort.ohneAntwort();
        }
    }

    /**
     * Schreibt das Ergebnis eines einzelnen Versands.
     *
     * <p><b>Die Zuordnung Antwort zu Reaktion steht nicht hier</b>, sondern in
     * {@code PushAntwort#von}. Diese Methode setzt nur um, was dort entschieden wurde.
     *
     * @return {@code true}, wenn der Push-Dienst die Nachricht angenommen hat
     */
    private boolean buche(PushAbo abo, PushAntwort antwort, OffsetDateTime jetzt) {
        switch (antwort.ergebnis()) {
            case ZUGESTELLT -> {
                pushAboRepository.erfolgVermerken(abo.id(), jetzt);
                return true;
            }
            case ERLOSCHEN -> pushAboRepository.deaktivieren(abo.id(), jetzt);
            case FEHLVERSUCH ->
                    pushAboRepository.fehlerVermerken(abo.id(), MAX_FEHLVERSUCHE, jetzt);
            case NUTZLAST_ZU_GROSS, SCHLUESSEL_ABGELEHNT -> {
                // Beides sind Fehler auf unserer Seite, keine des Abonnements: Die Zeile
                // bleibt unangetastet, auch der Zaehler. Fuenf zu grosse Nachrichten
                // deaktivierten sonst ein einwandfreies Abonnement, und ein
                // Konfigurationsfehler beim VAPID-Zugang loeschte den gesamten Bestand.
                // Protokolliert hat der Adapter bereits.
            }
        }
        return false;
    }
}
