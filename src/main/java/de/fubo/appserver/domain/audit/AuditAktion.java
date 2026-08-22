package de.fubo.appserver.domain.audit;

/**
 * Protokollierte Vorgaenge im {@code profil.audit_log}.
 *
 * <p>Ein Aufzaehlungstyp statt freier Zeichenketten: Die Werte landen in einer
 * {@code VARCHAR(50)}-Spalte und werden spaeter zum Filtern verwendet. Ein Tippfehler
 * in einem Literal faellt sonst erst beim Auswerten auf - dann fehlen die Eintraege
 * rueckwirkend.
 *
 * <p>Die Liste waechst mit den Meilensteinen (Adminaktionen in S3, Generierungslaeufe in
 * S5, Ergebniskorrekturen in S6). Hier stehen nur die Vorgaenge aus S2.
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
    ADMIN_ANGEMELDET
}
