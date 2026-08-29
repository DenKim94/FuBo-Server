package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.config.AlgorithmType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/config/aendern}
 * (A10, A11, A14, A15, A17, A23; S3 Abschnitt 5).
 *
 * <h2>Voll-Update, nicht feldweise</h2>
 * Der Koerper enthaelt <b>alle zehn</b> aenderbaren Felder; der Client laedt vorher
 * {@code /admin/config/lesen} und schickt das veraenderte Ganze zurueck. Weglassen ist keine
 * Angabe - anders als bei {@link SpielerBearbeitenRequest}, wo genau das der Vertrag ist.
 *
 * <p><b>Der Grund liegt bei den beiden {@code null}-faehigen Feldern.</b> Feldweise waere
 * {@code null} nicht von "nicht angegeben" zu unterscheiden: Der Admin koennte eine einmal
 * gesetzte Hallenadresse nie wieder entfernen, ohne dass der Vertrag eine Sonderregel erfindet -
 * ein Feld {@code halleEmailLoeschen} oder ein {@code JsonNullable}. Beim Voll-Update ist
 * {@code null} schlicht {@code null}. Dazu kommt: Es ist eine Zeile in einem Formular; es gibt
 * keinen Anwendungsfall, in dem der Client nur ein Feld kennt.
 *
 * <p>Bei den Profilen faellt die Entscheidung umgekehrt aus, und das ist kein Widerspruch: Dort
 * gibt es viele Zeilen, ein Formular je Zeile und mit den Skillwerten eine Teilmenge, die man
 * einzeln setzen will.
 *
 * <h2>Die Version ist Pflicht</h2>
 * Sie stammt aus der zuletzt gelesenen Konfiguration. Weicht sie vom gespeicherten Stand ab,
 * antwortet der Dienst {@code 409 DATEN_VERALTET} und schreibt nichts. Ohne diesen Wert wuerde
 * ein Voll-Update bedingungslos ueberschreiben - zwei geoeffnete Browser-Tabs genuegen, damit der
 * zuletzt gespeicherte die Aenderungen des anderen lautlos zuruecknimmt.
 *
 * <h2>Warum die Grenzen hier stehen und nicht an der Entity</h2>
 * Die Datenbank sichert die Wertebereiche per CHECK-Constraint ab und bleibt die letzte Instanz.
 * Sie ist aber nicht die Instanz, die dem Nutzer antwortet: Eine verletzte Bedingung endet in
 * einem {@code 500} mit einem Constraint-Namen im Log. Die Eingabepruefung gehoert deshalb an die
 * API-Grenze - dieselbe Aufteilung wie bei den Skillwerten in S2b.
 *
 * <p><b>Die Kurzform {@code short} ist Teil der Pruefung.</b> Die Spalten sind {@code SMALLINT};
 * eine Zahl jenseits von 32767 wird schon beim Lesen des Koerpers abgelehnt und endet in einem
 * {@code 400}, nicht in einer Datenbankausnahme.
 *
 * <p>Zwei Grenzen sind hier festgelegt und stehen in keiner Anforderung:
 * <ul>
 *   <li><b>{@code anzGuests} hoechstens 22</b> (Vorgabe des Haupt-Entwicklers, offener Punkt 6 der
 *       S3-Anleitung). {@code anz_guests} ist {@code SMALLINT} ohne oberen CHECK, und seit S3 legt
 *       eine Erhoehung Gastplaetze wirklich an: Ein Tippfehler - "40" statt "4" - erzeugte 40
 *       Zeilen, die niemand wieder loescht. Die 22 ist die Vorgabe von {@code max_teilnehmer};
 *       mehr Gaeste als Teilnehmer ergeben keinen Sinn.</li>
 *   <li><b>Die beiden Sitzungsfelder</b> (Leerlauf hoechstens 24 Stunden, Gesamtdauer hoechstens
 *       24 Stunden; Festlegung vom 29.08.2026). Sie sind sicherheitsrelevant: Ohne Obergrenze
 *       waere ein Leerlauf-Fenster von rund 20 Tagen eine gueltige Eingabe, und ein
 *       verrutschtes Komma im Formular haette dieselbe Wirkung wie ein abgeschaltetes
 *       Sitzungsende.</li>
 * </ul>
 * Eine feldeuebergreifende Regel - {@code maxTeilnehmer >= minTeilnehmer} - kann Bean Validation
 * nicht; sie liegt im Dienst.
 *
 * @param version                Stand, auf dem die Aenderung aufsetzt
 * @param minTeilnehmer          Mindestteilnehmerzahl (A10)
 * @param maxTeilnehmer          Hoechstteilnehmerzahl (A11); nicht unter {@code minTeilnehmer}
 * @param anzGuests              Obergrenze gleichzeitig angemeldeter Gaeste (A17)
 * @param algorithmType          Verfahren der Teamgenerierung (A15)
 * @param anzTeamGenerator       Kontingent an Generierungslaeufen je Nutzer und Spieltag (A15)
 * @param sessionLeerlaufMinuten gleitendes Leerlauf-Fenster in Minuten (A14)
 * @param sessionMaximalStunden  harte Obergrenze der Sitzungsdauer in Stunden (A14)
 * @param halleEmail             Empfaengeradresse des Hallenbetreibers oder {@code null} (A23)
 * @param halleAbsageVorlage     vordefinierter Absagetext oder {@code null} (A23)
 * @param halleVorlaufStunden    Vorlauf, bis zu dem eine Absage zulaessig ist (A23)
 */
public record KonfigurationAendernRequest(

        @NotNull(message = "Die Version fehlt. Sie stammt aus /admin/config/lesen.")
        Long version,

        @Min(value = 1, message = "Die Mindestteilnehmerzahl muss mindestens 1 betragen.")
        short minTeilnehmer,

        @Min(value = 1, message = "Die Maximalzahl muss mindestens 1 betragen.")
        short maxTeilnehmer,

        @Min(value = 0, message = "Die Zahl der Gastplätze darf nicht negativ sein.")
        @Max(value = 22, message = "Es sind höchstens 22 Gastplätze zulässig.")
        short anzGuests,

        @NotNull(message = "Das Verfahren der Teamgenerierung fehlt.")
        AlgorithmType algorithmType,

        @Min(value = 1, message = "Das Kontingent des Teamgenerators muss mindestens 1 betragen.")
        short anzTeamGenerator,

        @Min(value = 1, message = "Das Leerlauf-Fenster muss mindestens 1 Minute betragen.")
        @Max(value = 1440, message = "Das Leerlauf-Fenster darf höchstens 1440 Minuten (24 Stunden) betragen.")
        short sessionLeerlaufMinuten,

        @Min(value = 1, message = "Die Sitzungsdauer muss mindestens 1 Stunde betragen.")
        @Max(value = 24, message = "Die Sitzungsdauer darf höchstens 24 Stunden betragen.")
        short sessionMaximalStunden,

        @Email(message = "Die Hallen-Adresse ist keine gültige E-Mail-Adresse.")
        @Size(max = 120, message = "Die Hallen-Adresse darf höchstens 120 Zeichen lang sein.")
        String halleEmail,

        String halleAbsageVorlage,

        @Min(value = 0, message = "Der Vorlauf darf nicht negativ sein.")
        short halleVorlaufStunden) {

    /**
     * Die Hallenadresse ohne Randleerzeichen; {@code null}, wenn nichts uebrig bleibt.
     *
     * <p><b>Die leere Zeichenkette wird zu {@code null}</b>, nicht zu {@code ""}. Ein Formularfeld
     * liefert beim Leeren regelmaessig {@code ""}; in der Spalte stuende dann eine leere
     * Zeichenkette, die auf jede Pruefung "ist eine Adresse hinterlegt" mit "ja" antwortete und
     * beim Versand scheiterte. {@code @Email} laesst {@code ""} durch - fuer die Bean Validation
     * ist eine leere Eingabe keine falsche Adresse.
     */
    public String halleEmailBereinigt() {
        return leerAlsNull(halleEmail);
    }

    /**
     * Die Absagevorlage ohne Randleerzeichen; {@code null}, wenn nichts uebrig bleibt.
     *
     * <p>Dieselbe Ueberlegung wie bei der Adresse: Eine Vorlage aus Leerzeichen ist keine Vorlage.
     */
    public String halleAbsageVorlageBereinigt() {
        return leerAlsNull(halleAbsageVorlage);
    }

    /** Trimmt und macht aus einer leeren Eingabe ein fehlendes Feld. */
    private static String leerAlsNull(String eingabe) {
        if (eingabe == null) {
            return null;
        }
        String bereinigt = eingabe.trim();
        return bereinigt.isEmpty() ? null : bereinigt;
    }
}
