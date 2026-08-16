package de.fubo.appserver.service.audit;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.repository.audit.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Schreibt Eintraege in das Audit-Log und haelt die Aufbewahrungsfrist ein.
 *
 * <h2>Transaktionsverhalten (verbindlich, entschieden am 16.08.2026)</h2>
 * Alle Schreibmethoden laufen mit der voreingestellten Ausbreitung
 * {@link Propagation#REQUIRED} und schliessen sich damit einer laufenden Transaktion an.
 * <b>{@code REQUIRES_NEW} ist ausdruecklich untersagt.</b> Der Grund ist fachlich: Ein
 * Protokolleintrag soll eine <i>vollzogene</i> Aenderung belegen. Scheitert die Aenderung,
 * darf der Eintrag nicht stehen bleiben - sonst behauptet das Protokoll etwas, das nie
 * passiert ist, und das ist schlimmer als eine Luecke.
 *
 * <p>Beim PIN-Endpunkt greift die Regel nicht, weil dort keine umgebende Transaktion
 * existiert: Der Fehlversuch <i>ist</i> das Ereignis und wird sofort festgeschrieben.
 *
 * <h2>Warum Ausnahmen nicht verschluckt werden</h2>
 * Eine fruehere Fassung fing {@code RuntimeException} ab, damit ein Fehler im Protokoll
 * nicht die Fachlogik abbricht. Das ist innerhalb einer gemeinsamen Transaktion
 * <b>wirkungslos</b>: Schlaegt das {@code INSERT} fehl, ist die Transaktion bereits als
 * "rollback-only" markiert. Das Abfangen verhindert den Rollback nicht, sondern verschiebt
 * ihn nur bis zum Commit - der Aufrufer bekommt dann eine
 * {@code UnexpectedRollbackException} ohne erkennbare Ursache. Die Ausnahme wird deshalb
 * durchgereicht; sie benennt das Problem an der Stelle, an der es entsteht.
 */
@Service
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    /** Laenge der Spalte {@code akteur_bezeichnung}. */
    private static final int MAX_AKTEUR_LAENGE = 60;

    private final AuditLogRepository auditLogRepository;
    private final int aufbewahrungTage;

    public AuditService(AuditLogRepository auditLogRepository, FuboProperties eigenschaften) {
        this.auditLogRepository = auditLogRepository;
        this.aufbewahrungTage = eigenschaften.audit().aufbewahrungTage();
    }

    /**
     * Protokolliert einen Vorgang ohne Bezug zu einem Profil - etwa einen PIN-Fehlversuch,
     * bei dem nur die Adresse bekannt ist.
     *
     * @param akteurBezeichnung sprechende Bezeichnung des Handelnden (hier: die Client-IP)
     * @param aktion            Vorgang
     * @param details           zusaetzliche Angaben; darf {@code null} oder leer sein
     */
    @Transactional
    public void protokolliere(String akteurBezeichnung, AuditAktion aktion, Map<String, Object> details) {
        protokolliere(null, akteurBezeichnung, aktion, null, null, details);
    }

    /**
     * Vollstaendige Fassung mit Profilbezug und betroffener Entitaet.
     *
     * <p>Bewusst ohne {@code try/catch}: siehe Klassenkommentar.
     */
    @Transactional
    public void protokolliere(Long akteurSpielerId, String akteurBezeichnung, AuditAktion aktion,
                              String entitaet, Long entitaetId, Map<String, Object> details) {

        auditLogRepository.einfuegen(
                akteurSpielerId,
                kuerzen(akteurBezeichnung),
                aktion.name(),
                entitaet,
                entitaetId,
                alsJson(details));
    }

    /**
     * Entfernt Eintraege jenseits der Aufbewahrungsfrist (Vorgabe 90 Tage,
     * {@code fubo.audit.aufbewahrung-tage}).
     *
     * <p><b>Warum ueberhaupt geloescht wird:</b> Das Audit-Log enthaelt personenbezogene
     * Daten - bei einem PIN-Fehlversuch steht die Client-IP darin. Nach der DSGVO duerfen
     * solche Daten nur so lange gespeichert werden, wie sie fuer den Zweck erforderlich
     * sind; "Angriffe erkennen" rechtfertigt keine unbegrenzte Vorhaltung. Nebenbei bleibt
     * die Tabelle damit klein - sie ist die einzige, die sonst dauerhaft waechst.
     *
     * <p><b>Die Frist gilt einheitlich fuer alle Aktionen.</b> Eine Ergebniskorrektur aus
     * S6 ist damit nach 90 Tagen ebenfalls nicht mehr belegbar. Soll sicherheitsbezogenes
     * Protokoll kuerzer und fachliches laenger aufbewahrt werden, ist das eine Staffelung
     * je {@code aktion} - der Aufraeumjob waere dafuer die einzige Stelle, die sich aendert.
     *
     * <p>Der Lauf selbst wird nicht ins Audit-Log geschrieben; das waere zirkulaer. Er
     * protokolliert ueber die Anwendungsprotokollierung.
     */
    @Scheduled(cron = "0 45 3 * * *")
    @Transactional
    public void alteEintraegeEntfernen() {
        OffsetDateTime stichtag = OffsetDateTime.now().minusDays(aufbewahrungTage);
        int anzahl = auditLogRepository.loescheAelterAls(stichtag);

        LOG.info("Audit-Eintraege aelter als {} Tage entfernt: {}", aufbewahrungTage, anzahl);
    }

    /** Die Spalte ist {@code NOT NULL} und auf 60 Zeichen begrenzt. */
    private static String kuerzen(String bezeichnung) {
        if (bezeichnung == null || bezeichnung.isBlank()) {
            return "unbekannt";
        }
        return bezeichnung.length() <= MAX_AKTEUR_LAENGE
                ? bezeichnung
                : bezeichnung.substring(0, MAX_AKTEUR_LAENGE);
    }

    /**
     * Baut ein flaches JSON-Objekt.
     *
     * <p>Bewusst von Hand statt ueber einen Objekt-Mapper: Es geht um wenige, selbst
     * gesetzte Schluessel mit einfachen Werten. Eine Abhaengigkeit zur
     * Serialisierungsbibliothek in der Service-Schicht waere fuer diesen Zweck
     * unverhaeltnismaessig.
     */
    private static String alsJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("{");
        boolean erstes = true;
        for (Map.Entry<String, Object> eintrag : details.entrySet()) {
            if (!erstes) {
                json.append(',');
            }
            erstes = false;
            json.append('"').append(maskieren(eintrag.getKey())).append("\":");
            json.append(wert(eintrag.getValue()));
        }
        return json.append('}').toString();
    }

    /** Zahlen und Wahrheitswerte stehen ohne Anfuehrungszeichen, alles Uebrige als Text. */
    private static String wert(Object rohwert) {
        if (rohwert == null) {
            return "null";
        }
        if (rohwert instanceof Number || rohwert instanceof Boolean) {
            return rohwert.toString();
        }
        return '"' + maskieren(rohwert.toString()) + '"';
    }

    /** Maskiert die Zeichen, die in einem JSON-Text nicht unmaskiert vorkommen duerfen. */
    private static String maskieren(String text) {
        StringBuilder ergebnis = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char zeichen = text.charAt(i);
            switch (zeichen) {
                case '"' -> ergebnis.append("\\\"");
                case '\\' -> ergebnis.append("\\\\");
                case '\n' -> ergebnis.append("\\n");
                case '\r' -> ergebnis.append("\\r");
                case '\t' -> ergebnis.append("\\t");
                default -> {
                    if (zeichen < 0x20) {
                        ergebnis.append("\\u%04x".formatted((int) zeichen));
                    } else {
                        ergebnis.append(zeichen);
                    }
                }
            }
        }
        return ergebnis.toString();
    }
}
