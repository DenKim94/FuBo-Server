package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.AktiveSitzung;

import java.util.Optional;

/**
 * Handgeschriebener Teil des {@link SessionRepository}. Enthaelt die Abfragen, die sich
 * mit Spring Data allein nicht korrekt abbilden lassen.
 *
 * <p>Spring Data setzt beide Teile zu einem Bean zusammen; der Service kennt nur
 * {@link SessionRepository} und sieht nicht, welcher Teil wie umgesetzt ist.
 */
public interface SessionRepositoryCustom {

    /**
     * Prueft die Sitzung und verlaengert das Leerlauf-Fenster in einem einzigen,
     * atomaren Statement.
     *
     * @param tokenHash       SHA-256 des Cookie-Tokens als Hex
     * @param leerlaufMinuten Laenge des gleitenden Fensters aus der Admin-Konfiguration
     * @return die Sitzungsdaten oder {@link Optional#empty()}, wenn die Sitzung
     *         unbekannt, widerrufen oder abgelaufen ist
     */
    Optional<AktiveSitzung> pruefenUndVerlaengern(String tokenHash, int leerlaufMinuten);

    /**
     * Prueft die Sitzung, <b>ohne</b> das Leerlauf-Fenster zu verschieben und ohne
     * {@code letzte_aktivitaet_am} fortzuschreiben.
     *
     * <p>Gegenstueck zu {@link #pruefenUndVerlaengern} fuer Hintergrundaufrufe des
     * Frontends (Abschnitt 10.8, offener Punkt 7). Ohne diesen zweiten Pfad liefe das
     * gleitende Fenster nie ab, solange irgendein Browser-Tab pollt - "15 Minuten
     * Inaktivitaet" wuerde dann den offenen Tab messen und nicht den Nutzer.
     *
     * <p>Die Bedingungen sind wortgleich mit denen des schreibenden Pfades. Sie stehen
     * bewusst zweimal im SQL und nicht in einer gemeinsamen Zeichenkette: Ein {@code UPDATE}
     * wertet seine WHERE-Klausel unter einer Zeilensperre aus, ein {@code SELECT} nicht -
     * die beiden Anweisungen sind trotz gleicher Bedingung nicht dasselbe, und eine
     * geteilte Konstante wuerde suggerieren, dass sie es waeren.
     *
     * @param tokenHash SHA-256 des Cookie-Tokens als Hex
     * @return die Sitzungsdaten oder {@link Optional#empty()}, wenn die Sitzung
     *         unbekannt, widerrufen oder abgelaufen ist
     */
    Optional<AktiveSitzung> pruefen(String tokenHash);
}
