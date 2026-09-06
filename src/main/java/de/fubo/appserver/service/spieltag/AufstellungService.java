package de.fubo.appserver.service.spieltag;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.domain.profil.Profileintrag;
import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.spieltag.Gastauswahl;
import de.fubo.appserver.domain.spieltag.ManuelleAuswahl;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import de.fubo.appserver.domain.spieltag.Terminzustand;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.repository.profil.SkillKategorieRepository;
import de.fubo.appserver.repository.spieltag.AufstellungRepository;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.config.ConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ermittelt, <b>wer</b> eingeteilt wird - die Eingabe beider Teamverfahren (S5, Abschnitt 2).
 *
 * <h2>Der wichtigste Dienst des Meilensteins</h2>
 * Jeder Fehler hier verfaelscht beide Algorithmen gleichzeitig, und zwar unauffaellig: Die
 * Teams sehen aus wie Teams, nur stehen die falschen Leute darin. Deshalb wird hier abgelehnt
 * statt aufgefuellt und benannt statt gefiltert.
 *
 * <h2>Zwei Quellen, ein Ergebnis</h2>
 * <ul>
 *   <li>{@link #fuerTermin(Long)} - die Zusagen eines Termins (2.2)</li>
 *   <li>{@link #manuell(ManuelleAuswahl)} - die freie Auswahl des Admins (2.5, A24)</li>
 * </ul>
 * <b>Hier endet die Verzweigung.</b> Beide liefern eine {@link Aufstellung};
 * Zielfunktion und Verfahren erfahren die Herkunft nicht. A24 ist deshalb keine zweite
 * Teamgenerierung, sondern eine zweite Eingangstuer zu derselben.
 *
 * <h2>Abweichung von der Anleitung: {@link Aufstellung} statt {@code List<Aufstellungsspieler>}</h2>
 * 2.4 nennt die Liste als Rueckgabetyp. Die Verfahren brauchen aber zusaetzlich die aktiven
 * Skillkategorien - und die liest dieser Dienst ohnehin, um die Vollstaendigkeit der Werte zu
 * pruefen. Bliebe es bei der Liste, muesste der aufrufende Generierungsdienst sie ein zweites
 * Mal holen und das Wertobjekt selbst zusammensetzen; <b>zwei Aufrufer, zwei Gelegenheiten,
 * eine andere Kategorienmenge zu erwischen als die, gegen die geprueft wurde</b>. Fachlich ist
 * es dieselbe Auskunft, nur vollstaendig.
 *
 * <h2>Warum dieser Dienst in {@code service.spieltag} liegt und nicht in {@code service.team}</h2>
 * Er beantwortet eine Frage des Spieltags - wer hat zugesagt, wer ist gesperrt, wer wartet -
 * und liest dafuer Termin, Teilnahme und Konfiguration. Was mit der Aufstellung geschieht,
 * liegt in {@code service.team} und kennt weder Termin noch Sitzung noch Repository.
 */
@Service
public class AufstellungService {

    /**
     * Vorsilbe der Namensvorgabe fuer unbenannte Gaeste im manuellen Lauf.
     *
     * <p><b>Der Name ist kein Zierrat</b> (2.5): Der Admin liest die Teams vor und muss sagen
     * koennen, wer gemeint ist. "Gast" allein reichte nicht, sobald zwei davon auf dem Platz
     * stehen.
     */
    private static final String GAST_VORGABE = "Gast ";

    /**
     * Bauartbedingte Untergrenze: Aus einem Spieler lassen sich keine zwei Teams bilden.
     *
     * <p><b>Sie steht neben {@code min_teilnehmer} und nicht an dessen Stelle.</b>
     * {@code ck_app_config_teilnehmer} verlangt nur {@code min_teilnehmer > 0}, und das DTO
     * des Konfigurationsendpunkts laesst {@code 1} ausdruecklich zu. Ohne diese Grenze kaeme
     * eine Aufstellung mit einem einzigen Spieler durch - und scheiterte erst im Generator an
     * einer {@code IllegalArgumentException}, also mit einem {@code 500} statt einer Meldung.
     */
    private static final short MINDESTENS_ZWEI = 2;

    private final TerminRepository terminRepository;
    private final AufstellungRepository aufstellungRepository;
    private final SkillKategorieRepository skillKategorieRepository;
    private final ConfigService configService;

    public AufstellungService(TerminRepository terminRepository,
                              AufstellungRepository aufstellungRepository,
                              SkillKategorieRepository skillKategorieRepository,
                              ConfigService configService) {
        this.terminRepository = terminRepository;
        this.aufstellungRepository = aufstellungRepository;
        this.skillKategorieRepository = skillKategorieRepository;
        this.configService = configService;
    }

    // ------------------------------------------------------------------ Quelle 1: der Termin

    /**
     * Liefert die Aufstellung eines Termins: die ersten {@code max_teilnehmer} Zusagen in
     * Meldereihenfolge (2.2, 2.4).
     *
     * <h2>Drei Pruefungen, und die Reihenfolge ist nicht beliebig</h2>
     * <ol>
     *   <li>Der Termin existiert und ist {@link TerminStatus#GEPLANT}</li>
     *   <li>Die Zusagen erreichen {@code min_teilnehmer}</li>
     *   <li>Alle Skillwerte liegen vor</li>
     * </ol>
     * Ein abgesagter Termin mit zwei Zusagen soll {@code TERMIN_GESCHLOSSEN} melden, nicht
     * {@code ZU_WENIG_TEILNEHMER}: Der Nutzer soll den Grund erfahren, der zuerst greift und
     * den er nicht beheben kann.
     *
     * <h2>Was hier <i>nicht</i> geprueft wird</h2>
     * Der Terminbeginn und {@code teams_fixiert}. Beides gehoert zum Anstossen des Laufs
     * (9.3) und nicht zur Frage, wer aufgestellt ist - die Aufstellung eines begonnenen
     * Termins ist eine sinnvolle Auskunft, das Generieren darauf nicht.
     *
     * <p><b>Die Warteschlange bleibt draussen</b> (0.4, Punkt 2). Sie wird nicht in Java
     * abgeschnitten, sondern per {@code LIMIT} in derselben Abfrage, die auch sortiert -
     * Begruendung am {@code AufstellungRepository}.
     *
     * <p>Das Adminprofil und gesperrte Profile stehen nicht in der Liste; beide Ausschluesse
     * liegen in der Abfrage.
     *
     * @param terminId betroffener Termin
     * @return die Aufstellung: Teilnehmer in Meldereihenfolge, mindestens
     *         {@code min_teilnehmer} viele, samt der aktiven Kategorien
     * @throws FachlicherFehler {@code 404 INHALT_NICHT_GEFUNDEN}, wenn es den Termin nicht
     *                          gibt; {@code 409 TERMIN_GESCHLOSSEN}, wenn er nicht mehr
     *                          geplant ist; {@code 409 ZU_WENIG_TEILNEHMER};
     *                          {@code 409 SKILLWERTE_UNVOLLSTAENDIG}
     */
    @Transactional(readOnly = true)
    public Aufstellung fuerTermin(Long terminId) {
        // Nativ und nicht ueber findById: Der Generierungslauf liest die teilnehmer_version
        // aus demselben Zustand, und die Entity lieferte sie aus dem Persistence-Context -
        // also veraltet, sobald ein natives UPDATE sie zwischendurch erhoeht hat. Ausserdem
        // bleibt die Entity damit aus dem Vorgang heraus; dieselbe Regel wie beim
        // Rueckmeldepfad aus S4.
        Terminzustand zustand = terminRepository.zustand(terminId)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Es gibt keinen Termin mit dieser Id."));

        if (zustand.status() != TerminStatus.GEPLANT) {
            throw new FachlicherFehler(Fehlercode.TERMIN_GESCHLOSSEN,
                    "Für diesen Termin lassen sich keine Teams mehr generieren.");
        }

        AppConfig konfiguration = configService.lesen();
        List<Aufstellungsspieler> aufstellung =
                aufstellungRepository.fuerTermin(terminId, konfiguration.getMaxTeilnehmer());

        pruefeMindestzahl(aufstellung.size(), konfiguration.getMinTeilnehmer());
        return pruefenUndBauen(aufstellung);
    }

    // --------------------------------------------------- Quelle 2: die Auswahl des Admins

    /**
     * Liefert die Aufstellung aus der freien Auswahl des Admins (2.5, A24).
     *
     * <h2>Ablehnen statt filtern</h2>
     * Am Termin <i>ergibt</i> sich die Menge; hier hat der Admin jeden Einzelnen benannt. Wer
     * eine genannte Id kommentarlos herausnimmt, liefert Teams, die niemand angefordert hat -
     * und es faellt erst auf, wenn jemand vor Ort ohne Team dasteht. Aus demselben Grund
     * <b>begrenzt {@code max_teilnehmer} hier, es schneidet nicht ab</b>: Am Termin ist es die
     * Grenze zur Warteschlange, hier gibt es keine, in die jemand rutschen koennte.
     *
     * <h2>Die Reihenfolge der Pruefungen ist die aus 2.5</h2>
     * <ol>
     *   <li>leere Auswahl → {@code 400}</li>
     *   <li>doppelte Id oder doppelter Anzeigename → {@code 400}, nennt den Wert</li>
     *   <li>unbekannte oder gesperrte Id → {@code 400}, nennt die Ids</li>
     *   <li>Adminprofil → {@code 409 PROFIL_GESCHUETZT}</li>
     *   <li>Summe unter {@code min_teilnehmer} → {@code 409}</li>
     *   <li>Summe ueber {@code max_teilnehmer} → {@code 409}</li>
     *   <li>fehlender Skillwert → {@code 409}</li>
     * </ol>
     * <b>Die Namenspruefung ist geteilt</b>, und das ist der einzige Bruch mit der Tabelle:
     * Gastnamen untereinander lassen sich sofort vergleichen, ein Gastname gegen einen
     * Spielernamen erst, wenn die Profile geladen sind. Beide Faelle liefern denselben Code.
     *
     * <h2>Kein Kontingent, kein gespeicherter Lauf</h2>
     * Beides entschieden am 05.09.2026 (0.6). A15 zaehlt Laeufe je Termin und
     * Teilnehmerstand - beides fehlt hier.
     *
     * @param auswahl die benannten Profile und Gaeste
     * @return die Aufstellung in der Reihenfolge der Auswahl: erst die Spieler, dann die
     *         Gaeste, samt der aktiven Kategorien
     * @throws FachlicherFehler {@code 400 EINGABE_UNGUELTIG}, {@code 409 PROFIL_GESCHUETZT},
     *                          {@code 409 ZU_WENIG_TEILNEHMER},
     *                          {@code 409 ZU_VIELE_TEILNEHMER},
     *                          {@code 409 SKILLWERTE_UNVOLLSTAENDIG}
     */
    @Transactional(readOnly = true)
    public Aufstellung manuell(ManuelleAuswahl auswahl) {
        if (auswahl.anzahl() == 0) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Es wurde kein Teilnehmer ausgewählt.");
        }

        pruefeDoppelteIds(auswahl.spielerIds());
        List<String> gastNamen = gastNamenAufloesen(auswahl.gaeste());
        pruefeDoppelteNamen(gastNamen, "Gastname");

        List<Profileintrag> profile = profileLaden(auswahl.spielerIds());
        pruefeKeinAdminprofil(profile);
        pruefeNamensueberschneidung(profile, gastNamen);

        AppConfig konfiguration = configService.lesen();
        pruefeMindestzahl(auswahl.anzahl(), konfiguration.getMinTeilnehmer());
        pruefeHoechstzahl(auswahl.anzahl(), konfiguration.getMaxTeilnehmer());

        return pruefenUndBauen(zusammenstellen(auswahl, profile, gastNamen));
    }

    // ------------------------------------------------------------------ Hilfsmittel

    /**
     * Laedt die genannten Profile und lehnt jede Id ab, die nicht zurueckkam.
     *
     * <p>Unbekannt und gesperrt sind derselbe Fall: Die Abfrage filtert auf {@code s.aktiv},
     * beide fehlen also im Ergebnis. Sie zu unterscheiden hiesse, die Existenz eines
     * gesperrten Profils zu bestaetigen - fuer den Admin ohne Nutzen, denn beide Male ist die
     * Antwort dieselbe: Diese Id gehoert nicht in den Lauf.
     *
     * <p>Die Abfrage wird nur mit mindestens einer Id aufgerufen - eine leere Liste ergaebe
     * {@code IN ()} und damit einen Syntaxfehler.
     */
    private List<Profileintrag> profileLaden(List<Long> spielerIds) {
        if (spielerIds.isEmpty()) {
            return List.of();
        }
        List<Profileintrag> gefunden = aufstellungRepository.findeProfile(spielerIds);
        Set<Long> vorhanden = gefunden.stream()
                .map(Profileintrag::spielerId)
                .collect(Collectors.toSet());

        List<Long> fehlend = spielerIds.stream().filter(id -> !vorhanden.contains(id)).toList();
        if (!fehlend.isEmpty()) {
            throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                    "Diese Profile sind unbekannt oder gesperrt: " + fehlend + ".");
        }
        return gefunden;
    }

    /**
     * Setzt die Aufstellung zusammen: erst die Spieler in der Reihenfolge der Auswahl, dann
     * die Gaeste.
     *
     * <p><b>Die Reihenfolge ist keine Rangfolge</b> und geht in kein Ergebnis ein - beide
     * Verfahren rechnen auf Indizes, und die Zielfunktion ist gegen Vertauschungen
     * unempfindlich. Sie ist trotzdem festgelegt, damit derselbe Aufruf mit demselben Seed
     * dasselbe Ergebnis liefert; eine unbestimmte Reihenfolge machte einen Lauf
     * unnachrechenbar.
     *
     * <p>{@code teilnahmeId} und {@code gemeldetAm} bleiben {@code null}: Es gibt keine
     * Teilnahmezeile, und niemand hat sich gemeldet. Beides ist im Record vermerkt; die
     * Folge fuer den Auswechselspieler steht in 8.4.
     */
    private List<Aufstellungsspieler> zusammenstellen(ManuelleAuswahl auswahl,
                                                      List<Profileintrag> profile,
                                                      List<String> gastNamen) {
        Map<Long, Profileintrag> nachId = profile.stream()
                .collect(Collectors.toMap(Profileintrag::spielerId, Function.identity()));

        List<Aufstellungsspieler> aufstellung = new ArrayList<>(auswahl.anzahl());
        for (Long spielerId : auswahl.spielerIds()) {
            Profileintrag profil = nachId.get(spielerId);
            aufstellung.add(new Aufstellungsspieler(
                    null, profil.spielerId(), profil.name(), false, null, profil.skills()));
        }

        Map<GastStufe, Map<String, Integer>> vorlagen = auswahl.gaeste().isEmpty()
                ? Map.of()
                : aufstellungRepository.findeGastVorlagen();

        for (int i = 0; i < auswahl.gaeste().size(); i++) {
            GastStufe stufe = auswahl.gaeste().get(i).stufe();
            aufstellung.add(new Aufstellungsspieler(
                    null, null, gastNamen.get(i), true, null,
                    vorlagen.getOrDefault(stufe, Map.of())));
        }
        return aufstellung;
    }

    /**
     * Vergibt {@code Gast 1}, {@code Gast 2}, ... fuer Gaeste ohne Namen.
     *
     * <h2>Warum die Vorgabe hier steht und nicht im DTO</h2>
     * Vorgabewerte gehoeren sonst an die API-Grenze. Diese eine haengt aber an der
     * anschliessenden Pruefung: Nennt der Admin einen Gast ausdruecklich "Gast 2" und laesst
     * einen zweiten unbenannt, entsteht die Dopplung erst durch die Vorgabe - und sie soll
     * abgelehnt werden. Vorgabe und Dopplungspruefung sind eine Sache und stehen deshalb
     * nebeneinander. <b>Das Entfernen von Randleerzeichen bleibt Aufgabe des DTOs</b> (9.4).
     *
     * <p>Gezaehlt wird die <b>Position in der Liste</b>, nicht die Zahl der unbenannten
     * Gaeste: Der zweite Gast heisst {@code Gast 2}, auch wenn der erste einen Namen trug.
     * Das ist die Regel, die sich ohne Blick auf die anderen Eintraege vorhersagen laesst.
     *
     * <p>Ein leerer Name zaehlt wie ein fehlender - er stuende sonst als Leerstelle im Team.
     */
    private static List<String> gastNamenAufloesen(List<Gastauswahl> gaeste) {
        List<String> namen = new ArrayList<>(gaeste.size());
        for (int i = 0; i < gaeste.size(); i++) {
            String name = gaeste.get(i).name();
            namen.add(name == null || name.isBlank() ? GAST_VORGABE + (i + 1) : name);
        }
        return namen;
    }

    /** Lehnt eine doppelt genannte Profil-Id ab und nennt sie. */
    private static void pruefeDoppelteIds(List<Long> spielerIds) {
        Set<Long> gesehen = new LinkedHashSet<>();
        for (Long id : spielerIds) {
            if (!gesehen.add(id)) {
                throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                        "Das Profil mit der Id " + id + " wurde mehrfach ausgewählt.");
            }
        }
    }

    /** Lehnt einen doppelt vergebenen Anzeigenamen ab und nennt ihn. */
    private static void pruefeDoppelteNamen(List<String> namen, String bezeichnung) {
        Set<String> gesehen = new LinkedHashSet<>();
        for (String name : namen) {
            if (!gesehen.add(name)) {
                throw new FachlicherFehler(Fehlercode.EINGABE_UNGUELTIG,
                        "Der " + bezeichnung + " \"" + name + "\" wurde mehrfach vergeben.");
            }
        }
    }

    /**
     * Lehnt einen Gastnamen ab, der bereits einem ausgewaehlten Profil gehoert.
     *
     * <p>Zwei gleiche Namen in der Teamausgabe sind genau die Verwechslung, die der Gastname
     * verhindern soll. Beim Gast-Login prueft {@code GastService} dasselbe gegen <i>alle</i>
     * Profile; hier genuegt der Vergleich mit den Ausgewaehlten - ein gleichnamiges Profil,
     * das gar nicht mitspielt, steht in keiner Liste.
     */
    private static void pruefeNamensueberschneidung(List<Profileintrag> profile,
                                                    List<String> gastNamen) {
        List<String> alle = new ArrayList<>(profile.stream().map(Profileintrag::name).toList());
        alle.addAll(gastNamen);
        pruefeDoppelteNamen(alle, "Anzeigename");
    }

    /**
     * Haelt das Adminprofil aus der Aufstellung heraus (A24, 2.5 Pruefung 4).
     *
     * <p><b>Die Wiederholung des Ausschlusses an einer neuen Grenze.</b>
     * {@code /admin/user/lesen} weist die Rolle aus, aber wer die Id kennt, kommt an der
     * Liste vorbei - und das Adminprofil traegt Skillwerte von 0. Ohne diese Pruefung stuende
     * ein technisches Konto in einem Team und zoege die Zielfunktion zu seinen Gunsten.
     */
    private static void pruefeKeinAdminprofil(List<Profileintrag> profile) {
        boolean adminDabei = profile.stream().anyMatch(p -> p.rolle() == Rolle.ADMIN);
        if (adminDabei) {
            throw new FachlicherFehler(Fehlercode.PROFIL_GESCHUETZT,
                    "Das Adminprofil nimmt an keiner Teamgenerierung teil. "
                            + "Wer selbst mitspielt, braucht ein eigenes Spielerprofil.");
        }
    }

    /**
     * Lehnt eine zu kleine Aufstellung ab.
     *
     * <p>{@code detail} nennt Ist und Soll - "zu wenige" allein zwingt den Nutzer, die
     * Teilnehmerliste zu zaehlen und die Konfiguration nachzuschlagen. Genannt wird dabei die
     * <b>wirksame</b> Untergrenze, nicht der Konfigurationswert: Steht {@code min_teilnehmer}
     * auf 1, gilt trotzdem {@link #MINDESTENS_ZWEI}, und eine Meldung "mindestens 1" waere
     * dann schlicht falsch.
     */
    private static void pruefeMindestzahl(int ist, short soll) {
        short wirksam = (short) Math.max(soll, MINDESTENS_ZWEI);
        if (ist < wirksam) {
            throw new FachlicherFehler(Fehlercode.ZU_WENIG_TEILNEHMER,
                    "Es sind " + ist + " Teilnehmer vorhanden, mindestens " + wirksam
                            + " sind nötig.");
        }
    }

    /** Nur im manuellen Lauf; am Termin ist die Grenze die Warteschlange. */
    private static void pruefeHoechstzahl(int ist, short soll) {
        if (ist > soll) {
            throw new FachlicherFehler(Fehlercode.ZU_VIELE_TEILNEHMER,
                    "Es wurden " + ist + " Teilnehmer ausgewählt, höchstens " + soll
                            + " sind zugelassen.");
        }
    }

    /**
     * Prueft die Skillwerte und setzt daraus das Wertobjekt zusammen.
     *
     * <p><b>Die Kategorien werden genau einmal gelesen</b> und sowohl fuer die Pruefung als
     * auch fuer die Aufstellung verwendet. Zwei Lesevorgaenge koennten - theoretisch -
     * verschiedene Mengen liefern, und dann waere gegen etwas anderes geprueft worden als
     * gerechnet wird.
     *
     * <p>Ist keine Kategorie aktiv, bricht {@link Aufstellung} mit einer
     * {@code IllegalArgumentException} ab. Das ist richtig so: Ohne Kategorie gibt es keine
     * Zielfunktion, und es gibt keinen Endpunkt, der diesen Zustand herbeifuehren koennte -
     * es waere ein Eingriff in die Datenbank und damit ein Betriebs-, kein Eingabefehler.
     */
    private Aufstellung pruefenUndBauen(List<Aufstellungsspieler> spieler) {
        List<SkillKategorie> kategorien = skillKategorieRepository.aktive();
        pruefeSkillwerte(spieler, kategorien);
        return new Aufstellung(spieler, kategorien);
    }

    /**
     * Lehnt die Aufstellung ab, wenn jemandem der Wert zu einer aktiven Kategorie fehlt
     * (2.3).
     *
     * <h2>Warum das hier steht und nicht in der Zielfunktion</h2>
     * Die Zielfunktion rechnet auf einer Matrix; eine Luecke waere dort eine {@code 0} und
     * damit eine Behauptung ueber einen Spieler, die niemand aufgestellt hat. Sie faellt
     * nicht auf, die Teams sind falsch ausbalanciert, und niemand kann nachsehen, warum.
     * <b>Deshalb wird abgelehnt und nicht aufgefuellt.</b>
     *
     * <p><b>Gemessen wird an den aktiven Kategorien, nie an einer festen Zahl</b> - sonst
     * liesse sich nach dem Abschalten einer Kategorie kein Lauf mehr starten.
     *
     * <p>Geprueft werden Spieler <i>und</i> Gaeste. Beim Gast darf die <i>Stufe</i> fehlen
     * (A17, ersetzt durch {@code MITTEL}); fehlt der Vorlage dagegen eine Kategorie, ist das
     * derselbe Datenfehler wie bei einem Profil.
     *
     * <p>Die Meldung nennt die Namen, nicht die Kategorien: Der Admin sucht das Profil, nicht
     * das Feld - und die Kategorien stuenden dann fuer jeden Betroffenen einzeln in der
     * Antwort.
     */
    private static void pruefeSkillwerte(List<Aufstellungsspieler> spieler,
                                         List<SkillKategorie> kategorien) {
        Set<String> aktive = kategorien.stream()
                .map(SkillKategorie::schluessel)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> unvollstaendig = spieler.stream()
                .filter(eintrag -> !eintrag.werte().keySet().containsAll(aktive))
                .map(Aufstellungsspieler::anzeigeName)
                .toList();

        if (!unvollstaendig.isEmpty()) {
            throw new FachlicherFehler(Fehlercode.SKILLWERTE_UNVOLLSTAENDIG,
                    "Für diese Teilnehmer fehlen Skillwerte: "
                            + String.join(", ", unvollstaendig) + ".");
        }
    }
}
