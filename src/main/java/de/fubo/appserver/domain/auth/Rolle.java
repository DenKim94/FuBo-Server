package de.fubo.appserver.domain.auth;

/**
 * Rolle einer angemeldeten Identitaet. In der Stufe {@link Stage#PIN_VERIFIED}
 * noch nicht gesetzt, deshalb ist die Spalte {@code profil.session.rolle} optional.
 */
public enum Rolle {

    /** Genau ein Admin, in der Datenbank ueber einen partiellen Unique-Index erzwungen. */
    ADMIN,

    /** Regulaerer Spieler mit hinterlegtem Profil. */
    USER,

    /** Temporaere Identitaet ohne Profil, belegt einen festen Gast-Slot. */
    GAST
}
