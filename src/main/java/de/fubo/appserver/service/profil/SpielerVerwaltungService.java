package de.fubo.appserver.service.profil;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.profil.Spieler;
import de.fubo.appserver.dto.admin.SpielerAngelegt;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.repository.profil.SkillKategorieRepository;
import de.fubo.appserver.repository.profil.SpielerRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.auth.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Verwaltung von Spielerprofilen durch den Admin (A13, S2b Abschnitt 8): anlegen, entfernen,
 * sperren und wieder freigeben.
 *
 * <h2>Drei Wege, ein Profil loszuwerden - und wann welcher gilt</h2>
 * <table border="1">
 *   <caption>Abgrenzung von Entfernen und Blockieren</caption>
 *   <tr><th>Vorgang</th><th>Wirkung</th><th>Gedacht fuer</th></tr>
 *   <tr>
 *     <td>{@link #entfernen}</td>
 *     <td>Zeile weg, Skillwerte per {@code ON DELETE CASCADE} mit</td>
 *     <td>den frisch angelegten Fehlgriff, der noch nie benutzt wurde</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #blockieren} mit {@code true}</td>
 *     <td>{@code aktiv = false}, Sitzungen widerrufen, Name bleibt belegt</td>
 *     <td>alles Uebrige - wer einmal mitgespielt hat, hinterlaesst Belege</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #blockieren} mit {@code false}</td>
 *     <td>{@code aktiv = true}</td>
 *     <td>die Ruecknahme einer Sperre</td>
 *   </tr>
 * </table>
 *
 * <h2>Das Adminprofil ist in allen Faellen geschuetzt</h2>
 * Es zu entfernen scheiterte an {@code fk_admin_konto_spieler}, es zu sperren machte den
 * Adminbereich unerreichbar - in beiden Faellen spaerrte sich der Admin mit einem einzigen
 * Aufruf selbst aus. Geprueft wird ueber die Rolle, nicht ueber die Id: Der partielle
 * Unique-Index {@code uq_spieler_genau_ein_admin} laesst genau ein Profil mit
 * {@link Rolle#ADMIN} zu.
 */
@Service
public class SpielerVerwaltungService {

    /**
     * Vorgabestufe fuer Skillwerte, wenn der Admin keine angibt.
     *
     * <p><b>Warum {@code MITTEL} und nicht 0:</b> Ein Profil mit lauter Nullen bekaeme in der
     * Teamgenerierung (S5) ein Team ohne jede Staerke zugeteilt, ohne dass jemand den Grund
     * saehe. {@code MITTEL} ist derselbe Wert, mit dem ein Gast ohne Selbsteinschaetzung
     * eingeht - eine ehrliche Annahme statt einer stillen Verzerrung.
     */
    private static final GastStufe VORGABESTUFE = GastStufe.MITTEL;

    /** Bezeichnung der betroffenen Entitaet im Audit-Log. */
    private static final String ENTITAET = "spieler";

    private final SpielerRepository spielerRepository;
    private final SkillKategorieRepository skillKategorieRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final AuditService auditService;

    public SpielerVerwaltungService(SpielerRepository spielerRepository,
                                    SkillKategorieRepository skillKategorieRepository,
                                    SessionRepository sessionRepository,
                                    SessionService sessionService,
                                    AuditService auditService) {
        this.spielerRepository = spielerRepository;
        this.skillKategorieRepository = skillKategorieRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
    }

    /**
     * Legt ein Profil mit der Rolle {@link Rolle#USER} an.
     *
     * <p>Die Skillwerte entstehen in zwei Schritten: erst die Vorgaben der Stufe
     * {@link #VORGABESTUFE} fuer alle aktiven Kategorien, dann die ausdruecklich genannten
     * darueber. Damit ist eine <i>teilweise</i> Angabe moeglich, ohne dass der Aufrufer alle
     * Kategorien kennen muesste.
     *
     * @param name           bereits getrimmter Anzeigename
     * @param skills         Skillwerte je Kategorieschluessel; darf leer sein
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @return Id und uebernommener Name
     * @throws FachlicherFehler {@code 409 NAME_BELEGT} bei belegtem Namen,
     *                          {@code 400 EINGABE_UNGUELTIG} bei unbekannter Kategorie oder
     *                          einem Wert ausserhalb ihres Bereichs
     */
    @Transactional
    public SpielerAngelegt anlegen(String name, Map<String, Integer> skills,
                                   Long adminSpielerId, String clientIp) {

        // Zuerst pruefen, dann schreiben: Sonst bliebe bei einem ungueltigen Skillwert ein
        // Profil ohne Werte zurueck, und der naechste Versuch scheiterte am belegten Namen.
        if (spielerRepository.existsByNameIgnoreCase(name)) {
            throw new FachlicherFehler(Fehlercode.NAME_BELEGT);
        }
        Map<String, Integer> gepruefteSkills = pruefeSkills(skills);

        OffsetDateTime jetzt = OffsetDateTime.now();
        Spieler neu = new Spieler();
        neu.setName(name);
        neu.setRolle(Rolle.USER);
        neu.setAktiv(true);
        neu.setErstelltAm(jetzt);
        neu.setGeaendertAm(jetzt);

        // saveAndFlush, weil die folgenden Anweisungen natives SQL sind und den erzeugten
        // Schluessel brauchen.
        Spieler gespeichert = spielerRepository.saveAndFlush(neu);

        // Die Werte VOR den Schreibzugriffen festhalten: Die nativen Abfragen laufen mit
        // clearAutomatically und loesen die Entity anschliessend vom Persistence-Context.
        Long spielerId = gespeichert.getId();
        String uebernommenerName = gespeichert.getName();

        spielerRepository.vorgabewerteAnlegen(spielerId, VORGABESTUFE.name());
        gepruefteSkills.forEach((kategorie, wert) ->
                spielerRepository.skillwertSetzen(spielerId, kategorie, wert));

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.PROFIL_ANGELEGT,
                ENTITAET, spielerId,
                Map.of("name", uebernommenerName,
                        "skillsGesetzt", gepruefteSkills.size(),
                        "vorgabestufe", VORGABESTUFE.name()));

        return new SpielerAngelegt(spielerId, uebernommenerName);
    }

    /**
     * Entfernt ein Profil endgueltig.
     *
     * <p>Die Reihenfolge ist festgelegt: erst laden und pruefen, dann die Verwendung
     * feststellen, <b>danach</b> die Sitzungen abraeumen. Andernfalls waeren die Sitzungen
     * bereits geloescht, wenn der Vorgang an einem Beleg scheitert - der Nutzer waere
     * abgemeldet, obwohl sein Profil bestehen bleibt.
     *
     * @throws FachlicherFehler {@code 404} bei unbekannter Id, {@code 409 PROFIL_GESCHUETZT}
     *                          beim Adminprofil, {@code 409 PROFIL_IN_VERWENDUNG}, wenn noch
     *                          fachliche Daten daran haengen
     */
    @Transactional
    public void entfernen(Long spielerId, Long adminSpielerId, String clientIp) {
        Spieler profil = laden(spielerId);
        pruefeNichtAdminprofil(profil);

        String name = profil.getName();

        if (spielerRepository.istReferenziert(spielerId)) {
            throw new FachlicherFehler(Fehlercode.PROFIL_IN_VERWENDUNG);
        }

        sessionRepository.loescheFuerSpieler(spielerId);
        spielerRepository.deleteById(spielerId);
        spielerRepository.flush();

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.PROFIL_ENTFERNT,
                ENTITAET, spielerId, Map.of("name", name));
    }

    /**
     * Sperrt ein Profil oder gibt es wieder frei.
     *
     * <p><b>Beim Sperren werden die offenen Sitzungen widerrufen.</b> Ohne das bliebe der
     * Gesperrte bis zum Ablauf seiner Sitzung angemeldet - die Sperre wirkte mit
     * Verzoegerung und gerade im gewuenschten Moment gar nicht.
     *
     * <p>Beim Freigeben geschieht das Gegenteil ausdruecklich <i>nicht</i>: Eine widerrufene
     * Sitzung laesst sich nicht wiederbeleben, und der Nutzer meldet sich ohnehin neu an.
     *
     * <p>Der Aufruf ist wiederholbar - ein bereits gesperrtes Profil erneut zu sperren
     * aendert nichts.
     *
     * @param blockieren {@code true} sperrt, {@code false} gibt frei
     * @throws FachlicherFehler {@code 404} bei unbekannter Id, {@code 409 PROFIL_GESCHUETZT}
     *                          beim Adminprofil
     */
    @Transactional
    public void blockieren(Long spielerId, boolean blockieren, Long adminSpielerId, String clientIp) {
        Spieler profil = laden(spielerId);
        pruefeNichtAdminprofil(profil);

        String name = profil.getName();

        profil.setAktiv(!blockieren);
        profil.setGeaendertAm(OffsetDateTime.now());
        spielerRepository.save(profil);

        if (blockieren) {
            sessionService.widerrufenFuerSpieler(spielerId);
        }

        auditService.protokolliere(adminSpielerId, clientIp,
                blockieren ? AuditAktion.PROFIL_BLOCKIERT : AuditAktion.PROFIL_FREIGEGEBEN,
                ENTITAET, spielerId, Map.of("name", name));
    }

    /**
     * Prueft die angegebenen Skillwerte gegen {@code profil.skill_kategorie}.
     *
     * <p><b>Warum hier und nicht ueber Bean Validation:</b> Die zulaessigen Schluessel und
     * Wertebereiche stehen in der Datenbank und koennen sich aendern; eine Annotation
     * muesste sie fest verdrahten. Der Trigger {@code pruefe_skill_wertebereich} bleibt die
     * letzte Instanz - er allein braechte aber einen {@code 500} statt einer Meldung, die
     * die betroffene Kategorie nennt.
     *
     * <p>Schluessel werden getrimmt und in Grossbuchstaben verglichen: Die Kategorien heissen
     * in der Datenbank durchgaengig gross, und ein {@code 400} wegen Kleinschreibung waere
     * eine Schikane ohne Sicherheitsgewinn.
     *
     * @return die geprueften Werte mit normalisierten Schluesseln; leer, wenn nichts angegeben war
     */
    private Map<String, Integer> pruefeSkills(Map<String, Integer> eingabe) {
        if (eingabe == null || eingabe.isEmpty()) {
            return Map.of();
        }

        Map<String, SkillKategorie> bekannt = skillKategorieRepository.aktive().stream()
                .collect(Collectors.toMap(SkillKategorie::schluessel, Function.identity()));

        Map<String, Integer> geprueft = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> eintrag : eingabe.entrySet()) {
            String schluessel = eintrag.getKey() == null
                    ? "" : eintrag.getKey().trim().toUpperCase(Locale.ROOT);
            SkillKategorie kategorie = bekannt.get(schluessel);

            if (kategorie == null) {
                throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                        "Unbekannte Skillkategorie '%s'. Zulässig sind: %s."
                                .formatted(eintrag.getKey(), String.join(", ", bekannt.keySet())));
            }
            Integer wert = eintrag.getValue();
            if (wert == null) {
                throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                        "Für die Kategorie '%s' fehlt der Wert.".formatted(schluessel));
            }
            if (!kategorie.enthaelt(wert)) {
                throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                        "Der Wert %d liegt ausserhalb des Bereichs %d bis %d für die Kategorie '%s'."
                                .formatted(wert, kategorie.minWert(), kategorie.maxWert(), schluessel));
            }
            geprueft.put(schluessel, wert);
        }
        return geprueft;
    }

    /** Laedt ein Profil oder lehnt mit {@code 404} ab. */
    private Spieler laden(Long spielerId) {
        return spielerRepository.findById(spielerId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN));
    }

    /** Das Adminprofil ist ein technisches Konto und in jedem Fall geschuetzt. */
    private static void pruefeNichtAdminprofil(Spieler profil) {
        if (profil.getRolle() == Rolle.ADMIN) {
            throw new FachlicherFehler(Fehlercode.PROFIL_GESCHUETZT);
        }
    }
}
