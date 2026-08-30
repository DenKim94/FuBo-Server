package de.fubo.appserver.domain.spieltag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Ein einzelner Spieltermin ({@code spieltag.termin} aus {@code V005}, A18).
 *
 * <p><b>Kein {@code @ManyToOne} auf die Serie</b>, nur der Fremdschluesselwert
 * {@link #serieId}: Eine Assoziation laedt entweder unnoetig die ganze Serie oder erzwingt
 * Lazy-Loading ausserhalb der Transaktion, und {@code open-in-view=false} ist gesetzt.
 * Die Regel steht in {@code AGENT_SERVER.md}.
 *
 * <p><b>{@code datum} und {@code uhrzeit} tragen keine Zeitzone</b> - die Spalten sind
 * {@code DATE} und {@code TIME}. Sie bedeuten Ortszeit; welche das ist, legt
 * {@code fubo.zeitzone} fest (Vorgabe {@code Europe/Berlin}, siehe {@code ZeitConfig}).
 * Der Zeitpunkt ist ueber {@code uq_termin_zeit} <b>global</b> eindeutig: Es gibt keine
 * zwei Termine zur selben Zeit, auch nicht an verschiedenen Orten.
 *
 * <p><b>Zwei Zaehlerspalten, die leicht zu verwechseln sind:</b> {@link #version} ist das
 * Optimistic Locking der Zeile (A5), {@link #teilnehmerVersion} zaehlt die
 * Teilnehmeraenderungen (A15). Die erste steigt bei jedem Schreibvorgang durch Hibernate,
 * die zweite ausschliesslich dann, wenn sich der Teilnehmerkreis aendert.
 */
@Entity
@Table(name = "termin", schema = "spieltag")
public class Termin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Serie, aus der dieser Termin stammt; {@code null} bei einem Einzeltermin. */
    @Column(name = "serie_id")
    private Long serieId;

    /** Datum in Ortszeit; zusammen mit {@link #uhrzeit} global eindeutig. */
    @Column(name = "datum", nullable = false)
    private LocalDate datum;

    /** Uhrzeit in Ortszeit, Minutengenauigkeit genuegt. */
    @Column(name = "uhrzeit", nullable = false)
    private LocalTime uhrzeit;

    /**
     * Spielort; optional (A18).
     *
     * <p><b>160 Zeichen, nicht 120.</b> Das Datenmodell-Dokument nannte urspruenglich 120,
     * {@code V005} legt 160 an - Migrationen sind unveraenderlich, also gilt 160.
     * {@code AGENT.md} ist am 30.08.2026 nachgezogen worden.
     */
    @Column(name = "ort", length = 160)
    private String ort;

    /** Zustand des Termins; die Datenbank sichert die Werte per CHECK-Constraint ab. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TerminStatus status = TerminStatus.GEPLANT;

    /**
     * Zaehler aller Teilnehmeraenderungen dieses Termins (A15).
     *
     * <p>{@code INTEGER}, nicht {@code BIGINT} - anders als {@link #version}. In S4 wird er
     * nur gefuehrt; ab S5 ist er der <b>einzige</b> Ausloeser fuer das Zuruecksetzen des
     * Generierungskontingents und das Veraltet-Kennzeichen einer Teameinteilung.
     */
    @Column(name = "teilnehmer_version", nullable = false)
    private int teilnehmerVersion;

    /**
     * Ob die Teameinteilung eingefroren ist.
     *
     * <p><b>Bleibt in S4 unangetastet.</b> Die Spalte gehoert zum offenen Punkt "Einteilung
     * einfrieren" und bekommt erst mit S5 eine Bedeutung. Sie steht hier, weil
     * {@code ddl-auto=validate} nur die Existenz gemappter Spalten prueft - eine fehlende
     * Abbildung faellt nicht auf, ein spaeteres Nachziehen dagegen schon.
     */
    @Column(name = "teams_fixiert", nullable = false)
    private boolean teamsFixiert;

    /** Optimistic Locking (A5); als Wrapper-Typ, damit der ungespeicherte Zustand erkennbar bleibt. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Nur fuer JPA.
     *
     * <p><b>Es gibt bewusst keinen oeffentlichen Konstruktor.</b> Ein Termin entsteht
     * ausschliesslich ueber {@code TerminRepository#einfuegenWennFrei} - dort haengt die
     * {@code ON CONFLICT}-Klausel dran, die den Wettlauf um einen Zeitpunkt entscheidet.
     * Ein zweiter Weg ueber {@code save(new Termin(...))} umginge sie und braechte bei
     * gleichzeitigen Anlagen einen {@code 500} statt {@code 409 TERMIN_BELEGT}. Diese Entity
     * dient dem Lesen, dem Aendern und dem Absagen - also allem, wofuer das Optimistic
     * Locking ueber {@code @Version} gebraucht wird.
     */
    protected Termin() {
    }

    public Long getId() { return id; }

    public Long getSerieId() { return serieId; }

    public LocalDate getDatum() { return datum; }

    public void setDatum(LocalDate datum) { this.datum = datum; }

    public LocalTime getUhrzeit() { return uhrzeit; }

    public void setUhrzeit(LocalTime uhrzeit) { this.uhrzeit = uhrzeit; }

    public String getOrt() { return ort; }

    public void setOrt(String ort) { this.ort = ort; }

    public TerminStatus getStatus() { return status; }

    public void setStatus(TerminStatus status) { this.status = status; }

    public int getTeilnehmerVersion() { return teilnehmerVersion; }

    public boolean isTeamsFixiert() { return teamsFixiert; }

    public Long getVersion() { return version; }
}
