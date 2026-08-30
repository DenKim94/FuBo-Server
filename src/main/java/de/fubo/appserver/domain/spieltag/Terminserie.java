package de.fubo.appserver.domain.spieltag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Erzeugungsregel einer befristeten Terminserie ({@code spieltag.terminserie} aus
 * {@code V005}, A18).
 *
 * <p><b>Die Serie ist eine Regel, kein lebender Datensatz.</b> Ihre Termine entstehen beim
 * Anlegen als echte Zeilen (S4, Abschnitt 4.2); eine spaetere Aenderung an der Serie wirkt
 * deshalb nicht auf sie zurueck. Genau deshalb gibt es keinen Endpunkt, der eine Serie
 * aendert - er weckte eine Erwartung, die er nicht einloesen koennte.
 *
 * <p><b>{@code enddatum > startdatum} ist strikt</b> ({@code ck_terminserie_zeitraum}):
 * Eine Serie mit nur einem Termin ist unmoeglich. Wer einen einzelnen Termin will, nimmt
 * {@code /admin/termin/anlegen}.
 *
 * <p><b>{@link #wochentag} zaehlt nach ISO-8601</b> (Montag = 1), abgesichert durch
 * {@code ck_terminserie_wochentag}. {@code java.time.DayOfWeek} zaehlt genauso - hier ist
 * ausnahmsweise nichts umzurechnen.
 */
@Entity
@Table(name = "terminserie", schema = "spieltag")
public class Terminserie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Bezeichnung der Serie; Pflichtfeld, die Spalte ist {@code NOT NULL}. */
    @Column(name = "titel", nullable = false, length = 80)
    private String titel;

    /** Wochentag nach ISO-8601: 1 = Montag bis 7 = Sonntag. */
    @Column(name = "wochentag", nullable = false)
    private short wochentag;

    /** Uhrzeit aller erzeugten Termine, in Ortszeit. */
    @Column(name = "uhrzeit", nullable = false)
    private LocalTime uhrzeit;

    /** Beginn des Zeitraums; faellt er auf den Wochentag, gehoert er zur Serie. */
    @Column(name = "startdatum", nullable = false)
    private LocalDate startdatum;

    /** Ende des Zeitraums, einschliesslich; muss nach {@link #startdatum} liegen. */
    @Column(name = "enddatum", nullable = false)
    private LocalDate enddatum;

    /** Ort aller erzeugten Termine; optional. */
    @Column(name = "ort", length = 160)
    private String ort;

    /**
     * Profil-Id des anlegenden Admins.
     *
     * <p>Die Spalte ist {@code NULL}-faehig und verweist ohne {@code ON DELETE} auf
     * {@code profil.spieler}. Sie ist zugleich einer der Gruende, aus denen sich ein Profil
     * nach der ersten Serie nicht mehr entfernen laesst
     * ({@code 409 PROFIL_IN_VERWENDUNG}) - {@code SpielerRepository#istReferenziert} prueft
     * diese Tabelle seit S2b mit.
     */
    @Column(name = "angelegt_von")
    private Long angelegtVon;

    /**
     * Zeitpunkt des Anlegens.
     *
     * <p>Die Spalte hat zwar {@code DEFAULT now()}, doch Hibernate schreibt sie beim
     * {@code INSERT} mit - ein nicht gesetzter Wert liefe deshalb in eine Verletzung der
     * {@code NOT NULL}-Bedingung, statt den Vorgabewert zu ziehen. Der Dienst setzt ihn
     * ueber die {@code Clock}-Bean.
     */
    @Column(name = "angelegt_am", nullable = false)
    private OffsetDateTime angelegtAm;

    /**
     * Optimistic Locking (A5).
     *
     * <p>Ohne fachlichen Nutzen, solange es keinen Endpunkt gibt, der eine Serie aendert -
     * die Spalte existiert aber, und {@code @Version} ist auf jeder schreibend genutzten
     * {@code version}-Spalte Pflicht.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Terminserie() {
        // JPA
    }

    /**
     * Legt eine Serie an.
     *
     * @param titel       Bezeichnung, hoechstens 80 Zeichen
     * @param wochentag   1 (Montag) bis 7 (Sonntag)
     * @param uhrzeit     Uhrzeit aller Termine
     * @param startdatum  Beginn des Zeitraums
     * @param enddatum    Ende des Zeitraums, muss nach dem Beginn liegen
     * @param ort         Ort oder {@code null}
     * @param angelegtVon Profil-Id des Admins
     * @param angelegtAm  Zeitpunkt aus der {@code Clock}-Bean
     */
    public Terminserie(String titel, short wochentag, LocalTime uhrzeit,
                       LocalDate startdatum, LocalDate enddatum, String ort,
                       Long angelegtVon, OffsetDateTime angelegtAm) {
        this.titel = titel;
        this.wochentag = wochentag;
        this.uhrzeit = uhrzeit;
        this.startdatum = startdatum;
        this.enddatum = enddatum;
        this.ort = ort;
        this.angelegtVon = angelegtVon;
        this.angelegtAm = angelegtAm;
    }

    public Long getId() { return id; }

    public String getTitel() { return titel; }

    public short getWochentag() { return wochentag; }

    public LocalTime getUhrzeit() { return uhrzeit; }

    public LocalDate getStartdatum() { return startdatum; }

    public LocalDate getEnddatum() { return enddatum; }

    public String getOrt() { return ort; }

    public Long getAngelegtVon() { return angelegtVon; }

    public OffsetDateTime getAngelegtAm() { return angelegtAm; }

    public Long getVersion() { return version; }
}
