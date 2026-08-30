package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.auth.GastPlatz;
import de.fubo.appserver.domain.auth.GastStufe;

import java.time.OffsetDateTime;

/**
 * Antwortobjekt von {@code GET /api/v1/admin/gast/lesen} (A17, Vorgabe vom 30.08.2026).
 *
 * <p>Beantwortet die Betriebsfrage "warum bekommt der naechste Gast keinen Platz". Die haeufigste
 * Antwort ist ein belegter Platz ohne lebende Sitzung: {@code belegt} wahr, {@code sitzungGueltig}
 * falsch. Der Zustand entsteht regelmaessig, weil eine ablaufende Gastsitzung ihren Platz
 * <b>nicht</b> von selbst freigibt - das tut erst der naechtliche Aufraeumlauf oder
 * {@code /admin/gast/freigeben}.
 *
 * <p><b>Warum {@code sessionId} nicht mit hinausgeht</b>, obwohl das Wertobjekt sie traegt: Sie ist
 * ein interner Schluessel ohne Nutzen fuer den Aufrufer - fuer die Freigabe genuegt die
 * Platznummer. Dieselbe Ueberlegung wie bei {@code SitzungInfo}, das die Profil-Id ebenfalls
 * zurueckhaelt. Der Token taucht ohnehin nirgends auf; in der Datenbank steht nur sein Hash.
 *
 * <p><b>Keine Skillwerte.</b> Die Stufe eines Gastes ist seine Selbsteinschaetzung, keine
 * Bewertung aus {@code profil.spieler_skill} - A12 ist davon nicht beruehrt. Trotzdem liegt das
 * DTO in {@code dto/admin}: Der Endpunkt ist einer.
 *
 * @param nummer         Platznummer; zugleich der Wert fuer {@code slotIds} beim Freigeben
 * @param anzeigeName    Vorschlagsname des Platzes, nicht der Name des Gastes
 * @param wirksam        {@code false}, wenn die Nummer ueber der aktuellen {@code anzGuests} liegt
 * @param belegt         Zustand der Zeile
 * @param belegtSeit     Zeitpunkt der Belegung oder {@code null}
 * @param gastName       Name des angemeldeten Gastes oder {@code null}
 * @param gastStufe      Selbsteinschaetzung des Gastes oder {@code null}
 * @param sitzungGueltig ob die Sitzung noch laeuft; {@code null} bei einem freien Platz
 */
public record GastPlatzInfo(int nummer,
                            String anzeigeName,
                            boolean wirksam,
                            boolean belegt,
                            OffsetDateTime belegtSeit,
                            String gastName,
                            GastStufe gastStufe,
                            Boolean sitzungGueltig) {

    /**
     * Uebersetzt das Wertobjekt der Abfrage in das Antwortobjekt der API-Grenze.
     *
     * <p>Die Abbildung steht hier und nicht im Dienst - dieselbe Aufteilung wie bei
     * {@link SkillKategorieInfo#von} und {@link SpielerDetails#von}.
     *
     * @param platz      Ergebniszeile der Abfrage
     * @param maxGaeste  aktueller Wert von {@code configs.app_config.anz_guests}; er entscheidet
     *                   ueber {@code wirksam} und ist dem Repository nicht bekannt
     */
    public static GastPlatzInfo von(GastPlatz platz, int maxGaeste) {
        return new GastPlatzInfo(
                platz.nummer(),
                platz.anzeigeName(),
                platz.nummer() <= maxGaeste,
                platz.belegt(),
                platz.belegtSeit(),
                platz.gastName(),
                platz.gastStufe(),
                platz.sitzungGueltig());
    }
}
