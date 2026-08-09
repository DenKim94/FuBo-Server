package de.fubo.appserver.domain.auth;

/**
 * Selbsteinschaetzung eines Gastes (A17). Bestimmt, welche Werte aus
 * {@code profil.gast_vorlage} fuer die Teamgenerierung herangezogen werden.
 */
public enum GastStufe {
    STARK,
    MITTEL,
    SCHWACH
}
