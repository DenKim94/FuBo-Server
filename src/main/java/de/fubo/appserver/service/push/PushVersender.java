package de.fubo.appserver.service.push;

import de.fubo.appserver.domain.push.PushAbo;
import de.fubo.appserver.domain.push.PushAntwort;
import de.fubo.appserver.domain.push.PushNutzlast;

import java.util.concurrent.CompletableFuture;

/**
 * Versendet eine Benachrichtigung an genau ein Abonnement (A25b, S8 Abschnitt 6.1).
 *
 * <h2>Warum es diese Schnittstelle gibt</h2>
 * <b>Ohne sie sind Empfaengerauswahl und Einmalversand nicht pruefbar, ohne einen fremden
 * Dienst anzusprechen</b> - und kein Test darf das. Die Testdoppelung merkt sich die Aufrufe
 * und liefert eine einstellbare Antwort; Vorbild ist {@code support.MailErsatz} aus S7.
 *
 * <h2>Warum die Antwort ein {@link CompletableFuture} ist</h2>
 * <b>Die Nebenlaeufigkeit gehoert hierher und nicht in den aufrufenden Dienst.</b> Der
 * Listener der Terminabsage laeuft synchron im Anfrage-Thread des Admins; seriell versendet
 * waeren dreissig Empfaenger im schlechtesten Fall zweieinhalb Minuten Ladekreis. Die
 * Wartezeit soll deshalb das <i>Maximum</i> der Einzelaufrufe sein und nicht ihre Summe.
 *
 * <p>Eine synchrone Methode koennte das nur mit einem eigenen Thread-Pool erreichen - und
 * genau den vermeidet Weggabelung C: Ein Pool waere eine weitere Bean mit eigener
 * Konfiguration, und der naheliegende gemeinsame {@code ForkJoinPool} hat auf der Zielhardware
 * die Groesse der Kernzahl. Dreissig blockierende Netzaufrufe darauf liefen in Schueben von
 * drei und damit fast seriell. <b>{@code HttpClient#sendAsync} bringt seinen Ausfuehrer
 * bereits mit</b>; die Schnittstelle gibt ihn nur weiter.
 *
 * <p><b>Fuer die Testdoppelung kostet das nichts:</b>
 * {@code CompletableFuture.completedFuture(antwort)} ist fertig, bevor der Aufrufer sie
 * ansieht - kein Wettlauf, kein Latch, eine gewoehnliche Zusicherung.
 *
 * <h2>Sie wirft nicht</h2>
 * Jeder Fehlschlag - Netzfehler, unbrauchbarer Schluessel, zu grosse Nutzlast - kommt als
 * {@link PushAntwort} zurueck. <b>Der Grund ist der Aufrufer:</b> Ein Lauf mit dreissig
 * Empfaengern darf nicht am ersten scheitern, und der Listener der Terminabsage darf
 * ueberhaupt nichts nach aussen geben - eine Ausnahme aus einem
 * {@code AFTER_COMMIT}-Callback propagiert zum Aufrufer und machte aus einer gespeicherten
 * Absage einen {@code 500}.
 */
public interface PushVersender {

    /**
     * Verschluesselt die Nutzlast fuer dieses Abonnement und schickt sie an den Push-Dienst.
     *
     * @param abo      Zielabonnement
     * @param nutzlast zu versendender Inhalt
     * @return die Antwort des Dienstes, sobald sie vorliegt; nie ein fehlgeschlagenes Future
     */
    CompletableFuture<PushAntwort> versende(PushAbo abo, PushNutzlast nutzlast);
}
