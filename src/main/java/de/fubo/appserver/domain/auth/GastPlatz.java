package de.fubo.appserver.domain.auth;

import java.time.OffsetDateTime;

/**
 * Ein Gastplatz samt Zustand - Ergebniszeile der Uebersichtsabfrage im
 * {@code GastSlotRepository} (Vorgabe des Haupt-Entwicklers vom 30.08.2026).
 *
 * <p><b>Der Anker ist der Platz, nicht die Sitzung.</b> Nur so werden verwaiste Plaetze
 * sichtbar: {@code belegt} ist wahr, {@code sitzungGueltig} falsch - der Platz ist besetzt,
 * die zugehoerige Sitzung aber abgelaufen oder widerrufen. Eine Abfrage ueber
 * {@code profil.session} zeigte genau diese Zeilen nicht, und sie sind der haeufigste Grund
 * dafuer, dass der naechste Gast keinen Platz bekommt.
 *
 * <p><b>Kein Wertobjekt fuer die Sitzung selbst.</b> Alles, was der Admin ueber den Gast
 * wissen muss, steht hier flach daneben; eine geschachtelte Struktur brauchte einen zweiten
 * Typ, der bei einem freien Platz durchgehend leer waere.
 *
 * <p>{@code wirksam} wird <b>nicht</b> hier gefuellt - es haengt an
 * {@code configs.app_config.anz_guests} und damit an einem Wert, den das Repository nicht
 * kennt. Der Dienst setzt es beim Bauen des DTOs.
 *
 * @param nummer         Platznummer ({@code gast_slot.id}), zugleich der Wert fuer die Freigabe
 * @param anzeigeName    Vorschlagsname aus dem Seed, etwa {@code Gast 3}
 * @param belegt         Zustand der Zeile
 * @param belegtSeit     Zeitpunkt der Belegung; {@code null} bei einem freien Platz
 * @param sessionId      belegende Sitzung; {@code null} bei einem freien Platz
 * @param gastName       Name des Gastes aus der Sitzung; {@code null} bei einem freien Platz
 * @param gastStufe      Selbsteinschaetzung des Gastes; {@code null} bei einem freien Platz
 * @param sitzungGueltig ob die Sitzung noch laeuft; {@code null} bei einem freien Platz
 */
public record GastPlatz(int nummer,
                        String anzeigeName,
                        boolean belegt,
                        OffsetDateTime belegtSeit,
                        Long sessionId,
                        String gastName,
                        GastStufe gastStufe,
                        Boolean sitzungGueltig) {

    /**
     * Ein belegter Platz, hinter dem niemand mehr sitzt.
     *
     * <p>Der Fall, den der Endpunkt sichtbar machen soll: Eine ablaufende Gastsitzung gibt
     * ihren Platz nicht von selbst frei - das erledigt erst der naechtliche Aufraeumlauf oder
     * ein ausdruecklicher Aufruf des Admins.
     */
    public boolean verwaist() {
        return belegt && !Boolean.TRUE.equals(sitzungGueltig);
    }
}
