package de.fubo.appserver.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * Die zentrale PIN der Anwendung (A3); genau eine Zeile mit {@code id = 1}
 * (Singleton, per {@code ck_zugangsdaten_singleton} erzwungen).
 *
 * <p>Gespeichert wird ausschliesslich der BCrypt-Hash. Die Tabelle ist bewusst
 * <b>nicht</b> vorbefuellt: Ein Hash in einer Flyway-Migration waere ein Geheimnis in der
 * Git-Historie, und Migrationen sind unveraenderlich. Die erste Zeile entsteht beim Start
 * ueber {@code PinBootstrap}.
 *
 * <p>Kein {@code @GeneratedValue}: Der Schluessel ist fest 1. Damit Hibernate einen noch
 * nicht gespeicherten Zustand erkennt, ist {@link #version} ein Wrapper-Typ - bei manuell
 * vergebenem Schluessel ist der {@code null}-Wert der Version das einzige Merkmal dafuer.
 */
@Entity
@Table(name = "zugangsdaten", schema = "profil")
public class Zugangsdaten {

    /** Fester Wert 1. */
    @Id
    @Column(name = "id")
    private Short id;

    /** BCrypt-Hash der zentralen PIN; die Spalte ist {@code VARCHAR(72)} - BCrypt liefert 60 Zeichen. */
    @Column(name = "pin_hash", nullable = false, length = 72)
    private String pinHash;

    @Column(name = "geaendert_am", nullable = false)
    private OffsetDateTime geaendertAm;

    /** Id des aendernden Admin-Kontos; beim Bootstrap leer, da es noch keines gibt. */
    @Column(name = "geaendert_von")
    private Short geaendertVon;

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

    public String getPinHash() {
        return pinHash;
    }

    public void setPinHash(String pinHash) {
        this.pinHash = pinHash;
    }

    public OffsetDateTime getGeaendertAm() {
        return geaendertAm;
    }

    public void setGeaendertAm(OffsetDateTime geaendertAm) {
        this.geaendertAm = geaendertAm;
    }

    public Short getGeaendertVon() {
        return geaendertVon;
    }

    public void setGeaendertVon(Short geaendertVon) {
        this.geaendertVon = geaendertVon;
    }

    public Long getVersion() {
        return version;
    }
}
