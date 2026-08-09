package de.fubo.appserver.domain.auth;

/**
 * Stufe des zweistufigen Logins (A14). Wird serverseitig erzwungen und in der
 * Filterchain auf eine Spring-Security-Authority abgebildet.
 */
public enum Stage {

    /** Zentrale PIN geprueft, Identitaet noch nicht gewaehlt. */
    PIN_VERIFIED,

    /** Name gewaehlt oder als Gast angemeldet. Erst hier existiert eine Rolle. */
    PROFILE_AUTHENTICATED
}
