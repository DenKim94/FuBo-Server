package de.fubo.appserver.domain.profil;

import de.fubo.appserver.domain.auth.Rolle;

import java.util.Map;

/**
 * Die Stammdaten eines Profils samt Skillwerten - Ergebnis der Uebersichtsabfrage
 * (S3, Abschnitt 2.3).
 *
 * <p>Wie {@code NamensEintrag} ein schlankes Wertobjekt und <b>kein</b> JPA-Entity: Die Zeile
 * entsteht aus einer Abfrage mit Unterabfrage ueber drei Tabellen und ist nicht vom
 * Persistence-Context verwaltet. Der Typ ueberschreitet die API-Grenze nie; nach aussen geht
 * {@code dto.admin.SpielerDetails}.
 *
 * <h2>Warum der Belegtstatus hier fehlt</h2>
 * Die Anleitung fuehrt ihn in derselben Abfrage. Er steht hier trotzdem nicht drin, und der
 * Grund ist der Zwischenspeicher: Diese Stammdaten werden gecacht, der Belegtstatus darf es
 * nicht. Er wird aus den aktiven Sitzungen abgeleitet (A6) und aendert sich, ohne dass jemand
 * ein Profil anfasst - jede Anmeldung, jeder Ablauf, jeder Logout veraendert ihn. In einem
 * Zwischenspeicher, der nur bei Profilaenderungen verworfen wird, fiele er ein und bliebe
 * beliebig lange falsch. Genau das widerspraeche der Eigenschaft, wegen der er ueberhaupt
 * abgeleitet und nicht gespeichert wird: <i>er kann nicht veralten</i>.
 *
 * <p>Zusammengefuehrt werden beide Teile in {@code SpielerVerwaltungService#uebersicht()}.
 *
 * @param spielerId Id des Profils
 * @param name      Anzeigename
 * @param rolle     {@link Rolle#ADMIN} oder {@link Rolle#USER}
 * @param aktiv     {@code false} bedeutet gesperrt
 * @param skills    Wert je Kategorieschluessel; enthaelt nur aktive Kategorien, zu denen eine
 *                  Zeile existiert - eine fehlende Kategorie ist ein ungepflegtes Profil
 */
public record Profileintrag(Long spielerId, String name, Rolle rolle, boolean aktiv,
                            Map<String, Integer> skills) {

    /**
     * Kompakter Konstruktor, der die Skillkarte unveraenderlich macht.
     *
     * <p><b>Das ist hier nicht Kosmetik, sondern noetig:</b> Der Record liegt im
     * Zwischenspeicher und wird an jeden Aufrufer <i>dieselbe</i> Instanz herausgegeben. Eine
     * veraenderliche Karte koennte ein Aufrufer umschreiben - und alle folgenden bekaemen die
     * Aenderung mit, ohne dass sie je in der Datenbank stuende.
     */
    public Profileintrag {
        skills = skills == null ? Map.of() : Map.copyOf(skills);
    }
}
