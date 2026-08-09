package de.fubo.appserver.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Serverseitig gespeicherte Sitzung; im Cookie liegt nur der opake Token.
 *
 * <p>Diese Entity deckt die einfachen Schreibpfade ab (Anlegen). Die Pruefung bei jedem
 * Request laeuft bewusst nicht ueber JPA, sondern als bedingtes {@code UPDATE ... RETURNING}
 * im Repository - siehe {@code SessionRepositoryImpl}.
 */
@Entity
@Table(name = "session", schema = "profil")
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 des Tokens als Hex, 64 Zeichen. Der Token selbst wird nie gespeichert. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Profil-Id; bleibt in {@link Stage#PIN_VERIFIED} und bei Gaesten leer. */
    @Column(name = "spieler_id")
    private Long spielerId;

    @Column(name = "gast_name", length = 40)
    private String gastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "gast_stufe", length = 10)
    private GastStufe gastStufe;

    /** Erst ab {@link Stage#PROFILE_AUTHENTICATED} gesetzt, davor {@code null}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rolle", length = 10)
    private Rolle rolle;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 24)
    private Stage stage;

    @Column(name = "erstellt_am", nullable = false)
    private OffsetDateTime erstelltAm = OffsetDateTime.now();

    @Column(name = "letzte_aktivitaet_am", nullable = false)
    private OffsetDateTime letzteAktivitaetAm = OffsetDateTime.now();

    /** Gleitendes Leerlauf-Fenster; wird bei jedem Request nach hinten geschoben. */
    @Column(name = "gueltig_bis", nullable = false)
    private OffsetDateTime gueltigBis;

    /** Harte Obergrenze; wird nie verlaengert (Zwei-Timer-Modell, A14). */
    @Column(name = "absolut_gueltig_bis", nullable = false)
    private OffsetDateTime absolutGueltigBis;

    @Column(name = "widerrufen_am")
    private OffsetDateTime widerrufenAm;

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Long getSpielerId() {
        return spielerId;
    }

    public void setSpielerId(Long spielerId) {
        this.spielerId = spielerId;
    }

    public String getGastName() {
        return gastName;
    }

    public void setGastName(String gastName) {
        this.gastName = gastName;
    }

    public GastStufe getGastStufe() {
        return gastStufe;
    }

    public void setGastStufe(GastStufe gastStufe) {
        this.gastStufe = gastStufe;
    }

    public Rolle getRolle() {
        return rolle;
    }

    public void setRolle(Rolle rolle) {
        this.rolle = rolle;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public OffsetDateTime getErstelltAm() {
        return erstelltAm;
    }

    public void setErstelltAm(OffsetDateTime erstelltAm) {
        this.erstelltAm = erstelltAm;
    }

    public OffsetDateTime getLetzteAktivitaetAm() {
        return letzteAktivitaetAm;
    }

    public void setLetzteAktivitaetAm(OffsetDateTime letzteAktivitaetAm) {
        this.letzteAktivitaetAm = letzteAktivitaetAm;
    }

    public OffsetDateTime getGueltigBis() {
        return gueltigBis;
    }

    public void setGueltigBis(OffsetDateTime gueltigBis) {
        this.gueltigBis = gueltigBis;
    }

    public OffsetDateTime getAbsolutGueltigBis() {
        return absolutGueltigBis;
    }

    public void setAbsolutGueltigBis(OffsetDateTime absolutGueltigBis) {
        this.absolutGueltigBis = absolutGueltigBis;
    }

    public OffsetDateTime getWiderrufenAm() {
        return widerrufenAm;
    }

    public void setWiderrufenAm(OffsetDateTime widerrufenAm) {
        this.widerrufenAm = widerrufenAm;
    }
}
