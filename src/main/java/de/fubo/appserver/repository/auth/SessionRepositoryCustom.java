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
     * @param tokenHash      SHA-256 des Cookie-Tokens als Hex
     * @param leerlaufMinuten Laenge des gleitenden Fensters aus der Admin-Konfiguration
     * @return die Sitzungsdaten oder {@link Optional#empty()}, wenn die Sitzung
     *         unbekannt, widerrufen oder abgelaufen ist
     */
    Optional<AktiveSitzung> pruefenUndVerlaengern(String tokenHash, int leerlaufMinuten);
}
