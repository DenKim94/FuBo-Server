package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.Profileintrag;

import java.util.Map;

/**
 * Ein Profil aus Sicht des Admins - <b>mit Skillwerten</b>. Antwortobjekt von
 * {@code GET /api/v1/admin/user/lesen} (S3, Abschnitt 2).
 *
 * <h2>Das erste Antwortobjekt dieser API mit Skillwerten</h2>
 * Bis S2b enthielt keines welche; {@code SpielerAngelegt} traegt bewusst nur Id und Name.
 * Damit wird A12 hier zum ersten Mal scharf: <b>Skillwerte gehen ausschliesslich an
 * {@code ROLE_ADMIN}.</b> Der Endpunkt liegt unterhalb von {@code /api/*&#47;admin/**}, und die
 * Filterchain verlangt dort die Rolle - der Schutz haengt also nicht an diesem Record, sondern
 * am Pfad. Dass dieser Record nur unter {@code /admin/} auftaucht, ist trotzdem eine Regel,
 * die beim Lesen auffallen soll: Er gehoert in kein Antwortobjekt fuer {@code USER} oder
 * {@code GAST}.
 *
 * <h2>Warum drei Dinge drinstehen, die die Namensliste nicht hat</h2>
 * <ul>
 *   <li><b>{@code aktiv}</b> - gesperrte Profile stehen in dieser Liste. Ohne sie koennte der
 *       Admin ein versehentlich gesperrtes Profil nicht wiederfinden, um es freizugeben;
 *       genau dafuer kennt {@code /admin/user/blockieren} beide Richtungen.</li>
 *   <li><b>{@code rolle}</b> - der Client muss das Adminprofil erkennen, um fuer diese eine
 *       Zeile die Schaltflaechen "sperren" und "entfernen" auszublenden. Der Server lehnt jede
 *       Aenderung daran ueber {@code bearbeiten} ab; eine Eingabe, die immer scheitert, ist
 *       eine schlechte Oberflaeche.</li>
 *   <li><b>{@code skills}</b> - ohne sie liesse sich kein Bearbeitungsformular fuellen.</li>
 * </ul>
 *
 * @param spielerId Id des Profils; Eingabewert von {@code /admin/user/bearbeiten}
 * @param name      Anzeigename
 * @param rolle     {@link Rolle#ADMIN} oder {@link Rolle#USER}
 * @param aktiv     {@code false} bedeutet gesperrt
 * @param belegt    {@code true}, wenn gerade eine gueltige Sitzung auf dieses Profil laeuft;
 *                  bei jedem Aufruf frisch ermittelt, auch wenn die uebrigen Felder aus dem
 *                  Zwischenspeicher stammen
 * @param skills    Wert je Kategorieschluessel; enthaelt nur aktive Kategorien, zu denen eine
 *                  Zeile existiert. Eine fehlende Kategorie ist ein ungepflegtes Profil -
 *                  das kommt bei Profilen aus einem Datenimport vor (offener Punkt 20 aus S2).
 *                  Der Client erkennt die Luecke, indem er die Karte gegen
 *                  {@code /admin/skills/lesen} haelt; ein eigenes Feld im Vertrag braucht es
 *                  dafuer nicht.
 */
public record SpielerDetails(Long spielerId, String name, Rolle rolle,
                             boolean aktiv, boolean belegt,
                             Map<String, Integer> skills) {

    /**
     * Fuehrt die zwischengespeicherten Stammdaten mit dem frisch ermittelten Belegtstatus
     * zusammen.
     *
     * <p>Die Abbildung steht hier und nicht im Service: Sie gehoert zum DTO, und der Service
     * soll nicht wissen muessen, wie der Vertrag aussieht.
     *
     * @param eintrag Stammdaten aus der Uebersichtsabfrage
     * @param belegt  aus der Sitzungsabfrage desselben Aufrufs
     */
    public static SpielerDetails von(Profileintrag eintrag, boolean belegt) {
        return new SpielerDetails(
                eintrag.spielerId(),
                eintrag.name(),
                eintrag.rolle(),
                eintrag.aktiv(),
                belegt,
                eintrag.skills());
    }
}
