package de.fubo.appserver.domain.config;

/**
 * Verfahren der Teamgenerierung (A15, Details in S5). Beide Algorithmen nutzen dieselbe
 * Zielfunktion und dieselbe Datengrundlage und sind gegeneinander austauschbar.
 *
 * <p>Die zulaessigen Werte sind zusaetzlich per CHECK-Constraint
 * {@code ck_app_config_algo} in der Datenbank festgeschrieben.
 */
public enum AlgorithmType {

    /**
     * Default, exakt: vollstaendige Enumeration aller Splits der Groesse {@code n/2}.
     * Liefert das globale Optimum, bei bis zu 22 Teilnehmern beherrschbar
     * ({@code C(22,11) ~ 705.000}), skaliert darueber nicht.
     */
    EXHAUSTIV,

    /**
     * Skalierbar: seed-basierte lokale Suche mit Paar-Tausch, Laufzeit
     * {@code O(Iterationen * n^2)}. Liefert je Seed eine andere, nah-optimale Loesung.
     */
    HEURISTIK
}
