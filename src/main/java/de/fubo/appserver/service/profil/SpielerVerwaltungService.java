package de.fubo.appserver.service.profil;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.Profileintrag;
import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.profil.Spieler;
import de.fubo.appserver.dto.admin.SpielerAngelegt;
import de.fubo.appserver.dto.admin.SpielerDetails;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.repository.profil.SkillKategorieRepository;
import de.fubo.appserver.repository.profil.SpielerRepository;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.auth.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Verwaltung von Spielerprofilen durch den Admin (A13): anlegen, entfernen, sperren und wieder
 * freigeben (S2b Abschnitt 8) sowie lesen und bearbeiten (S3 Abschnitte 2 und 3).
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
 * <h2>Das Adminprofil ist in allen schreibenden Faellen geschuetzt</h2>
 * Es zu entfernen scheiterte an {@code fk_admin_konto_spieler}, es zu sperren machte den
 * Adminbereich unerreichbar - in beiden Faellen spaerrte sich der Admin mit einem einzigen
 * Aufruf selbst aus. Seit S3 gilt dasselbe fuer {@link #bearbeiten}: Sein Name ist zugleich
 * der Anmeldename und wird ueber {@code /admin/name/aendern} geaendert
 * ({@link #adminProfilUmbenennen}), seine Skillwerte bleiben auf 0.
 *
 * <p>Geprueft wird ueber die Rolle, nicht ueber die Id: Der partielle Unique-Index
 * {@code uq_spieler_genau_ein_admin} laesst genau ein Profil mit {@link Rolle#ADMIN} zu.
 *
 * <p><b>Beim Lesen gilt das Gegenteil:</b> {@link #uebersicht()} enthaelt das Adminprofil.
 * Eine Profilverwaltung zaehlt keine Mitspieler auf, sondern den Datenbestand - ohne die
 * Zeile saehe der Admin 30 Profile, waehrend die Datenbank 31 enthaelt, und die Differenz
 * waere nirgends erklaert.
 *
 * <h2>Jede Aenderung verwirft den Zwischenspeicher</h2>
 * {@link ProfilStammdatenCache} haelt die Profilliste im Arbeitsspeicher. <b>Jeder</b>
 * schreibende Vorgang dieser Klasse ruft {@code verwerfen()} auf - anlegen, entfernen,
 * blockieren, bearbeiten, umbenennen. Wird eine Stelle vergessen, liefert die Uebersicht
 * unbegrenzt lange veraltete Daten: Es gibt keine Frist, die den Fehler von selbst heilte.
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
    private final ProfilStammdatenCache profilStammdatenCache;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final AuditService auditService;

    public SpielerVerwaltungService(SpielerRepository spielerRepository,
                                    SkillKategorieRepository skillKategorieRepository,
                                    ProfilStammdatenCache profilStammdatenCache,
                                    SessionRepository sessionRepository,
                                    SessionService sessionService,
                                    AuditService auditService) {
        this.spielerRepository = spielerRepository;
        this.skillKategorieRepository = skillKategorieRepository;
        this.profilStammdatenCache = profilStammdatenCache;
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

        profilStammdatenCache.verwerfen();

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
        profilStammdatenCache.verwerfen();

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

        // saveAndFlush statt save: Die Namensliste und die Pruefungen der uebrigen Endpunkte
        // lesen ueber nativen JDBC-Zugriff und sehen nur, was in der Datenbank steht. Beim
        // Sperren erzwingt zwar der anschliessende Sitzungswiderruf ein Flush - beim Freigeben
        // gibt es keinen, und die Aenderung bliebe bis zum Ende der Transaktion unsichtbar.
        spielerRepository.saveAndFlush(profil);

        if (blockieren) {
            sessionService.widerrufenFuerSpieler(spielerId);
        }
        profilStammdatenCache.verwerfen();

        auditService.protokolliere(adminSpielerId, clientIp,
                blockieren ? AuditAktion.PROFIL_BLOCKIERT : AuditAktion.PROFIL_FREIGEGEBEN,
                ENTITAET, spielerId, Map.of("name", name));
    }

    /**
     * Liefert alle Profile mit Skillwerten und Belegtstatus (S3, Abschnitt 2).
     *
     * <h2>Zwei Quellen, mit Absicht</h2>
     * Die Anleitung sieht <i>eine</i> Abfrage vor, die auch den Belegtstatus mitliefert. Hier
     * sind es zwei, und der Grund ist der Zwischenspeicher aus der Vorgabe vom 29.08.2026:
     * <ul>
     *   <li>Die <b>Stammdaten</b> - Name, Rolle, Sperrzustand, Skillwerte - kommen aus
     *       {@link ProfilStammdatenCache}. Sie aendern sich nur, wenn jemand ein Profil
     *       anfasst, und genau dann wird der Speicher verworfen.</li>
     *   <li>Der <b>Belegtstatus</b> kommt bei jedem Aufruf frisch aus der Sitzungstabelle. Er
     *       aendert sich mit jeder Anmeldung, jedem Ablauf und jedem Logout - also staendig
     *       und ohne Zutun des Admins.</li>
     * </ul>
     * Beides gemeinsam zu speichern hiesse, den Belegtstatus einfrieren zu lassen: Er wuerde
     * erst wieder stimmen, wenn jemand ein Profil aendert. Das widerspraeche genau der
     * Eigenschaft, wegen der er ueberhaupt abgeleitet und nicht gespeichert wird (A6) -
     * <i>er kann nicht veralten</i>. Der Preis ist eine zweite, sehr schmale Abfrage ueber
     * einen partiellen Index.
     *
     * <p><b>Kein {@code @Transactional} an dieser Methode.</b> Sie liest nichts selbst: Der
     * Zwischenspeicher bringt seine eigene Lesetransaktion mit, und die Sitzungsabfrage ist
     * ein einzelner Aufruf. Eine Transaktion hier hielte im Cache-Treffer eine Verbindung
     * offen, ohne sie zu benutzen.
     *
     * @return alle Profile, Spielerprofile zuerst, das technische Adminkonto zuletzt
     */
    public List<SpielerDetails> uebersicht() {
        List<Profileintrag> stammdaten = profilStammdatenCache.alle();
        Set<Long> belegte = spielerRepository.findeBelegteProfilIds();

        return stammdaten.stream()
                .map(eintrag -> SpielerDetails.von(eintrag, belegte.contains(eintrag.spielerId())))
                .toList();
    }

    /**
     * Aendert Name und/oder Skillwerte eines bestehenden Profils (S3, Abschnitt 3).
     *
     * <h2>Weglassen heisst "nicht aendern"</h2>
     * {@code name} und {@code skills} sind beide optional; was {@code null} ist, bleibt. Eine
     * <b>leere</b> Skillkarte loescht nichts - ein Loeschen von Skillzeilen ist in dieser API
     * nicht vorgesehen, weil der Teamgenerator vollstaendige Werte braucht. Ein Aufruf ohne
     * jede Angabe wird abgelehnt: Er taete nichts, hinterliesse aber einen Protokolleintrag.
     *
     * <p>Die Auslegung des Anfragekoerpers - Randleerzeichen, Vorgabewerte - steht im DTO,
     * nicht hier. Der Service bekommt fertige Werte und entscheidet nur noch fachlich.
     *
     * <h2>Das Adminprofil wird vollstaendig abgelehnt</h2>
     * {@code 409 PROFIL_GESCHUETZT}, wie bei {@link #entfernen} und {@link #blockieren}.
     * Sein Name ist seit dem 29.08.2026 zugleich der <b>Anmeldename</b> und damit ein
     * Anmeldemerkmal, keine Stammdatenpflege; er wird ueber {@code POST /admin/name/aendern}
     * geaendert. Seine Skillwerte stehen auf 0, weil es ein technisches Konto ist und nie in
     * ein Team eingeteilt wird - ein gepflegter Wert dort waere eine Behauptung ueber einen
     * Spieler, den es nicht gibt.
     *
     * <h2>Vormerkung fuer S4: eine Skillaenderung ist eine Teilnehmeraenderung (A15)</h2>
     * A15 nennt ausdruecklich "die Aenderung der Skill-Stufe eines Spielers" als
     * Teilnehmeraenderung. Aendert der Admin einen Skillwert, ist jede bereits erzeugte
     * Teameinteilung fuer einen kuenftigen Termin, an dem dieser Spieler zugesagt hat,
     * veraltet - und der einzige zulaessige Ausloeser dafuer ist das Hochzaehlen von
     * {@code spieltag.termin.teilnehmer_version}.
     *
     * <p><b>Das geschieht hier noch nicht.</b> {@code spieltag} hat in S3 weder Service noch
     * Entity; beides entsteht in S4. Der Aufruf ist dort als Pflichtpunkt aufzunehmen, nicht
     * als Erinnerung: Solange es keine Termine gibt, ist die Luecke folgenlos - sobald es sie
     * gibt, ist sie ein stiller Fehler.
     *
     * @param spielerId      Id des zu aendernden Profils
     * @param name           neuer Name oder {@code null}; bereits getrimmt
     * @param skills         zu setzende Werte oder {@code null}; auch eine Teilmenge
     * @param adminSpielerId Profil-Id des handelnden Admins, fuer das Protokoll
     * @param clientIp       Adresse des Aufrufers, fuer das Protokoll
     * @throws FachlicherFehler {@code 400 EINGABE_UNGUELTIG}, wenn nichts zu aendern war oder
     *                          ein Skillwert nicht passt; {@code 404}, wenn es die Id nicht
     *                          gibt; {@code 409 NAME_BELEGT} bei einem fremden Namen;
     *                          {@code 409 PROFIL_GESCHUETZT} beim Adminprofil
     */
    @Transactional
    public void bearbeiten(Long spielerId, String name, Map<String, Integer> skills,
                           Long adminSpielerId, String clientIp) {

        // Zuerst der leere Name, dann "nichts angegeben": Beides ist 400, aber die Meldungen
        // sagen Verschiedenes. Ein angegebener leerer Name ist eine Eingabe und kein
        // Weglassen - stillschweigend durchgelassen ueberschriebe er den Namen mit "".
        if (name != null && name.isBlank()) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Der Name darf nicht leer sein. Zum Beibehalten das Feld weglassen.");
        }

        boolean nameAendern = name != null;
        boolean skillsAendern = skills != null && !skills.isEmpty();

        if (!nameAendern && !skillsAendern) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Es wurde nichts zum Ändern angegeben: weder ein Name noch Skillwerte.");
        }

        Spieler profil = laden(spielerId);
        pruefeNichtAdminprofil(profil);

        // Zuerst pruefen, dann schreiben - wie beim Anlegen. Sonst bliebe bei einem
        // ungueltigen Skillwert ein bereits umbenanntes Profil zurueck.
        Map<String, Integer> gepruefteSkills = skillsAendern ? pruefeSkills(skills) : Map.of();
        String alterName = profil.getName();

        if (nameAendern) {
            pruefeNameFrei(name, profil);
            profil.setName(name);
        }
        profil.setGeaendertAm(OffsetDateTime.now());

        // saveAndFlush: Die Skillzeilen werden gleich per nativem SQL geschrieben, und die
        // Uebersicht liest ebenfalls ueber nativen JDBC-Zugriff. Ohne Flush bliebe der neue
        // Name bis zum Ende der Transaktion unsichtbar.
        spielerRepository.saveAndFlush(profil);

        gepruefteSkills.forEach((kategorie, wert) ->
                spielerRepository.skillwertSetzen(spielerId, kategorie, wert));

        profilStammdatenCache.verwerfen();

        // Die alten Skillwerte gehoeren nicht ins Protokoll: Der Eintrag beantwortet "wer hat
        // wann was geaendert", nicht "wie war es vorher". Eine vollstaendige Aenderungshistorie
        // waere eine eigene Entscheidung mit eigenem Datenmodell - und die Loeschfrist von
        // 90 Tagen machte sie ohnehin lueckenhaft.
        Map<String, Object> details = new LinkedHashMap<>();
        if (nameAendern) {
            details.put("nameAlt", alterName);
            details.put("nameNeu", name);
        }
        if (!gepruefteSkills.isEmpty()) {
            details.put("skills", gepruefteSkills);
        }

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.PROFIL_GEAENDERT,
                ENTITAET, spielerId, details);
    }

    /**
     * Benennt das Adminprofil um und aendert damit den Anmeldenamen (S3, Vorgabe des
     * Haupt-Entwicklers vom 29.08.2026).
     *
     * <p>Der Schreibzugriff liegt hier, weil dieser Dienst die Profiltabelle besitzt; der
     * Anwendungsfall - Protokoll, Reichweite der Wirkung - steht in
     * {@code ZugangsdatenService#adminNameAendern}. Dieselbe Aufteilung wie zwischen
     * {@code AdminService#passwortSetzen} und {@code ZugangsdatenService}.
     *
     * <p><b>Getrimmt wird bereits im DTO</b>, und das ist hier keine Kosmetik:
     * {@code /auth/admin/anmelden} trimmt seine Eingabe ebenfalls. Ein mit Randleerzeichen
     * gespeicherter Name liesse sich deshalb nie eingeben - der Admin sperrte sich mit der
     * eigenen Umbenennung aus, und der Passwort-Reset holt das Passwort zurueck, nie den
     * Namen.
     *
     * @param neuerName bereits getrimmter neuer Name
     * @return der bisherige Name, fuer den Protokolleintrag
     * @throws FachlicherFehler {@code 409 NAME_BELEGT}, wenn ein anderes Profil so heisst
     * @throws IllegalStateException wenn es kein Adminprofil gibt - ein Betriebsfehler, den
     *                               der Start-Bootstrap ausschliesst
     */
    @Transactional
    public String adminProfilUmbenennen(String neuerName) {
        Spieler admin = spielerRepository.findByRolle(Rolle.ADMIN)
                .orElseThrow(() -> new IllegalStateException(
                        "Es gibt kein Profil mit der Rolle ADMIN - der Start-Bootstrap ist nicht gelaufen."));

        String alterName = admin.getName();
        pruefeNameFrei(neuerName, admin);

        admin.setName(neuerName);
        admin.setGeaendertAm(OffsetDateTime.now());
        spielerRepository.saveAndFlush(admin);

        profilStammdatenCache.verwerfen();

        return alterName;
    }

    /**
     * Stellt sicher, dass kein <i>anderes</i> Profil diesen Namen traegt.
     *
     * <p><b>Der eigene Datensatz zaehlt nicht als Kollision.</b> Ohne diese Ausnahme
     * scheiterte jede Korrektur der Schreibweise ("pruefspieler a" nach "Pruefspieler A") am
     * eigenen Namen - {@code existsByNameIgnoreCase} traefe die Zeile, die gerade geaendert
     * wird.
     *
     * <p>Der Vergleich laeuft ohne Ruecksicht auf Gross- und Kleinschreibung, obwohl
     * {@code uq_spieler_name} schreibungsgenau ist: Zwei Profile "Pruefspieler A" und
     * "pruefspieler a" waeren in der Auswahlliste nicht auseinanderzuhalten. Dieselbe Regel
     * gilt schon beim Anlegen.
     */
    private void pruefeNameFrei(String name, Spieler profil) {
        if (!name.equalsIgnoreCase(profil.getName())
                && spielerRepository.existsByNameIgnoreCase(name)) {
            throw new FachlicherFehler(Fehlercode.NAME_BELEGT);
        }
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
