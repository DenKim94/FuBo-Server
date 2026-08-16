package de.fubo.appserver.domain.profil;

import de.fubo.appserver.domain.auth.Rolle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * Spielerprofil aus {@code profil.spieler} (A13).
 *
 * <p>In S2 wird die Entity nur lesend gebraucht: fuer die Rolle, die beim Stufenwechsel in
 * die Sitzung uebernommen wird, und fuer die Pruefung, ob das Profil aktiv ist. Das
 * Admin-CRUD entsteht in S3.
 *
 * <p><b>Die Skillwerte stehen bewusst nicht hier</b>, sondern in {@code profil.spieler_skill}
 * (A12). Waeren sie ein Feld dieser Entity, muesste bei jedem DTO-Bau erneut daran gedacht
 * werden, sie wegzulassen. So kann ein Profil gar nicht versehentlich mit Skillwerten in
 * einer Antwort landen.
 */
@Entity
@Table(name = "spieler", schema = "profil")
public class Spieler {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /**
     * Wiederverwendung von {@link Rolle} aus dem Auth-Bereich, obwohl die Spalte per
     * {@code ck_spieler_rolle} nur {@code ADMIN} und {@code USER} zulaesst: Der Wert wird
     * beim Stufenwechsel unveraendert in {@code profil.session.rolle} uebernommen, und ein
     * zweiter, fast gleicher Aufzaehlungstyp braeuchte an dieser Stelle eine Umwandlung,
     * die nichts pruefen wuerde, was die Datenbank nicht schon prueft. {@code GAST}
     * entsteht ausschliesslich im Gast-Login (Abschnitt 8) und nie aus einem Profil.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rolle", nullable = false, length = 10)
    private Rolle rolle;

    /** Inaktive Profile erscheinen nicht in der Namensliste und koennen sich nicht anmelden. */
    @Column(name = "aktiv", nullable = false)
    private boolean aktiv;

    @Column(name = "erstellt_am", nullable = false)
    private OffsetDateTime erstelltAm;

    @Column(name = "geaendert_am", nullable = false)
    private OffsetDateTime geaendertAm;

    /** Optimistic Locking (A5); wird ausschliesslich von Hibernate gepflegt. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Rolle getRolle() {
        return rolle;
    }

    public void setRolle(Rolle rolle) {
        this.rolle = rolle;
    }

    public boolean isAktiv() {
        return aktiv;
    }

    public void setAktiv(boolean aktiv) {
        this.aktiv = aktiv;
    }

    public OffsetDateTime getErstelltAm() {
        return erstelltAm;
    }

    public void setErstelltAm(OffsetDateTime erstelltAm) {
        this.erstelltAm = erstelltAm;
    }

    public OffsetDateTime getGeaendertAm() {
        return geaendertAm;
    }

    public void setGeaendertAm(OffsetDateTime geaendertAm) {
        this.geaendertAm = geaendertAm;
    }

    public Long getVersion() {
        return version;
    }
}
