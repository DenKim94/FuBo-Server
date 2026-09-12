package de.fubo.appserver.domain.spieltag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * Das Ergebnis eines Termins ({@code spieltag.ergebnis} aus {@code V006}, A21).
 *
 * <h2>Warum es eine Entity gibt, obwohl der Eintrag nativ laeuft</h2>
 * Dasselbe geteilte Muster wie bei {@code TerminRepository}, und aus denselben zwei Gruenden:
 * Der <b>Eintrag</b> braucht {@code ON CONFLICT ... DO NOTHING}, das JPA nicht kennt - er
 * laeuft deshalb ueber {@code JdbcClient}. Die <b>Korrektur</b> braucht {@link Version}, das
 * natives SQL nicht bekommt - sie laeuft ueber diese Entity.
 *
 * <h2>{@code sieger} ist ein {@link Character} und kein {@link String}</h2>
 * Die Spalte ist {@code CHAR(1)}. Hibernate bildet {@code String} standardmaessig auf
 * {@code VARCHAR} ab, PostgreSQL meldet {@code CHAR(1)} aber als {@code bpchar} mit Typcode
 * {@code CHAR} - {@code ddl-auto=validate} bricht den Kontextstart dann ab, und der Fehler
 * zeigt sich als Kaskade von {@code UnsatisfiedDependencyException}, bei der <b>nur die erste
 * Logzeile die Ursache nennt</b>. Die Regel steht in {@code AGENT_SERVER.md} im
 * JPA-Kapitel; {@code spieltag.team_zuteilung.team} ist die zweite {@code CHAR(1)}-Spalte und
 * bleibt ungemappt, weil sie ueber {@code JdbcClient} laeuft.
 *
 * <p>Nach aussen wird daraus {@link Sieger}; der Aufzaehlungstyp steht bewusst <b>nicht</b> an
 * dieser Spalte. {@code @Enumerated(STRING)} schriebe den Namen des Konstanten, was hier
 * zufaellig passte - aber nur, solange die Konstanten einbuchstabig heissen. Die Umsetzung
 * steht deshalb an einer benennbaren Stelle: {@link Sieger#kennung()} und
 * {@link Sieger#vonKennung(char)}.
 *
 * <h2>Der Name des Erfassers wird kopiert, nicht verwiesen</h2>
 * {@link #erfasstVonSpielerId} ist nullbar (ein Gast hat keine Profil-Id),
 * {@link #erfasstVonBezeichnung} ist es nicht. Dasselbe Paar wie bei
 * {@code team_generierung.erzeugt_von_*}: Ein spaeter entferntes Profil liesse die Auskunft
 * sonst leer, obwohl der Eintrag stattgefunden hat.
 *
 * <p><b>{@link #korrigiertAm} traegt keine Historie.</b> Jede Korrektur ueberschreibt den
 * Wert; wer wann was gedreht hat, steht im Audit-Log. Dieselbe Aufteilung wie ueberall: Die
 * Tabelle sagt, wie es <i>ist</i>, das Protokoll, wie es dazu kam.
 */
@Entity
@Table(name = "ergebnis", schema = "spieltag")
public class Ergebnis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Betroffener Termin.
     *
     * <p>Kein {@code @ManyToOne} auf {@link Termin}, nur der Fremdschluesselwert - die Regel
     * aus {@code AGENT_SERVER.md}. {@code uq_ergebnis_termin} macht die Spalte zugleich
     * eindeutig: <b>Je Termin gibt es hoechstens ein Ergebnis</b>, und das ist "der erste
     * Eintrag gilt" in der Datenbank und nicht im Dienst.
     */
    @Column(name = "termin_id", nullable = false)
    private Long terminId;

    /** {@code 'A'}, {@code 'B'} oder {@code 'U'}; abgesichert durch {@code ck_ergebnis_sieger}. */
    @Column(name = "sieger", nullable = false)
    private Character sieger;

    /**
     * Ob der Sieg deutlich ausfiel.
     *
     * <p><b>Fuer die Bilanz ohne Bedeutung</b> - das Feld beschreibt die Hoehe, nicht den
     * Ausgang. Bei einem Unentschieden ist es unzulaessig; geprueft wird das am DTO, weil es
     * zwei Felder desselben Anfragekoerpers verbindet.
     */
    @Column(name = "deutlich", nullable = false)
    private boolean deutlich;

    /** Profil-Id des Erfassers; {@code null}, wenn ein Gast eingetragen hat. */
    @Column(name = "erfasst_von_spieler_id")
    private Long erfasstVonSpielerId;

    /** Anzeigename zum Zeitpunkt der Erfassung; in der Datenbank gebildet und kopiert. */
    @Column(name = "erfasst_von_bezeichnung", nullable = false, length = 60)
    private String erfasstVonBezeichnung;

    /** Zeitpunkt des ersten Eintrags; von {@code DEFAULT now()} gesetzt. */
    @Column(name = "erfasst_am", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime erfasstAm;

    /** Zeitpunkt der letzten Korrektur; {@code null}, solange nicht korrigiert wurde. */
    @Column(name = "korrigiert_am")
    private OffsetDateTime korrigiertAm;

    /** Optimistic Locking der Korrektur (A5). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Ergebnis() {
        // Fuer JPA.
    }

    public Long getId() { return id; }

    public Long getTerminId() { return terminId; }

    /** @return Ausgang des Spiels, aus dem Zeichen der Spalte uebersetzt */
    public Sieger getSieger() { return Sieger.vonKennung(sieger); }

    /** Setzt den Ausgang; die Spalte bekommt das Zeichen, nicht den Namen der Konstanten. */
    public void setSieger(Sieger neuerSieger) { this.sieger = neuerSieger.kennung(); }

    public boolean isDeutlich() { return deutlich; }

    public void setDeutlich(boolean deutlich) { this.deutlich = deutlich; }

    public Long getErfasstVonSpielerId() { return erfasstVonSpielerId; }

    public String getErfasstVonBezeichnung() { return erfasstVonBezeichnung; }

    public OffsetDateTime getErfasstAm() { return erfasstAm; }

    public OffsetDateTime getKorrigiertAm() { return korrigiertAm; }

    public void setKorrigiertAm(OffsetDateTime korrigiertAm) { this.korrigiertAm = korrigiertAm; }

    public Long getVersion() { return version; }
}
