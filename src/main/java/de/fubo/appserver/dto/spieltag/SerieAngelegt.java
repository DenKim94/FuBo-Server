package de.fubo.appserver.dto.spieltag;

import java.time.LocalDate;
import java.util.List;

/**
 * Antwort von {@code POST /api/v1/admin/serie/anlegen} (S4, Abschnitt 4.2).
 *
 * <h2>Zwei Listen statt einer Zahl</h2>
 * Weggabelung A: Eine Serie <b>ueberspringt</b> kollidierende Zeitpunkte, statt ganz zu
 * scheitern. {@code uq_termin_zeit} ist eine <i>globale</i> Bedingung - bei einer
 * Zwoelf-Wochen-Serie genuegt ein einziger bestehender Einzeltermin, um das Anlegen
 * abzulehnen. Den Admin den Konflikt vorher selbst finden zu lassen waere Arbeit, die der
 * Server erledigen kann.
 *
 * <p>Damit die uebersprungenen Zeitpunkte nicht untergehen, stehen sie <b>namentlich</b> in
 * der Antwort und nicht nur als Anzahl: Eine Zahl beantwortete nicht, welcher Termin fehlt,
 * und der Admin muesste die Liste doch wieder selbst durchsehen.
 *
 * @param serieId               Id der angelegten Serie
 * @param titel                 uebernommener Titel, bereits von Randleerzeichen befreit
 * @param angelegteTermine      die erzeugten Termine in zeitlicher Reihenfolge
 * @param uebersprungeneTermine Zeitpunkte, an denen bereits ein Termin stand
 */
public record SerieAngelegt(Long serieId,
                            String titel,
                            List<TerminAngelegt> angelegteTermine,
                            List<LocalDate> uebersprungeneTermine) {
}
