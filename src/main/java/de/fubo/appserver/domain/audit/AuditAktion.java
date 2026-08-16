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
    PIN_GESPERRT
}
