package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.profil.NamensEintrag;
import de.fubo.appserver.domain.profil.Profileintrag;

import java.util.List;
import java.util.Set;

/**
 * Handgeschriebener Teil des {@link SpielerRepository}.
 *
 * <p>Alle drei Abfragen verbinden {@code profil.spieler} mit Unterabfragen auf andere
 * Tabellen. Ueber abgeleitete Methodennamen laesst sich das nicht ausdruecken, und eine
 * JPQL-Fassung braeuchte Assoziationen zwischen den Entities - die es hier bewusst nicht gibt.
 */
public interface SpielerRepositoryCustom {

    /**
     * Liefert alle aktiven Profile mit ihrem Belegtstatus, nach Namen sortiert.
     *
     * <p>Fuer die <b>Namensauswahl</b> beim Login: ohne gesperrte Profile, ohne das
     * Adminprofil. Die Verwaltungsuebersicht des Admins braucht das Gegenteil, siehe
     * {@link #findeProfilstammdaten()}.
     *
     * @return Liste fuer die Namensauswahl; leer, wenn keine aktiven Profile existieren
     */
    List<NamensEintrag> findeNamensliste();

    /**
     * Liefert <b>alle</b> Profile mit ihren Skillwerten - gesperrte und das Adminprofil
     * eingeschlossen (S3, Abschnitt 2).
     *
     * <p><b>Ohne Belegtstatus, anders als in der Anleitung.</b> Abschnitt 2.3 fuehrt ihn in
     * derselben Abfrage; hier ist er abgetrennt, weil dieses Ergebnis zwischengespeichert wird
     * und der Belegtstatus das nicht vertraegt. Er kommt aus
     * {@link #findeBelegteProfilIds()} und wird im Service zusammengefuehrt. Die Begruendung
     * steht am Record {@code Profileintrag}.
     *
     * @return alle Profile, Spielerprofile zuerst, das technische Adminkonto zuletzt
     */
    List<Profileintrag> findeProfilstammdaten();

    /**
     * Liefert die Ids aller Profile, auf die gerade eine gueltige Sitzung laeuft (A6).
     *
     * <p>Bewusst eine eigene, sehr schmale Abfrage: Sie laeuft bei jedem Aufruf der
     * Uebersicht, waehrend die Stammdaten aus dem Zwischenspeicher kommen. Der partielle
     * Index {@code ix_session_aktiv} ({@code WHERE widerrufen_am IS NULL}) haelt sie klein,
     * auch wenn die Tabelle mit abgelaufenen Sitzungen waechst.
     *
     * @return Menge der belegten Profil-Ids; leer, wenn niemand angemeldet ist
     */
    Set<Long> findeBelegteProfilIds();
}
