package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.spieltag.Terminserie;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Zugriff auf {@code spieltag.terminserie} (S4, Abschnitt 4).
 *
 * <p><b>Ohne eigene Abfragen, und das bleibt vorerst so.</b> Eine Serie wird angelegt und
 * danach nicht mehr angefasst: Ihre Termine entstehen sofort als eigene Zeilen, eine
 * spaetere Aenderung an der Regel wirkte nicht auf sie zurueck. Es gibt deshalb weder einen
 * Endpunkt, der eine Serie aendert, noch einen, der sie loescht -
 * {@code fk_termin_serie} hat bewusst kein {@code ON DELETE}, solange Termine daran haengen.
 *
 * <p>Die Termine einer Serie sind trotzdem auffindbar: Sie tragen ihre {@code serieId} in
 * der Terminliste, und der partielle Index {@code ix_termin_serie} deckt die Suche ab.
 */
public interface TerminserieRepository extends JpaRepository<Terminserie, Long> {
}
