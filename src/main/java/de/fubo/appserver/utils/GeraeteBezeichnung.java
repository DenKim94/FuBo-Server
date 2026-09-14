package de.fubo.appserver.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Leitet aus dem {@code User-Agent}-Kopf eine kurze Geraetebezeichnung ab (A25c, S8
 * Abschnitt 7.2).
 *
 * <h2>Warum der Server sie bestimmt und nicht der Client</h2>
 * Ein frei waehlbarer Anzeigename waere eine vom Client bestimmte Zeichenkette, die in der
 * Oberflaeche landen kann - ohne Gewinn, denn der Zweck ist allein, dass ein Spieler sein
 * <b>eigenes</b> Geraet in einer Liste wiedererkennt. Ein Eingabefeld dafuer gibt es nicht und
 * wird es nicht geben.
 *
 * <h2>Warum nicht einfach der gekuerzte {@code User-Agent}</h2>
 * Weil er nichts zeigt. Die ersten achtzig Zeichen eines ueblichen Kopfes lauten
 * "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, li" - darin ist
 * weder der Browser noch etwas Unterscheidbares zu erkennen. "Chrome auf Mac" leistet, wofuer
 * das Feld da ist.
 *
 * <h2>Es ist eine Heuristik, und sie darf danebenliegen</h2>
 * {@code User-Agent}-Koepfe sind seit Jahrzehnten voller Altlasten: Jeder Browser nennt
 * "Mozilla", Edge nennt sich zusaetzlich "Chrome", Chrome nennt sich zusaetzlich "Safari".
 * <b>Die Reihenfolge der Pruefungen ist deshalb tragend</b> und nicht beliebig - die
 * spezifischste Kennung zuerst.
 *
 * <p><b>Ein Fehlgriff kostet nichts</b>: Es steht ein etwas falscher Name in einer Liste, die
 * nur der Eigentuemer sieht. Das ist der Grund, warum hier eine kurze Tabelle genuegt und
 * keine Bibliothek gebraucht wird - und warum der Rueckfall der gekuerzte Rohtext ist statt
 * einer Ausnahme.
 *
 * <h2>Hier und nicht in {@code service/push}</h2>
 * Zustandsloser Helfer ohne Spring-Abhaengigkeit. {@code jakarta.servlet} ist in {@code utils}
 * erlaubt - die Regel richtet sich gegen Spring-Kontext und Zustand, nicht gegen die
 * Servlet-API; {@link ClientIpErmittler} ist das Vorbild.
 */
public final class GeraeteBezeichnung {

    /** Breite von {@code profil.push_abo.geraet_bezeichnung}. */
    private static final int MAX_LAENGE = 80;

    /** Browserkennungen, <b>spezifischste zuerst</b>; siehe Klassenkommentar. */
    private static final String[][] BROWSER = {
            {"Edg/", "Edge"},           // nennt sich zusaetzlich Chrome und Safari
            {"OPR/", "Opera"},          // dito
            {"SamsungBrowser/", "Samsung Internet"},
            {"Firefox/", "Firefox"},
            {"Chrome/", "Chrome"},      // nennt sich zusaetzlich Safari
            {"Safari/", "Safari"}
    };

    /** Plattformkennungen, ebenfalls spezifischste zuerst. */
    private static final String[][] PLATTFORM = {
            {"iPhone", "iPhone"},
            {"iPad", "iPad"},
            {"Android", "Android"},
            {"CrOS", "ChromeOS"},
            {"Windows", "Windows"},
            {"Macintosh", "Mac"},
            {"Linux", "Linux"}
    };

    private GeraeteBezeichnung() {
    }

    /**
     * Bildet die Bezeichnung aus dem {@code User-Agent} der Anfrage.
     *
     * @param anfrage laufende Anfrage
     * @return etwa {@code "Chrome auf Android"}, sonst der gekuerzte Rohtext; {@code null},
     *         wenn kein {@code User-Agent} mitgeschickt wurde - die Spalte ist nullbar, und
     *         ein erfundener Wert waere schlechter als keiner
     */
    public static String ermitteln(HttpServletRequest anfrage) {
        return ausKopf(anfrage.getHeader("User-Agent"));
    }

    /**
     * Dieselbe Ableitung aus dem blossen Kopfwert.
     *
     * <p>Paketprivat getrennt, damit sie ohne Servlet-Umgebung pruefbar ist.
     *
     * @param kopf Wert des {@code User-Agent}-Kopfes, darf {@code null} sein
     * @return die Bezeichnung oder {@code null}
     */
    static String ausKopf(String kopf) {
        if (kopf == null || kopf.isBlank()) {
            return null;
        }

        String browser = ersterTreffer(kopf, BROWSER);
        String plattform = ersterTreffer(kopf, PLATTFORM);

        if (browser != null && plattform != null) {
            return browser + " auf " + plattform;
        }
        if (browser != null) {
            return browser;
        }
        if (plattform != null) {
            return plattform;
        }
        // Rueckfall: der Rohtext, gekuerzt. Unschoen, aber ehrlich - und er unterscheidet
        // wenigstens zwei sehr verschiedene Geraete voneinander.
        String bereinigt = kopf.strip();
        return bereinigt.length() <= MAX_LAENGE ? bereinigt : bereinigt.substring(0, MAX_LAENGE);
    }

    /** Liefert die Bezeichnung der ersten passenden Kennung, sonst {@code null}. */
    private static String ersterTreffer(String kopf, String[][] tabelle) {
        for (String[] zeile : tabelle) {
            if (kopf.contains(zeile[0])) {
                return zeile[1];
            }
        }
        return null;
    }
}
