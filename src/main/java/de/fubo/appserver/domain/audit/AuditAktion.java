package de.fubo.appserver.domain.audit;

/**
 * Protokollierte Vorgaenge im {@code profil.audit_log}.
 *
 * <p>Ein Aufzaehlungstyp statt freier Zeichenketten: Die Werte landen in einer
 * {@code VARCHAR(50)}-Spalte und werden spaeter zum Filtern verwendet. Ein Tippfehler
 * in einem Literal faellt sonst erst beim Auswerten auf - dann fehlen die Eintraege
 * rueckwirkend.
 *
 * <p>Die Liste waechst mit den Meilensteinen (Generierungslaeufe in S5,
 * Ergebniskorrekturen in S6). Hier stehen die Vorgaenge aus S2 und S2b.
 */
public enum AuditAktion {

    /** Anmeldeversuch mit falscher zentraler PIN. */
    PIN_FEHLVERSUCH,

    /** Der PIN-Endpunkt wurde fuer eine Adresse oder insgesamt gesperrt. */
    PIN_GESPERRT,

    /** Anmeldeversuch am Adminzugang mit falschem Passwort. */
    ADMIN_LOGIN_FEHLVERSUCH,

    /**
     * Erfolgreiche Anmeldung des Admins.
     *
     * <p>Anders als bei den Spielern wird der <i>Erfolg</i> hier protokolliert und nicht nur
     * der Fehlversuch: Der Adminzugang ist der einzige mit erhoehten Rechten, und spaetere
     * Adminaktionen (S3, S6) lassen sich nur dann einer konkreten Sitzung zuordnen, wenn
     * deren Beginn im Protokoll steht.
     */
    ADMIN_ANGEMELDET,

    /**
     * Zuruecksetzen des Admin-Passworts wurde angefordert und die Bestaetigungs-PIN
     * versendet (S2b).
     *
     * <p>Der Eintrag entsteht in derselben Transaktion wie der Vorgang. Scheitert der
     * Versand, rollt er mit zurueck - das Protokoll behauptet dann nicht, eine Nachricht
     * sei unterwegs.
     */
    PASSWORT_RESET_ANGEFORDERT,

    /**
     * Falsche Bestaetigungs-PIN beim Einloesen (S2b).
     *
     * <p>Der Eintrag ist der Beleg dafuer, dass jemand PINs raet. Er wird geschrieben,
     * <b>bevor</b> die Ablehnung geworfen wird und ueberlebt deren Rollback nicht - der
     * Versuchszaehler dagegen schon, denn er laeuft in einer eigenen Transaktion.
     */
    PASSWORT_RESET_FEHLVERSUCH,

    /**
     * Das Admin-Passwort wurde geaendert (S2b).
     *
     * <p>Das Detail {@code weg} unterscheidet {@code reset} (per Bestaetigungs-PIN) von
     * {@code aendern} (im angemeldeten Zustand mit dem alten Passwort).
     */
    PASSWORT_GEAENDERT,

    /**
     * Die zentrale PIN wurde durch den Admin gewechselt (A3, S2b).
     *
     * <p>Der Vorgang meldet ausnahmslos alle Sitzungen ab und ist damit im Betrieb sofort
     * spuerbar - er gehoert deshalb nachvollziehbar ins Protokoll.
     */
    PIN_GEAENDERT,

    /**
     * Der Admin hat ein Spielerprofil angelegt (A13, S2b Abschnitt 8).
     *
     * <p>Die drei Aktionen der Spielerverwaltung tragen als {@code entitaet} den Wert
     * {@code spieler} und als {@code entitaet_id} die betroffene Profil-Id. Handelnder ist
     * immer der Admin, nicht das betroffene Profil - sonst waere das Protokoll die einzige
     * Stelle, an der ein geloeschtes Profil noch auftaucht.
     */
    PROFIL_ANGELEGT,

    /** Der Admin hat ein Spielerprofil endgueltig entfernt (S2b Abschnitt 8). */
    PROFIL_ENTFERNT,

    /** Der Admin hat ein Spielerprofil gesperrt (S2b Abschnitt 8). */
    PROFIL_BLOCKIERT,

    /** Der Admin hat ein gesperrtes Spielerprofil wieder freigegeben (S2b Abschnitt 8). */
    PROFIL_FREIGEGEBEN,

    /**
     * Der Admin hat Name oder Skillwerte eines Profils geaendert (S3, Abschnitt 3.6).
     *
     * <p>Die Details nennen den alten und den neuen Namen sowie die <i>gesetzten</i>
     * Skillwerte - nicht die vorherigen. Der Eintrag beantwortet "wer hat wann was geaendert",
     * nicht "wie war es vorher"; eine vollstaendige Aenderungshistorie waere eine eigene
     * Entscheidung mit eigenem Datenmodell, und die Loeschfrist von 90 Tagen machte sie
     * ohnehin lueckenhaft.
     */
    PROFIL_GEAENDERT,

    /**
     * Der Admin hat seinen eigenen Anmeldenamen geaendert (S3, {@code /admin/name/aendern}).
     *
     * <p><b>Ein eigener Wert und nicht {@link #PROFIL_GEAENDERT}</b>, obwohl technisch
     * dieselbe Spalte geschrieben wird: Der Name des Adminprofils ist seit dem 29.08.2026
     * zugleich der Anmeldename und damit ein Anmeldemerkmal. Er gehoert in dieselbe Reihe wie
     * {@link #PASSWORT_GEAENDERT} und {@link #PIN_GEAENDERT} - wer das Protokoll nach
     * Aenderungen an den Zugangsdaten durchsieht, soll ihn dort finden und nicht zwischen
     * Stammdatenpflege.
     */
    ADMIN_NAME_GEAENDERT,

    /**
     * Der Admin hat die anwendungsweite Konfiguration geaendert (S3, Abschnitt 5.5).
     *
     * <p>Als {@code entitaet} steht {@code app_config}, als {@code entitaet_id} die 1 - die
     * Tabelle traegt genau eine Zeile.
     *
     * <p><b>Hier stehen alte <i>und</i> neue Werte in den Details</b>, anders als bei
     * {@link #PROFIL_GEAENDERT}. Es sind hoechstens elf Werte, sie gelten anwendungsweit, und
     * die Betriebsfrage "seit wann steht das Leerlauf-Fenster auf 60 Minuten" ist ohne den alten
     * Wert nicht zu beantworten. Ausgenommen ist {@code halleAbsageVorlage}: Ein mehrzeiliger
     * Text in jedem Eintrag blaehte die Tabelle auf, ohne etwas zu belegen - dort genuegt der
     * Vermerk, dass die Vorlage geaendert wurde.
     *
     * <p>Aendert ein Speichervorgang keinen einzigen Wert, bleiben die Details leer. Der Eintrag
     * steht trotzdem: Er belegt, wer wann gespeichert hat, und {@code geaendert_am} ist ebenfalls
     * gewandert.
     */
    KONFIG_GEAENDERT,

    /**
     * Der Admin hat belegte Gastplaetze freigegeben und die zugehoerigen Gaeste abgemeldet
     * (Vorgabe vom 30.08.2026).
     *
     * <p>Als {@code entitaet} steht {@code gast_slot}, {@code entitaet_id} bleibt <b>leer</b>: Der
     * Vorgang betrifft in der Regel mehrere Zeilen, und eine willkuerlich herausgegriffene Nummer
     * waere irrefuehrend. Die betroffenen Plaetze stehen als Liste in den Details.
     *
     * <p><b>Das Detail {@code warAktiv} je Platz ist der eigentliche Beleg.</b> Es unterscheidet
     * zwei sehr verschiedene Vorgaenge unter demselben Aufruf: das Aufraeumen verwaister Zeilen,
     * hinter denen niemand mehr sass, und das Hinauswerfen eines angemeldeten Gastes.
     */
    GAST_ABGEMELDET
}
