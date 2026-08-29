package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/user/bearbeiten} (A13, S3 Abschnitt 3).
 *
 * <h2>Weglassen heisst "nicht aendern"</h2>
 * Der Endpunkt arbeitet feldweise: Was nicht im Anfragekoerper steht, bleibt unveraendert.
 * <table border="1">
 *   <caption>Auslegung der beiden optionalen Felder</caption>
 *   <tr><th>Eingabe</th><th>Wirkung</th></tr>
 *   <tr><td>{@code name} fehlt oder ist {@code null}</td><td>Name bleibt</td></tr>
 *   <tr><td>{@code name} ist leer oder nur Leerzeichen</td><td>{@code 400 EINGABE_UNGUELTIG}</td></tr>
 *   <tr><td>{@code skills} fehlt oder ist {@code null}</td><td>Skillwerte bleiben</td></tr>
 *   <tr><td>{@code skills} ist leer ({@code {}})</td><td>Skillwerte bleiben - <b>kein</b> Loeschen</td></tr>
 *   <tr><td>{@code skills} nennt eine Teilmenge</td><td>genau diese Kategorien werden gesetzt</td></tr>
 *   <tr><td>weder {@code name} noch {@code skills}</td><td>{@code 400} mit benennender Meldung</td></tr>
 * </table>
 *
 * <p><b>Die vierte Zeile ist die wichtige: Eine leere Karte loescht nichts.</b> Ein Loeschen
 * von Skillzeilen ist in dieser API ueberhaupt nicht vorgesehen - der Teamgenerator braucht
 * vollstaendige Werte, und eine Kategorie verschwindet nur, wenn sie in
 * {@code profil.skill_kategorie} abgeschaltet wird. Das ist Datenpflege, keine
 * Profilaenderung.
 *
 * <p>Die letzte Zeile verhindert einen Aufruf, der nichts tut, aber einen Audit-Eintrag
 * hinterliesse.
 *
 * <h2>Warum {@code name} kein {@code @NotBlank} traegt - anders als beim Anlegen</h2>
 * Beim Anlegen ist der Name Pflicht, hier ist er optional. Ein {@code @NotBlank} lehnte
 * bereits das Weglassen ab und machte die feldweise Auslegung unmoeglich. Der leere String
 * wird trotzdem abgelehnt, aber im Service: Er ist eine Eingabe, kein Weglassen.
 *
 * @param spielerId Id des zu aendernden Profils
 * @param name      neuer Anzeigename oder {@code null}
 * @param skills    zu setzende Werte je Kategorieschluessel oder {@code null}
 */
public record SpielerBearbeitenRequest(

        @NotNull(message = "Die Profil-Id fehlt.")
        Long spielerId,

        @Size(max = 60, message = "Der Name darf hoechstens 60 Zeichen lang sein.")
        String name,

        Map<String, Integer> skills) {

    /**
     * Der Name ohne Randleerzeichen; {@code null}, wenn kein Name angegeben wurde.
     *
     * <p><b>Ein angegebener, aber leerer Name bleibt leer</b> und wird nicht zu
     * {@code null} - sonst waere "Name loeschen" nicht von "Name weglassen" zu
     * unterscheiden, und der Aufruf liefe stillschweigend durch, statt {@code 400} zu
     * liefern.
     */
    public String nameGetrimmt() {
        return name == null ? null : name.trim();
    }
}
