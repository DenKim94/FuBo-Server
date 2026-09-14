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
import java.time.OffsetDateTime;

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
     * <p><b>Seit S5 in Gebrauch.</b> Gesetzt wird das Flag vom A18-Auftrag bei Terminbeginn,
     * zurueckgesetzt beim Verschieben in die Zukunft (10.2); wer danach generieren will,
     * bekommt {@code 409 TEAMS_FIXIERT}.
     *
     * <p><b>Ehrlich zu benennen: Es ist grossteils redundant.</b> Nach Terminbeginn nimmt A7
     * ohnehin keine Rueckmeldung mehr an, und {@code teilnehmerVersionErhoehenFuerSpieler}
     * fasst nur kuenftige Termine an - die Version kann nicht mehr steigen, die Einteilung
     * nicht mehr veralten. Was das Flag hinzufuegt, ist Ausdruecklichkeit: ein benennbarer
     * Fehlercode statt eines Scheiterns an einer impliziten Statuspruefung, und ein in der
     * Datenbank ablesbarer Zustand.
     */
    @Column(name = "teams_fixiert", nullable = false)
    private boolean teamsFixiert;

    /**
     * Zeitpunkt, zu dem die Erinnerung an offene Rueckmeldungen fuer diesen Termin versandt
     * wurde ({@code V014}, A25); {@code null} heisst "noch nicht erinnert".
     *
     * <p><b>Sie sichert den Einmalversand.</b> Der Erinnerungsauftrag markiert den Termin ueber
     * einen bedingten {@code UPDATE ... WHERE push_erinnerung_am IS NULL} <b>vor</b> dem
     * Versand; eine betroffene Zeile heisst "wir sind die Ersten". Die Reihenfolge ist bewusst
     * gewaehlt: Ein Absturz mitten im Versand kostet einzelne Nachrichten - markierte man erst
     * danach, bekaemen nach einem Neustart <b>alle</b> Empfaenger die Nachricht ein zweites Mal.
     * Dasselbe Muster wie bei {@code halle_abgesagt_am} aus {@code V012}.
     *
     * <p><b>Warum diese Spalte gemappt ist und {@code halle_abgesagt_am} nicht</b> - der
     * Unterschied hat genau einen Grund: Sie faellt beim Verschieben eines Termins zurueck
     * (Entscheidung vom 14.09.2026), unter derselben Bedingung wie {@link #teamsFixiert}, also
     * nur bei echter Aenderung von Datum oder Uhrzeit. Und {@code TerminService#aendern}
     * arbeitet dort mit der <b>geladenen</b> Entity; ein natives {@code UPDATE} daneben waere
     * die verbotene Kombination aus Versionsspalte und verwalteter Entity.
     *
     * <p><b>Der Erinnerungsauftrag schreibt sie trotzdem nativ</b> - er laeuft in einer eigenen
     * Transaktion, in der keine {@code Termin}-Entity geladen ist. Das ist kein Widerspruch,
     * sondern dieselbe Regel von der anderen Seite.
     *
     * <p><b>{@code OffsetDateTime} und nicht {@code LocalDateTime}:</b> Die Spalte ist
     * {@code TIMESTAMPTZ} und traegt ihre Zeitzone selbst - anders als {@link #datum} und
     * {@link #uhrzeit}. {@code LocalDateTime} verloere sie.
     */
    @Column(name = "push_erinnerung_am")
    private OffsetDateTime pushErinnerungAm;

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

    public void setTeamsFixiert(boolean teamsFixiert) { this.teamsFixiert = teamsFixiert; }

    public OffsetDateTime getPushErinnerungAm() { return pushErinnerungAm; }

    public void setPushErinnerungAm(OffsetDateTime pushErinnerungAm) {
        this.pushErinnerungAm = pushErinnerungAm;
    }

    public Long getVersion() { return version; }
}
