package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.profil.NamensEintrag;

import java.util.List;

/**
 * Handgeschriebener Teil des {@link SpielerRepository} fuer die Namensliste.
 *
 * <p>Die Abfrage verbindet {@code profil.spieler} mit einer {@code EXISTS}-Unterabfrage auf
 * {@code profil.session}. Ueber abgeleitete Methodennamen laesst sich das nicht ausdruecken,
 * und eine JPQL-Fassung braeuchte eine Assoziation zwischen den Entities - die es hier
 * bewusst nicht gibt.
 */
public interface SpielerRepositoryCustom {

    /**
     * Liefert alle aktiven Profile mit ihrem Belegtstatus, nach Namen sortiert.
     *
     * @return Liste fuer die Namensauswahl; leer, wenn keine aktiven Profile existieren
     */
    List<NamensEintrag> findeNamensliste();
}
