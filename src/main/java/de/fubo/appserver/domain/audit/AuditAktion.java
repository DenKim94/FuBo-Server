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
    PIN_GEAENDERT
}
