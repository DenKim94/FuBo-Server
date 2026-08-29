package de.fubo.appserver.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/auth/admin/anmelden} (A22).
 *
 * <p>Die zweite Login-Stufe fuer den Adminzugang. Er ist die Alternative zur Namensauswahl:
 * Das Adminprofil ist ein technisches Konto und steht in der Namensliste nicht zur Verfuegung.
 *
 * <h2>Warum seit dem 29.08.2026 auch der Anmeldename uebertragen wird</h2>
 * Eine fruehere Fassung uebertrug ausschliesslich das Passwort mit der Begruendung, es gebe
 * genau einen Admin ({@code ck_admin_konto_singleton}) und eine Kennung waere deshalb ein
 * Feld ohne Auswahl. Das Argument stimmt fuer die <i>Auswahl</i>, nicht fuer die
 * <i>Absicherung</i>: Ohne Kennung reicht ein einziges erratenes Geheimnis, und der
 * Anmeldename ist nirgends abrufbar - das Adminprofil steht nicht in der Namensliste
 * ({@code GET /auth/users/lesen} filtert {@code rolle &lt;&gt; 'ADMIN'}). Wer die zentrale
 * PIN kennt, muss jetzt zwei Angaben treffen statt einer.
 *
 * <p><b>Der Anmeldename ist der Profilname des Adminprofils</b> ({@code profil.spieler.name}
 * zu {@code admin_konto.spieler_id}), gesetzt ueber {@code ADMIN_NAME} beim Start-Bootstrap.
 * Eine eigene Spalte im Admin-Konto waere ein zweiter Name fuer dasselbe Konto; die
 * {@code .env.example} beschreibt {@code ADMIN_NAME} ohnehin schon als Kontonamen, der kein
 * Spielername sein muss.
 *
 * <p><b>Er wird zeichengenau geprueft, einschliesslich Gross- und Kleinschreibung.</b> Die
 * Schreibweise ist eindeutig vorgegeben: Der Bootstrap legt den Profilnamen genau so ab, wie
 * {@code ADMIN_NAME} ihn nennt, und bricht sonst den Start ab. Randleerzeichen werden
 * dagegen entfernt - sie sind unsichtbar und nie beabsichtigt, eine Schreibweise ist
 * sichtbar und kann es sein.
 *
 * <h2>Zu den Laengengrenzen</h2>
 * Beim Anmeldenamen sind es 60 Zeichen - die Breite von {@code profil.spieler.name}. Eine
 * grosszuegigere Grenze waere sinnlos: Ein laengerer Wert kann keinem Profil entsprechen.
 *
 * <p>Beim Passwort sind es 72 Zeichen. Das ist keine fachliche Vorgabe, sondern schuetzt vor
 * unnoetig teuren BCrypt-Berechnungen mit sehr langen Eingaben - dieselbe Ueberlegung wie bei
 * der zentralen PIN. Eine <b>Mindest</b>laenge steht bewusst nicht hier: Sie waere eine
 * Auskunft ueber das Format des echten Passworts.
 *
 * @param anmeldename Klartext des Anmeldenamens (Profilname des Adminprofils)
 * @param passwort    Klartext des Admin-Passworts
 */
public record AdminLoginRequest(
        @NotBlank(message = "Der Anmeldename darf nicht leer sein.")
        @Size(max = 60, message = "Der Anmeldename ist zu lang.")
        String anmeldename,

        @NotBlank(message = "Das Passwort darf nicht leer sein.")
        @Size(max = 72, message = "Das Passwort ist zu lang.")
        String passwort) {

    /**
     * Entfernt Randleerzeichen aus dem Anmeldenamen - <b>nur</b> diese, die Schreibweise
     * bleibt unangetastet.
     *
     * <p>Die Ableitung steht im Record und nicht im Service: Sie gehoert zur Auslegung des
     * Anfragekoerpers und damit an die API-Grenze - dieselbe Regel wie bei
     * {@link GastAnmeldungRequest#bereinigterName()}.
     *
     * <p>Ein mitkopiertes Leerzeichen aus der Zwischenablage soll die Anmeldung nicht
     * scheitern lassen; der Bootstrap legt den Namen ebenfalls getrimmt ab, die beiden
     * Seiten bleiben also deckungsgleich.
     */
    public String bereinigterAnmeldename() {
        return anmeldename == null ? null : anmeldename.trim();
    }
}
