package de.fubo.appserver.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * Zugangsdaten des Admins (A22); genau eine Zeile mit {@code id = 1}
 * (Singleton, per {@code ck_admin_konto_singleton} erzwungen).
 *
 * <p>Die Tabelle traegt nur, was zur Anmeldung des Admins gehoert - Name und Rolle stehen
 * im verknuepften Profil ({@code profil.spieler}). Diese Trennung ist der Grund, warum es
 * ueberhaupt zwei Tabellen gibt: Der Admin ist zugleich Spieler und taucht in Namensliste,
 * Teilnahme und Teameinteilung wie jeder andere auf.
 *
 * <p><b>Zwei Stellen erzwingen "genau ein Admin":</b> {@code ck_admin_konto_singleton} hier
 * und der partielle Unique-Index {@code uq_spieler_genau_ein_admin} auf
 * {@code profil.spieler}. Beide sind noetig - die eine verhindert ein zweites Konto, die
 * andere ein zweites Profil mit der Rolle {@code ADMIN}.
 *
 * <p>Kein {@code @GeneratedValue}: Der Schluessel ist fest 1. Damit Hibernate einen noch
 * nicht gespeicherten Zustand erkennt, ist {@link #version} ein Wrapper-Typ - bei manuell
 * vergebenem Schluessel ist der {@code null}-Wert der Version das einzige Merkmal dafuer.
 */
@Entity
@Table(name = "admin_konto", schema = "profil")
public class AdminKonto {

    /** Fester Wert 1. */
    @Id
    @Column(name = "id")
    private Short id;

    /**
     * Profil des Admins. Als reiner Fremdschluesselwert und nicht als {@code @ManyToOne} -
     * eine Assoziation laedt entweder unnoetig das ganze Profil oder erzwingt
     * Lazy-Loading ausserhalb der Transaktion, und {@code open-in-view=false} ist gesetzt.
     */
    @Column(name = "spieler_id", nullable = false)
    private Long spielerId;

    /** BCrypt-Hash; die Spalte ist {@code VARCHAR(72)} - BCrypt liefert 60 Zeichen. */
    @Column(name = "passwort_hash", nullable = false, length = 72)
    private String passwortHash;

    /** Zieladresse der Bestaetigungs-PIN beim Passwort-Reset (A22, S2b). */
    @Column(name = "email", nullable = false, length = 120)
    private String email;

    @Column(name = "passwort_geaendert_am", nullable = false)
    private OffsetDateTime passwortGeaendertAm;

    /** Optimistic Locking (A5); wird ausschliesslich von Hibernate gepflegt. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Short getId() {
        return id;
    }

    public void setId(Short id) {
        this.id = id;
    }

    public Long getSpielerId() {
        return spielerId;
    }

    public void setSpielerId(Long spielerId) {
        this.spielerId = spielerId;
    }

    public String getPasswortHash() {
        return passwortHash;
    }

    public void setPasswortHash(String passwortHash) {
        this.passwortHash = passwortHash;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public OffsetDateTime getPasswortGeaendertAm() {
        return passwortGeaendertAm;
    }

    public void setPasswortGeaendertAm(OffsetDateTime passwortGeaendertAm) {
        this.passwortGeaendertAm = passwortGeaendertAm;
    }

    public Long getVersion() {
        return version;
    }
}
