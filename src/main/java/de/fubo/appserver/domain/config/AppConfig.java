package de.fubo.appserver.domain.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * Admin-Konfiguration der Anwendung; genau eine Zeile mit {@code id = 1}
 * (Singleton, per CHECK-Constraint {@code ck_app_config_singleton} erzwungen,
 * Seed in {@code V007}).
 *
 * <p>Vollstaendiges Abbild von {@code configs.app_config} aus {@code V004}. Gelesen wird
 * in S2 nur das Zwei-Timer-Modell; die uebrigen Felder werden ab S3 (Admin-CRUD),
 * S4 (Teilnehmerzahlen), S5 (Teamgenerator) und S7 (Hallenmodus) verwendet.
 *
 * <p>Die Wertebereiche sind in der Datenbank per CHECK-Constraint abgesichert
 * ({@code ck_app_config_teilnehmer}, {@code ck_app_config_guests},
 * {@code ck_app_config_algo}, {@code ck_app_config_generator},
 * {@code ck_app_config_session}, {@code ck_app_config_vorlauf}). Auf zusaetzliche
 * Bean-Validation-Annotationen an der Entity wird bewusst verzichtet - die Regel stuende
 * dann an zwei Orten und koennte auseinanderlaufen. Die Eingabepruefung gehoert an die
 * API-Grenze, also an das DTO des Admin-Endpunkts (S3).
 */
@Entity
@Table(name = "app_config", schema = "configs")
public class AppConfig {

    /** Fester Wert 1; kein {@code @GeneratedValue}, die Zeile stammt aus dem Seed. */
    @Id
    @Column(name = "id")
    private Short id;

    // ---------------------------------------------------------------- Teilnehmer (A10/A11)

    /** Mindestteilnehmerzahl, unter der ein Termin nicht stattfindet (Default 6). */
    @Column(name = "min_teilnehmer", nullable = false)
    private short minTeilnehmer;

    /** Hoechstteilnehmerzahl; darueber greift die Warteschlange (Default 22). */
    @Column(name = "max_teilnehmer", nullable = false)
    private short maxTeilnehmer;

    /**
     * Obergrenze fuer gleichzeitige Gaeste (Default 4, A17). Spaltenname mischt Deutsch
     * und Englisch - so in {@code V004} angelegt, Migrationen sind unveraenderlich.
     */
    @Column(name = "anz_guests", nullable = false)
    private short anzGuests;

    // ---------------------------------------------------------------- Teamgenerator (A15)

    /** Gewaehltes Verfahren der Teamgenerierung (Default {@link AlgorithmType#EXHAUSTIV}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm_type", nullable = false, length = 20)
    private AlgorithmType algorithmType;

    /** Kontingent an Generierungslaeufen je Nutzer und Spieltag (Default 1). */
    @Column(name = "anz_team_generator", nullable = false)
    private short anzTeamGenerator;

    // ---------------------------------------------------------------- Sitzungen (A14)

    /** Gleitendes Leerlauf-Fenster in Minuten (Default 15). */
    @Column(name = "session_leerlauf_minuten", nullable = false)
    private short sessionLeerlaufMinuten;

    /** Harte Obergrenze der Sitzungsdauer in Stunden (Default 1). */
    @Column(name = "session_maximal_stunden", nullable = false)
    private short sessionMaximalStunden;

    // ---------------------------------------------------------------- Hallenmodus (A23)

    /** Empfaengeradresse des Hallenbetreibers fuer die Absage; optional. */
    @Column(name = "halle_email", length = 120)
    private String halleEmail;

    /**
     * Vordefinierter Absagetext; optional. Die Spalte ist {@code TEXT}. Die
     * Laengenangabe eines {@code String}-Feldes wird von {@code ddl-auto=validate}
     * nicht geprueft - der Validator vergleicht nur den JDBC-Typcode, und
     * {@code TEXT} meldet sich wie {@code VARCHAR}.
     */
    @Column(name = "halle_absage_vorlage")
    private String halleAbsageVorlage;

    /** Vorlauf in Stunden, bis zu dem eine Absage zulaessig ist (Default 48). */
    @Column(name = "halle_vorlauf_stunden", nullable = false)
    private short halleVorlaufStunden;

    // ---------------------------------------------------------------- Aenderungsverfolgung

    /**
     * Id des aendernden Admin-Kontos; optional, verweist auf {@code profil.admin_konto}.
     * Bewusst als schlichter Fremdschluesselwert und nicht als {@code @ManyToOne}: Eine
     * Assoziation laedt entweder unnoetig das ganze Konto oder erzwingt Lazy-Loading
     * ausserhalb der Transaktion - und {@code open-in-view=false} ist gesetzt.
     */
    @Column(name = "geaendert_von")
    private Short geaendertVon;

    @Column(name = "geaendert_am", nullable = false)
    private OffsetDateTime geaendertAm;

    /**
     * Optimistic Locking (A5). Verhindert, dass zwei parallele Admin-Aenderungen
     * einander ueberschreiben: Hibernate nimmt die Spalte in die WHERE-Klausel des
     * UPDATE auf und erhoeht sie; passt der Wert nicht mehr, schlaegt der Schreibvorgang
     * mit einer {@code OptimisticLockingFailureException} fehl.
     *
     * <p>Als Wrapper-Typ, damit Hibernate einen noch nicht gespeicherten Zustand am
     * {@code null}-Wert erkennen kann - bei manuell vergebenem Schluessel ist die
     * Version das einzige Unterscheidungsmerkmal.
     *
     * <p>Kein Setter: Der Wert wird ausschliesslich von Hibernate gepflegt.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ---------------------------------------------------------------- Zugriff

    public Short getId() {
        return id;
    }

    public short getMinTeilnehmer() {
        return minTeilnehmer;
    }

    public void setMinTeilnehmer(short minTeilnehmer) {
        this.minTeilnehmer = minTeilnehmer;
    }

    public short getMaxTeilnehmer() {
        return maxTeilnehmer;
    }

    public void setMaxTeilnehmer(short maxTeilnehmer) {
        this.maxTeilnehmer = maxTeilnehmer;
    }

    public short getAnzGuests() {
        return anzGuests;
    }

    public void setAnzGuests(short anzGuests) {
        this.anzGuests = anzGuests;
    }

    public AlgorithmType getAlgorithmType() {
        return algorithmType;
    }

    public void setAlgorithmType(AlgorithmType algorithmType) {
        this.algorithmType = algorithmType;
    }

    public short getAnzTeamGenerator() {
        return anzTeamGenerator;
    }

    public void setAnzTeamGenerator(short anzTeamGenerator) {
        this.anzTeamGenerator = anzTeamGenerator;
    }

    public short getSessionLeerlaufMinuten() {
        return sessionLeerlaufMinuten;
    }

    public void setSessionLeerlaufMinuten(short sessionLeerlaufMinuten) {
        this.sessionLeerlaufMinuten = sessionLeerlaufMinuten;
    }

    public short getSessionMaximalStunden() {
        return sessionMaximalStunden;
    }

    public void setSessionMaximalStunden(short sessionMaximalStunden) {
        this.sessionMaximalStunden = sessionMaximalStunden;
    }

    public String getHalleEmail() {
        return halleEmail;
    }

    public void setHalleEmail(String halleEmail) {
        this.halleEmail = halleEmail;
    }

    public String getHalleAbsageVorlage() {
        return halleAbsageVorlage;
    }

    public void setHalleAbsageVorlage(String halleAbsageVorlage) {
        this.halleAbsageVorlage = halleAbsageVorlage;
    }

    public short getHalleVorlaufStunden() {
        return halleVorlaufStunden;
    }

    public void setHalleVorlaufStunden(short halleVorlaufStunden) {
        this.halleVorlaufStunden = halleVorlaufStunden;
    }

    public Short getGeaendertVon() {
        return geaendertVon;
    }

    public void setGeaendertVon(Short geaendertVon) {
        this.geaendertVon = geaendertVon;
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
