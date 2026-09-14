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
 *
 * <p><b>Die Bilanz dagegen steht hier</b> (seit {@code V011}, A21). Der Unterschied zu den
 * Skillwerten ist kein technischer, sondern ein fachlicher: Skillwerte sind vertraulich und
 * duerfen die API-Grenze nur unterhalb von {@code /admin/} passieren; Siege und Niederlagen
 * sind das Gegenteil - sie entstehen aus Spielen, die alle Beteiligten miterlebt haben. Eine
 * eigene Tabelle braeuchte es dafuer nicht: Es sind drei Zahlen mit demselben Lebenszyklus
 * wie das Profil.
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

    // ------------------------------------------------------------------- Bilanz (A21)

    /**
     * Zahl der gewonnenen Spiele; wird ab S6 bei jeder Ergebnisaenderung neu berechnet.
     *
     * <p><b>Neu berechnet, nicht fortgeschrieben</b> (Entscheidung vom 30.08.2026). Ein
     * {@code +1} beim Eintragen und ein {@code -1} beim Korrigieren waeren kuerzer, setzten
     * aber voraus, dass die Teameinteilung dazwischen unveraendert bleibt. Tut sie es nicht,
     * traefe die Ruecknahme andere Spieler als der urspruengliche Eintrag - und der Fehler
     * faellt nie auf, weil es keine zweite Quelle gibt, gegen die sich der Zaehler pruefen
     * liesse. Die Neuberechnung aus {@code spieltag.ergebnis} und
     * {@code spieltag.team_zuteilung} macht die Korrektur zum Normalfall statt zum Sonderfall.
     *
     * <p><b>Ein Gast taucht hier nie auf.</b> Er hat keine Zeile in {@code profil.spieler};
     * in {@code spieltag.teilnahme} steht nur sein {@code gast_name}. Gastteilnahmen bleiben
     * damit ohne Bilanz - das Ergebnis des Termins ist davon unberuehrt.
     */
    @Column(name = "anz_siege", nullable = false)
    private int anzSiege;

    /** Zahl der verlorenen Spiele; Pflege wie bei {@link #anzSiege}. */
    @Column(name = "anz_niederlagen", nullable = false)
    private int anzNiederlagen;

    /**
     * Zahl der unentschiedenen Spiele; Pflege wie bei {@link #anzSiege}.
     *
     * <p>Das Unentschieden steht in {@code spieltag.ergebnis.sieger} als {@code 'U'} und ist
     * damit ein dritter Ausgang, kein fehlender Sieger. {@code deutlich} ist davon
     * unabhaengig: Ein deutlicher Sieg ist ein Sieg und zaehlt einmal - die Spalte beschreibt
     * die Hoehe, nicht den Ausgang.
     */
    @Column(name = "anz_unentschieden", nullable = false)
    private int anzUnentschieden;

    // ------------------------------------------------------ Benachrichtigungen (A25f)

    /**
     * Personenschalter fuer Push-Benachrichtigungen ({@code V014}, A25f); Vorgabe {@code true}.
     *
     * <p>Er ist die <b>mittlere</b> von drei Bedingungen, die zusammen gelten muessen: Anlage
     * ({@code configs.app_config.push_aktiv}, der Admin entscheidet), Person (dieses Feld, der
     * Spieler selbst) und Geraet (mindestens ein aktives Abonnement in {@code profil.push_abo}).
     * Faellt eine weg, unterbleibt der Versand stillschweigend - das ist kein Fehlerfall,
     * sondern der Normalzustand vieler Spieler.
     *
     * <p><b>Warum die Spalte gemappt wird und nicht nativ geschrieben:</b> Das ist die Lehre aus
     * S6. Hibernate schreibt beim Flush <b>alle</b> gemappten Spalten. Wuerde der Schalter per
     * SQL gesetzt, waehrend irgendwo eine {@code Spieler}-Entity geladen ist, schriebe deren
     * Flush den alten Wert still zurueck - dasselbe Bild wie bei den Bilanzzaehlern, nur ohne
     * den Testfall, der es damals aufgedeckt hat. Ueber die Entity greift {@link #version} von
     * selbst.
     *
     * <p><b>Preis, den man kennen muss:</b> Schaltet ein Spieler um, waehrend der Admin sein
     * Profil geoeffnet hat, bekommt der Admin beim Speichern {@code 409 DATEN_VERALTET}. Das ist
     * das gewuenschte Verhalten und kein Fehler.
     *
     * <p><b>Primitiver {@code boolean} und kein {@code Boolean}:</b> Die Spalte ist
     * {@code NOT NULL}, ein dritter Zustand existiert nicht - dieselbe Ueberlegung wie bei
     * {@code AppConfig#hallenModusAktiv}. Beim Optimistic Locking ist es umgekehrt: Dort erkennt
     * Hibernate am {@code null} der Wrapper-Version den ungespeicherten Zustand.
     *
     * <p><b>Das Adminprofil traegt den Wert ebenfalls</b> - es darf abonnieren, damit
     * {@code /admin/push/test} an seine eigenen Geraete versenden kann. Aus der
     * Empfaengerabfrage der Terminerinnerung faellt es trotzdem heraus, aber ueber
     * {@code rolle <> 'ADMIN'} und nicht ueber diesen Schalter.
     */
    @Column(name = "push_erwuenscht", nullable = false)
    private boolean pushErwuenscht;

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

    public int getAnzSiege() {
        return anzSiege;
    }

    public void setAnzSiege(int anzSiege) {
        this.anzSiege = anzSiege;
    }

    public int getAnzNiederlagen() {
        return anzNiederlagen;
    }

    public void setAnzNiederlagen(int anzNiederlagen) {
        this.anzNiederlagen = anzNiederlagen;
    }

    public int getAnzUnentschieden() {
        return anzUnentschieden;
    }

    public void setAnzUnentschieden(int anzUnentschieden) {
        this.anzUnentschieden = anzUnentschieden;
    }

    public boolean isPushErwuenscht() {
        return pushErwuenscht;
    }

    public void setPushErwuenscht(boolean pushErwuenscht) {
        this.pushErwuenscht = pushErwuenscht;
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
