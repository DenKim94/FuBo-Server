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
 * <p>Vollstaendiges Abbild von {@code configs.app_config} aus {@code V004}, {@code V009},
 * {@code V010}, {@code V013} und {@code V014}.
 * Gelesen wird in S2 nur das Zwei-Timer-Modell; die uebrigen Felder werden ab S3 (Admin-CRUD),
 * S4 (Teilnehmerzahlen), S5 (Teamgenerator samt {@code auswechselModus}), S7 (Hallenmodus)
 * und S8 (Push)
 * verwendet.
 *
 * <p>Die Wertebereiche sind in der Datenbank per CHECK-Constraint abgesichert
 * ({@code ck_app_config_teilnehmer}, {@code ck_app_config_guests},
 * {@code ck_app_config_algo}, {@code ck_app_config_auswechsel}, {@code ck_app_config_generator},
 * {@code ck_app_config_session}, {@code ck_app_config_vorlauf},
 * {@code ck_app_config_push_vorlauf}). Auf zusaetzliche
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

    /**
     * Wer bei ungerader Teilnehmerzahl als Auswechselspieler gilt (A20b,
     * Default {@link AuswechselModus#SCHWAECHSTER_UEBERZAHL}).
     *
     * <p>{@code length = 24} passt zu {@code VARCHAR(24)} aus {@code V009} und damit zum
     * laengsten Enum-Namen mit einem Zeichen Luft. Weicht die Laenge ab, bricht der Start
     * an {@code ddl-auto=validate} - das ist beabsichtigt.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "auswechsel_modus", nullable = false, length = 24)
    private AuswechselModus auswechselModus;

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

    /**
     * Hauptschalter des Hallenmodus (A23, {@code V013}); Vorgabe {@code false}.
     *
     * <p><b>Steht er aus, lehnt {@code /admin/halle/absagen} ab</b>
     * ({@code 409 HALLE_MODUS_INAKTIV}) - und zwar vor jeder anderen Pruefung. A23 verlangt,
     * dass der Hallenmodus <i>serverseitig</i> abschaltbar ist; ein Flag, das nur der Client
     * auswertet, waere keine Abschaltung, sondern ein ausgeblendeter Knopf.
     *
     * <p><b>Primitiver {@code boolean} und kein {@code Boolean}:</b> Die Spalte ist
     * {@code NOT NULL}, ein dritter Zustand existiert nicht. Beim Optimistic Locking ist das
     * anders - dort erkennt Hibernate am {@code null} der Wrapper-Version den ungespeicherten
     * Zustand.
     *
     * <p><b>Unabhaengig von {@link #halleEmail}</b> (Entscheidung vom 13.09.2026): Der Modus
     * laesst sich einschalten, bevor eine Adresse hinterlegt ist - das Formular blockiert
     * niemanden, und der fehlende Empfaenger faellt beim Absagen als
     * {@code 409 HALLE_NICHT_KONFIGURIERT} auf.
     */
    @Column(name = "hallen_modus_aktiv", nullable = false)
    private boolean hallenModusAktiv;

    // ---------------------------------------------------------------- Push (A25)

    /**
     * Hauptschalter des Push-Versands ({@code V014}, A25e); Vorgabe {@code true}.
     *
     * <p><b>Der Vorgabewert ist der umgekehrte wie bei {@link #hallenModusAktiv}, und das ist
     * kein Versehen.</b> Der Unterschied ist der Empfaenger: Die Hallenabsage geht an einen
     * Aussenstehenden, der nie zugestimmt hat - dort ist "aus, bis jemand es einschaltet" die
     * sichere Richtung. Eine Push-Nachricht erreicht ausschliesslich, wer im Browserdialog
     * zugestimmt hat, und {@code true} kann fuer sich genommen nichts ausloesen: ohne
     * Abonnement und ohne eingerichtete VAPID-Schluessel geht nichts hinaus. A25(e) verlangt
     * die Voreinstellung "eingeschaltet" ausdruecklich.
     *
     * <p>Er ist die <b>Anlagenebene</b> von drei Bedingungen, die zusammen gelten: Anlage
     * (dieses Feld, der Admin), Person ({@code profil.spieler.push_erwuenscht}, der Spieler)
     * und Geraet (mindestens ein aktives Abonnement). Faellt eine weg, unterbleibt der Versand
     * stillschweigend.
     *
     * <p><b>Die VAPID-Schluessel stehen bewusst nicht in dieser Tabelle</b>, sondern in
     * Umgebungsvariablen: Die Konfigurationszeile wird ueber einen Admin-Endpunkt gelesen und
     * geschrieben, ein privates Geheimnis haette darin nichts zu suchen.
     */
    @Column(name = "push_aktiv", nullable = false)
    private boolean pushAktiv;

    /**
     * Vorlauf in Stunden, mit dem an eine offene Rueckmeldung erinnert wird (A25); Vorgabe 24.
     *
     * <p>Der Wert gilt <b>anwendungsweit</b> und nicht je Termin - ein Feld am Termin waere ein
     * weiteres Pflichtfeld im Terminformular. Die Obergrenze von 168 Stunden steht am DTO,
     * nicht als CHECK: {@code ck_app_config_push_vorlauf} verlangt nur {@code > 0}, und eine
     * verletzte Bedingung braechte einen {@code 500} mit einem Constraint-Namen im Log statt
     * einer Meldung.
     */
    @Column(name = "push_erinnerung_stunden", nullable = false)
    private short pushErinnerungStunden;

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

    public AuswechselModus getAuswechselModus() {
        return auswechselModus;
    }

    public void setAuswechselModus(AuswechselModus auswechselModus) {
        this.auswechselModus = auswechselModus;
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

    public boolean isHallenModusAktiv() {
        return hallenModusAktiv;
    }

    public void setHallenModusAktiv(boolean hallenModusAktiv) {
        this.hallenModusAktiv = hallenModusAktiv;
    }

    public boolean isPushAktiv() {
        return pushAktiv;
    }

    public void setPushAktiv(boolean pushAktiv) {
        this.pushAktiv = pushAktiv;
    }

    public short getPushErinnerungStunden() {
        return pushErinnerungStunden;
    }

    public void setPushErinnerungStunden(short pushErinnerungStunden) {
        this.pushErinnerungStunden = pushErinnerungStunden;
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
