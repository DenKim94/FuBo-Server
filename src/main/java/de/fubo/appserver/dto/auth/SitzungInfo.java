package de.fubo.appserver.dto.auth;

import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;

import java.time.OffsetDateTime;

/**
 * Antwort von {@code GET /api/v1/auth/session/lesen} - der "Wer bin ich"-Endpunkt
 * (Abschnitt 10.5 der Umsetzungsanleitung).
 *
 * <p>Er stellt den Zustand nach jedem Seitenneuladen wieder her: Die React-Anwendung weiss
 * nach {@code F5} nichts mehr, und den Token kann sie nicht lesen - er liegt in einem
 * {@code HttpOnly}-Cookie und ist opak. Das ist der entscheidende Unterschied zum Muster
 * "JWT aus dem localStorage dekodieren": Die Auskunft kommt vom Server und stimmt deshalb.
 *
 * <p><b>Was bewusst nicht enthalten ist:</b> keine Skillwerte, keine interne
 * {@code spieler_id}, kein Token. Auch die Selbsteinschaetzung eines Gastes fehlt: Sie
 * muesste in jeder Sitzungspruefung mitgelesen werden - also bei jedem Request - und wird
 * fuer die Anzeige nicht gebraucht. Die Rolle dagegen darf das Frontend kennen; sie steuert
 * nur, was gerendert wird, die Autorisierung bleibt serverseitig (Abschnitt 10.6).
 *
 * @param stage             erreichte Login-Stufe; in {@link Stage#PIN_VERIFIED} steht noch
 *                          keine Identitaet fest
 * @param rolle             {@code null}, solange die Sitzung in {@link Stage#PIN_VERIFIED} ist
 * @param anzeigeName       Profilname, Gastname oder {@code null} in {@link Stage#PIN_VERIFIED};
 *                          das Anhaengen von "(Gast)" ist Sache des Frontends (Anforderung 8)
 * @param gueltigBis        Ende des gleitenden Leerlauf-Fensters; Grundlage des Countdowns
 *                          und der Schaltflaeche "Sitzung verlaengern" (Abschnitt 10.7)
 * @param absolutGueltigBis harte Obergrenze. Sie steht hier, obwohl der Entwurf in 10.5 nur
 *                          {@code gueltigBis} vorsah: Ohne sie boete das Frontend ein
 *                          "Verlaengern" an, das kurz vor der Obergrenze nichts mehr bewirkt.
 *                          Der Countdown kann so auf "Sitzung endet endgueltig um ..."
 *                          umschalten, statt eine Schaltflaeche ohne Wirkung zu zeigen.
 */
public record SitzungInfo(Stage stage,
                          Rolle rolle,
                          String anzeigeName,
                          OffsetDateTime gueltigBis,
                          OffsetDateTime absolutGueltigBis) {
}
