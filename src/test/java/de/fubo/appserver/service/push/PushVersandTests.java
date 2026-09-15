package de.fubo.appserver.service.push;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.spieltag.Erinnerungstermin;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import de.fubo.appserver.dto.spieltag.TerminAendernRequest;
import de.fubo.appserver.repository.spieltag.TerminRepository;
import de.fubo.appserver.service.spieltag.HallenService;
import de.fubo.appserver.service.spieltag.TerminService;
import de.fubo.appserver.support.MailErsatz;
import de.fubo.appserver.support.MailErsatzConfig;
import de.fubo.appserver.support.PushVersenderErsatz;
import de.fubo.appserver.support.PushVersenderErsatzConfig;
import de.fubo.appserver.utils.TokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft die beiden Versandanlaesse, die Empfaengerauswahl, die Buchung der Antworten und den
 * Aufraeumlauf (A25b, S8 Abschnitte 8 bis 10; Pruefpunkte 15 bis 23 aus Abschnitt 12.3).
 *
 * <h2>Diese Klasse traegt bewusst kein {@code @Transactional}</h2>
 * Zwei Gruende, von denen jeder einzeln schon reicht:
 *
 * <p><b>Erstens arbeitet der Versandpfad in mehreren kurzen Transaktionen.</b> Kein HTTP-Aufruf
 * laeuft innerhalb einer offenen Transaktion - jede Buchung ist eine eigene Anweisung. Eine
 * umgebende Test-Transaktion brachte diese Aufteilung durcheinander.
 *
 * <p><b>Zweitens prueft der Fall "der zweite Lauf versendet nichts" einen Zustand, der committet
 * sein muss.</b> Und mehr noch: Der wichtigste Nachweis dieser Klasse haengt daran. Die Absage
 * wird ueber {@code @TransactionalEventListener(AFTER_COMMIT)} ausgeloest, und der Rueckruf
 * laeuft <b>innerhalb</b> des Commits mit noch gebundener Synchronisation. Ein
 * {@code @Transactional} in Voreinstellung schloesse sich dort der bereits committeten
 * Transaktion an - <b>und jede Schreibanweisung ginge lautlos verloren</b>. Genau deshalb steht
 * am Zuhoerer {@code Propagation.NOT_SUPPORTED}, und genau deshalb pruefen die beiden
 * Protokollfaelle dieser Klasse, dass der Eintrag hinterher wirklich in der Tabelle steht.
 * <b>In einer Test-Transaktion waeren sie gruen und prueften nichts.</b>
 * {@code HallenmodusTests} ist das Vorbild, samt Aufraeumen von Hand.
 *
 * <h2>Eigener Zeitstreifen: 20:45</h2>
 * {@code uq_termin_zeit UNIQUE (datum, uhrzeit)} ist <b>global</b>, und beide Achsen zaehlen.
 * Vergeben sind 40/18:15, 120/19:45, 200/17:30, 300/16:05, 500/20:15, 600 rueckwaerts/21:30 und
 * 700 vorwaerts/19:00. <b>Die Uhrzeit 20:45 traegt hier allein</b> - deshalb darf diese Klasse
 * mehrere Tage belegen, und deshalb raeumt {@link #aufraeumen()} ueber die Uhrzeit auf.
 *
 * <h2>Warum die Erinnerungstermine auf morgen fallen und nicht auf {@link #BASIS_TAGE}</h2>
 * Der Erinnerungsauftrag sucht Termine im Fenster {@code beginn > jetzt} und
 * {@code beginn <= jetzt + Vorlauf}; der Vorlauf ist auf hoechstens 168 Stunden begrenzt. Ein
 * Termin in 800 Tagen faellt <b>nie</b> hinein. Morgen um 20:45 dagegen liegt - von welcher
 * Tageszeit aus auch immer der Lauf startet - zwischen 20:45 und 44:45 Stunden entfernt.
 * <b>Mit einem Vorlauf von {@link #VORLAUF_STUNDEN} Stunden ist er damit unabhaengig von der
 * Uhrzeit des Testlaufs immer im Fenster</b>, und kein Fall haengt daran, wann jemand ihn
 * startet.
 *
 * <p>Die Grenzen des Fensters selbst prueft {@link #dasZeitfensterIstBeidseitigBegrenzt()}
 * direkt am Repository: Dort sind beide Schranken Parameter, und der Fall kommt ganz ohne
 * Wanduhr aus - er trifft sie auf die Sekunde genau.
 *
 * <h2>Die uebrigen Termine liegen weit in der Zukunft</h2>
 * Der Auftrag aus A18 laeuft alle fuenf Minuten und setzt geplante Termine 30 Minuten nach
 * Beginn auf {@code ABGESCHLOSSEN}. Ohne Test-Transaktion sieht er die Zeilen dieser Klasse.
 * <b>Ein Termin in 800 Tagen ist gegen ihn immun</b>; ein vergangener waere es nicht, und der
 * Fall pruefte dann mal die Zeit und mal den Status.
 *
 * <h2>Diese Klasse setzt sich eigene VAPID-Schluessel</h2>
 * Im Testprofil sind sie leer, damit {@code PushControllerTests} den {@code 503}-Fall pruefen
 * kann. Beide Versandwege steigen aber bei {@code !vapidSchluessel.eingerichtet()} sofort aus -
 * ohne Schluessel liefe hier jeder Fall ins Leere und waere trotzdem gruen. Die Werte sind das
 * <b>veroeffentlichte</b> Beispielpaar aus RFC 8291, Anhang A: Es ist nachweislich gueltig, es
 * gehoert zu keinem Server, und es ist kein Geheimnis. Ein echtes Paar duerfte hier nicht
 * stehen.
 *
 * <p><b>Diese Klasse bekommt damit einen eigenen Anwendungskontext.</b> Spring Boot
 * zwischenspeichert nach Konfiguration; sowohl die abweichenden Eigenschaften als auch der
 * eigene {@code @Import}-Satz gehen in den Schluessel ein. Den Satz braucht sie ohnehin - der
 * Versandadapter muss ersetzt sein -, die Eigenschaften kosten also nichts obendrauf.
 * {@code HallenmodusTests} und {@code PasswortResetControllerTests} haben aus demselben Grund
 * bereits einen eigenen. Der Preis ist ein zweiter Datenbankcontainer; dafuer koennen die
 * beiden Push-Testklassen gegensaetzliche Voraussetzungen pruefen.
 *
 * <h2>Der Versandadapter ist ersetzt</h2>
 * {@link PushVersenderErsatz} statt {@code WebPushVersender}: Es gibt im Testlauf keine
 * Push-Dienste, und selbst wenn - sie antworten auch auf eine falsch verschluesselte Nachricht
 * mit {@code 201}. Was der echte Adapter leistet, prueft {@code PushVerschluesselungTests} gegen
 * die Vektoren aus RFC 8291.
 *
 * <h2>Testdaten</h2>
 * Keine realen Personennamen - alle Profile stammen aus den anonymisierten Beispieldaten. Die
 * Endpoint-Adressen liegen unter {@code example.invalid} (RFC 2606), die Absageadresse der Halle
 * ebenfalls; {@code p256dh} und {@code auth} sind die Beispielwerte aus RFC 8291, Anhang A.
 */
@SpringBootTest(properties = {
        "fubo.push.vapid-public-key=BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIg"
                + "Dll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
        "fubo.push.vapid-private-key=yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
        "fubo.push.vapid-subject=mailto:push@example.invalid"
})
@Import({TestcontainersConfiguration.class, MailErsatzConfig.class, PushVersenderErsatzConfig.class})
class PushVersandTests {

    /** Uhrzeit aller Termine dieser Klasse; zugleich der Schluessel zum Aufraeumen. */
    private static final LocalTime UHRZEIT = LocalTime.of(20, 45);

    /** Abstand der Termine, die ausserhalb jedes Erinnerungsfensters liegen sollen. */
    private static final int BASIS_TAGE = 800;

    /**
     * Vorlauf der Erinnerung fuer die Faelle dieser Klasse.
     *
     * <p>48 Stunden - hoch genug, dass "morgen um 20:45" von jeder Tageszeit aus im Fenster
     * liegt (hoechstens 44:45 Stunden entfernt), und innerhalb der Obergrenze von 168, die das
     * DTO erlaubt.
     */
    private static final int VORLAUF_STUNDEN = 48;

    /** Test-Adresse aus RFC 5737; der Schluessel, ueber den Protokolleintraege aufgeraeumt werden. */
    private static final String CLIENT_IP = "203.0.113.45";

    /** Bezeichnung, unter der der Versand seine Protokolleintraege schreibt. */
    private static final String AKTEUR = "System (Push-Versand)";

    private static final String HALLE = "hallenbetreiber@example.invalid";

    /** Praefix aller Endpoint-Adressen dieser Klasse; der Schluessel zum Aufraeumen. */
    private static final String ENDPOINT_PRAEFIX = "https://push.example.invalid/wpush/v2/";

    private static final String P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";

    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    @Autowired
    private PushBenachrichtigungService benachrichtigungService;

    @Autowired
    private PushService pushService;

    @Autowired
    private TerminService terminService;

    @Autowired
    private HallenService hallenService;

    @Autowired
    private TerminRepository terminRepository;

    @Autowired
    private PushVersenderErsatz versender;

    @Autowired
    private MailErsatz mailErsatz;

    @Autowired
    private JdbcTemplate jdbc;

    /** Zaehlt die Termine eines Falls; jeder bekommt einen eigenen Tag. */
    private int terminZaehler;

    private boolean urspruenglichPushAktiv;
    private int urspruenglicherVorlauf;
    private boolean urspruenglicherHallenmodus;
    private String urspruenglicheHalleEmail;

    @BeforeEach
    void aufbauen() {
        versender.zuruecksetzen();
        mailErsatz.zuruecksetzen();
        terminZaehler = 0;

        Map<String, Object> zeile = konfigurationsZeile();
        urspruenglichPushAktiv = (Boolean) zeile.get("push_aktiv");
        urspruenglicherVorlauf = zahl(zeile, "push_erinnerung_stunden");
        urspruenglicherHallenmodus = (Boolean) zeile.get("hallen_modus_aktiv");
        urspruenglicheHalleEmail = (String) zeile.get("halle_email");

        aufraeumen();
        vorlaufSetzen(VORLAUF_STUNDEN);
    }

    /**
     * Ohne umgebende Transaktion bleibt alles stehen, was die Faelle anlegen - auch die
     * geaenderte Konfiguration. Beides wirkte sonst in die naechste Testklasse hinein.
     */
    @AfterEach
    void abbauen() {
        aufraeumen();
        jdbc.update("""
                UPDATE configs.app_config
                   SET push_aktiv              = ?,
                       push_erinnerung_stunden = CAST(? AS smallint),
                       hallen_modus_aktiv      = ?,
                       halle_email             = ?,
                       version                 = version + 1
                 WHERE id = 1
                """, urspruenglichPushAktiv, urspruenglicherVorlauf, urspruenglicherHallenmodus,
                urspruenglicheHalleEmail);
    }

    /**
     * Raeumt Termine, Abonnements, Protokolleintraege und die beiden Personenschalter ab.
     *
     * <p><b>Auch vor jedem Fall</b>, damit ein abgebrochener Lauf den naechsten nicht an
     * {@code uq_termin_zeit} oder {@code uq_push_abo_endpoint_hash} scheitern laesst. Die
     * Teilnahmezeilen haengen ueber {@code ON DELETE CASCADE} an den Terminen und brauchen
     * keine eigene Anweisung.
     */
    private void aufraeumen() {
        jdbc.update("DELETE FROM profil.audit_log WHERE akteur_bezeichnung IN (?, ?)",
                AKTEUR, CLIENT_IP);
        jdbc.update("DELETE FROM spieltag.termin WHERE uhrzeit = ?", UHRZEIT);
        jdbc.update("DELETE FROM profil.push_abo WHERE endpoint LIKE ?", ENDPOINT_PRAEFIX + "%");
        jdbc.update("""
                UPDATE profil.spieler
                   SET push_erwuenscht = true, aktiv = true, version = version + 1
                 WHERE NOT push_erwuenscht OR NOT aktiv
                """);
    }

    // ==================================================================== Empfaengerauswahl

    /**
     * Pruefpunkt 15: Angesprochen wird, wer aktiv ist, kein Admin ist, noch nicht geantwortet hat
     * und ein aktives Abonnement besitzt - und sonst niemand.
     *
     * <p>Vier Ausschlussgruende in einem Fall, jeder mit einem eigenen Profil:
     *
     * <p><b>Die Teilnahmezeile</b> ist der eigentliche Zweck der Erinnerung: Sie geht an die, von
     * denen noch keine Rueckmeldung vorliegt. Wer schon abgesagt hat, hat geantwortet - eine
     * Erinnerung waere eine Aufforderung, etwas zu tun, das er getan hat.
     *
     * <p><b>{@code rolle <> 'ADMIN'} ist keine Formalie.</b> Das Adminprofil ist ein technisches
     * Konto: Es traegt {@code push_erwuenscht = true} aus der Migration, hat <b>nie</b> eine
     * Teilnahmezeile und erfuellt "hat noch nicht geantwortet" damit fuer <b>jeden</b> Termin -
     * waehrend ihm die Rueckmeldung selbst mit {@code 409 PROFIL_GESCHUETZT} verweigert wird.
     * Ohne den Filter bekaeme der Admin zu jedem Training eine Aufforderung, etwas zu tun, was
     * der Server ihm verbietet. Als Abonnent bleibt er zulaessig - {@code /admin/push/test}
     * spricht seine Geraete an.
     *
     * <p><b>Gaeste kommen gar nicht vor</b> (A25d): Sie haben keine Zeile in
     * {@code profil.spieler}, der {@code NOT NULL}-Fremdschluessel schliesst sie ohne eigene
     * Bedingung aus. Deshalb steht dafuer kein Profil in diesem Fall.
     */
    @Test
    void derLaufSprichtNurDieOffenenEmpfaengerAn() {
        Long offen = spielerNach(0);
        Long hatGeantwortet = spielerNach(1);
        Long inaktiv = spielerNach(2);
        Long ohneAbo = spielerNach(3);
        Long admin = adminSpielerId();

        Long terminId = terminAnlegen(1);
        Long erwartetesAbo = aboAnlegen(offen, "offen");
        aboAnlegen(hatGeantwortet, "geantwortet");
        aboAnlegen(inaktiv, "inaktiv");
        aboAnlegen(admin, "admin");

        teilnahme(terminId, hatGeantwortet, false);
        jdbc.update("UPDATE profil.spieler SET aktiv = false, version = version + 1 WHERE id = ?",
                inaktiv);

        benachrichtigungService.erinnerungVersenden();

        assertThat(versender.angesprocheneAbos())
                .as("nur das offene Profil; ohneAbo (%d) hat gar keine Zeile", ohneAbo)
                .containsExactly(erwartetesAbo);
    }

    /**
     * Pruefpunkt 16, erster Schalter: Steht der Hauptschalter der Anlage auf {@code false}, geht
     * nichts hinaus - und es wird auch nichts vermerkt.
     *
     * <p><b>Der Vermerk ist die eigentliche Zusicherung.</b> Wuerde der Lauf die Termine erst
     * markieren und dann den Schalter pruefen, bliebe der Termin fuer immer als "erinnert"
     * stehen: Wer den Schalter spaeter einschaltet, bekaeme fuer diesen Termin nie eine
     * Erinnerung, und die Ursache laege Tage zurueck.
     */
    @Test
    void ohneHauptschalterGehtNichtsHinaus() {
        Long terminId = terminAnlegen(1);
        aboAnlegen(spielerNach(0), "offen");
        hauptschalter(false);

        benachrichtigungService.erinnerungVersenden();

        assertThat(versender.aufrufe()).isEmpty();
        assertThat(pushErinnerungAm(terminId))
                .as("der Termin bleibt unmarkiert und kann spaeter noch erinnert werden")
                .isNull();
    }

    /**
     * Pruefpunkt 16, zweiter Schalter: Wer {@code push_erwuenscht = false} gesetzt hat, bekommt
     * nichts.
     *
     * <p><b>Das Abonnement bleibt dabei bestehen</b>, und das ist Absicht (A25f): Der
     * Personenschalter ist eine andere Handlung als der Widerruf eines Geraets. Wuerde er die
     * Abonnements loeschen, verlangte das Wiedereinschalten einen neuen Browserdialog - auf
     * jedem Geraet einzeln.
     *
     * <p>Anders als beim Hauptschalter <b>wird der Termin hier markiert</b>: Der Lauf hat
     * stattgefunden, er hatte nur keine Empfaenger. Ein zweiter Lauf soll ihn nicht erneut
     * aufgreifen.
     */
    @Test
    void ohnePersonenschalterGehtNichtsHinaus() {
        Long spielerId = spielerNach(0);
        Long terminId = terminAnlegen(1);
        Long aboId = aboAnlegen(spielerId, "abgeschaltet");
        jdbc.update("""
                UPDATE profil.spieler SET push_erwuenscht = false, version = version + 1
                 WHERE id = ?
                """, spielerId);

        benachrichtigungService.erinnerungVersenden();

        assertThat(versender.aufrufe()).isEmpty();
        assertThat(aboZeile(aboId).get("deaktiviert_am"))
                .as("der Personenschalter loescht keine Abonnements")
                .isNull();
        assertThat(pushErinnerungAm(terminId))
                .as("der Lauf hat stattgefunden - nur ohne Empfaenger")
                .isNotNull();
    }

    /**
     * Pruefpunkt 16, dritter Schalter: Ein deaktiviertes Abonnement wird nicht mehr angesprochen.
     *
     * <p>Das ist die Geraeteebene. Sie ist die einzige der drei, die nicht in einem Formular
     * steht - der Browser entscheidet sie, und deshalb liefert {@code /push/status/lesen} die
     * anderen beiden getrennt: Nur so laesst sich "es kommt nichts an" einer Ursache zuordnen.
     */
    @Test
    void ohneAktivesAboGehtNichtsHinaus() {
        Long spielerId = spielerNach(0);
        Long terminId = terminAnlegen(1);
        Long aboId = aboAnlegen(spielerId, "erloschen");
        jdbc.update("""
                UPDATE profil.push_abo SET deaktiviert_am = now(), version = version + 1
                 WHERE id = ?
                """, aboId);

        benachrichtigungService.erinnerungVersenden();

        assertThat(versender.aufrufe()).isEmpty();
        assertThat(pushErinnerungAm(terminId)).isNotNull();
    }

    /**
     * Pruefpunkt 17: Der zweite Lauf versendet nichts, und {@code push_erinnerung_am} bleibt
     * unveraendert.
     *
     * <p>Der Riegel ist ein bedingtes {@code UPDATE ... WHERE push_erinnerung_am IS NULL} - er
     * traegt den Fall "Neustart mitten im Versand" und, bei mehreren Instanzen, zwei
     * gleichzeitige Laeufe. Markiert wird <b>vor</b> dem Versenden: Ein Absturz mitten im Versand
     * kostet dann einzelne Nachrichten, waehrend die umgekehrte Reihenfolge nach einem Neustart
     * <b>alle</b> Empfaenger ein zweites Mal benachrichtigte.
     *
     * <p><b>Ohne committete Zeilen pruefte dieser Fall nichts</b> - das ist der zweite Grund,
     * aus dem diese Klasse kein {@code @Transactional} traegt.
     */
    @Test
    void derZweiteLaufVersendetNichtsUndAendertDenVermerkNicht() {
        Long terminId = terminAnlegen(1);
        aboAnlegen(spielerNach(0), "offen");

        benachrichtigungService.erinnerungVersenden();
        String ersterVermerk = pushErinnerungAm(terminId);
        int nachDemErstenLauf = versender.aufrufe().size();

        benachrichtigungService.erinnerungVersenden();

        assertThat(nachDemErstenLauf).isEqualTo(1);
        assertThat(versender.aufrufe()).hasSize(1);
        assertThat(pushErinnerungAm(terminId)).isEqualTo(ersterVermerk);
    }

    /**
     * Pruefpunkt 18: Das Zeitfenster ist <b>beidseitig</b> begrenzt - zu frueh faellt heraus, und
     * bereits begonnen ebenso.
     *
     * <p><b>Geprueft direkt am Repository und nicht ueber den Auftrag</b>, weil dort beide
     * Schranken Parameter sind. Ueber den Auftrag haengen sie an der Wanduhr, und ein Fall, der
     * eine Grenze auf die Sekunde treffen will, waere dann davon abhaengig, wann jemand den
     * Testlauf startet.
     *
     * <p>Die obere Schranke ist {@code <=} und die untere {@code >}: Ein Termin, der genau jetzt
     * beginnt, faellt heraus - eine Erinnerung an eine offene Rueckmeldung kaeme dann zu spaet,
     * um noch etwas zu aendern.
     */
    @Test
    void dasZeitfensterIstBeidseitigBegrenzt() {
        Long terminId = terminAnlegen(BASIS_TAGE);
        LocalDateTime beginn = LocalDateTime.of(LocalDate.now().plusDays(BASIS_TAGE), UHRZEIT);

        assertThat(ids(terminRepository.faelligeFuerPushErinnerung(beginn.minusHours(1), beginn)))
                .as("im Fenster; die obere Schranke ist einschliesslich")
                .contains(terminId);

        assertThat(ids(terminRepository.faelligeFuerPushErinnerung(
                beginn.minusHours(5), beginn.minusMinutes(1))))
                .as("zu frueh - der Termin liegt hinter der oberen Schranke")
                .doesNotContain(terminId);

        assertThat(ids(terminRepository.faelligeFuerPushErinnerung(beginn, beginn.plusHours(2))))
                .as("bereits begonnen - die untere Schranke ist ausschliesslich")
                .doesNotContain(terminId);
    }

    // ==================================================================== Buchung der Antworten

    /**
     * Pruefpunkt 19, erster Teil: {@code 410} deaktiviert das Abonnement.
     *
     * <p>Der Push-Dienst sagt damit, dass die Adresse nicht mehr existiert. Weitere Versuche
     * waeren verschenkt, und der Fehlversuchszaehler braucht dafuer gar nicht erst zu laufen.
     * <b>Geloescht wird trotzdem nicht sofort</b>: Der Zwischenzustand unterscheidet "nie
     * abonniert" von "Abonnement erloschen", und meldet sich derselbe Browser neu an, wird die
     * Zeile wieder aktiv.
     */
    @Test
    void ein410DeaktiviertDasAbonnement() {
        Long aboId = aboAnlegen(spielerNach(0), "erloschen");
        terminAnlegen(1);
        versender.antwortetMit(410);

        benachrichtigungService.erinnerungVersenden();

        Map<String, Object> zeile = aboZeile(aboId);
        assertThat(zeile.get("deaktiviert_am")).isNotNull();
        assertThat(zahl(zeile, "fehlversuche"))
                .as("ein erloschenes Abonnement braucht keinen Zaehler")
                .isZero();
    }

    /**
     * Pruefpunkt 19, zweiter Teil: Ein {@code 5xx} erhoeht nur den Zaehler.
     *
     * <p>Ein Serverfehler beim Push-Dienst sagt nichts ueber das Abonnement aus - er ist
     * voruebergehend. Wuerde er sofort deaktivieren, kostete eine Stoerung beim Anbieter den
     * gesamten Bestand an Abonnements, und jedes Geraet braeuchte einen neuen Browserdialog.
     */
    @Test
    void ein5xxErhoehtNurDenFehlversuchszaehler() {
        Long aboId = aboAnlegen(spielerNach(0), "gestoert");
        terminAnlegen(1);
        versender.antwortetMit(503);

        benachrichtigungService.erinnerungVersenden();

        Map<String, Object> zeile = aboZeile(aboId);
        assertThat(zahl(zeile, "fehlversuche")).isEqualTo(1);
        assertThat(zeile.get("deaktiviert_am")).isNull();
    }

    /**
     * Pruefpunkt 19, dritter Teil: Der fuenfte Fehlversuch deaktiviert.
     *
     * <p>Die Grenze steht in {@code PushVersandService.MAX_FEHLVERSUCHE}, die Entscheidung selbst
     * im {@code CASE} derselben SQL-Anweisung, die den Zaehler erhoeht. <b>Zwei Anweisungen
     * waeren hier zwei Gelegenheiten, die Grenze unterschiedlich zu lesen</b> - und die zweite
     * liefe ohne die erste, wenn dazwischen etwas schiefgeht.
     *
     * <p>Der Zaehler wird auf vier vorgesetzt, statt fuenf Laeufe zu fahren: Fuenf Laeufe
     * brauchten fuenf Termine, und der Fall hiesse dann "fuenf Termine ergeben eine
     * Deaktivierung" - eine andere Aussage als die gepruefte.
     */
    @Test
    void derFuenfteFehlversuchDeaktiviert() {
        Long aboId = aboAnlegen(spielerNach(0), "fast-tot");
        jdbc.update("UPDATE profil.push_abo SET fehlversuche = 4, version = version + 1 "
                + "WHERE id = ?", aboId);
        terminAnlegen(1);
        versender.antwortetMit(503);

        benachrichtigungService.erinnerungVersenden();

        Map<String, Object> zeile = aboZeile(aboId);
        assertThat(zahl(zeile, "fehlversuche")).isEqualTo(5);
        assertThat(zeile.get("deaktiviert_am"))
                .as("Zaehler und Deaktivierung in derselben Anweisung")
                .isNotNull();
    }

    /**
     * Pruefpunkt 20: Ein {@code 401} deaktiviert <b>nicht</b> - und erhoeht auch den Zaehler
     * nicht.
     *
     * <p><b>Das ist der teuerste Fehler, den dieser Meilenstein machen koennte.</b> Ein
     * {@code 401} heisst "unsere Berechtigung wurde abgelehnt" und hat seine Ursache immer beim
     * VAPID-Zugang - ein vertauschtes Schluesselpaar, ein unbrauchbarer {@code sub}-Anspruch,
     * eine Signatur in DER-Form. Er trifft damit <b>jedes</b> Abonnement gleichzeitig. Wuerde er
     * wie ein Fehlversuch gebucht, loeschte ein falsch gesetzter Schluessel innerhalb weniger
     * Laeufe den gesamten Bestand, und die Wiederherstellung verlangte von jedem Spieler einen
     * neuen Browserdialog auf jedem Geraet. Die Zeile bleibt deshalb unangetastet; protokolliert
     * wird der Fall als Fehler des Servers.
     */
    @Test
    void ein401DeaktiviertNicht() {
        Long aboId = aboAnlegen(spielerNach(0), "abgelehnt");
        terminAnlegen(1);
        versender.antwortetMit(401);

        benachrichtigungService.erinnerungVersenden();

        Map<String, Object> zeile = aboZeile(aboId);
        assertThat(zeile.get("deaktiviert_am")).isNull();
        assertThat(zahl(zeile, "fehlversuche"))
                .as("ein Fehler auf unserer Seite geht nicht zulasten des Abonnements")
                .isZero();
    }

    // ==================================================================== Anlass 2: Absage

    /**
     * Pruefpunkt 21, erster Ausloeserpfad: {@code /admin/termin/absagen}.
     *
     * <p><b>Die Empfaenger sind hier die Zusager</b> - genau umgekehrt zur Erinnerung. Das ist
     * kein Versehen, sondern der Unterschied der beiden Anlaesse: Die Erinnerung geht an die, von
     * denen nichts vorliegt; die Absage an die, die zugesagt haben und sonst vor einer
     * geschlossenen Halle staenden. Wer abgesagt hat, hatte ohnehin nicht vor zu kommen.
     */
    @Test
    void dieAbsageUeberDenTerminendpunktErreichtDieZusager() {
        Long zusager = spielerNach(0);
        Long absager = spielerNach(1);
        Long terminId = terminAnlegen(BASIS_TAGE);

        Long erwartetesAbo = aboAnlegen(zusager, "zusager");
        aboAnlegen(absager, "absager");
        teilnahme(terminId, zusager, true);
        teilnahme(terminId, absager, false);

        terminService.absagen(terminId, adminSpielerId(), CLIENT_IP);

        assertThat(versender.angesprocheneAbos()).containsExactly(erwartetesAbo);
        assertThat(terminStatus(terminId)).isEqualTo("ABGESAGT");
    }

    /**
     * Pruefpunkt 21, zweiter Ausloeserpfad: das Bearbeitungsformular
     * ({@code /admin/termin/aendern} mit {@code status = ABGESAGT}).
     *
     * <p><b>Dieser Pfad wird leicht uebersehen</b>, weil A25b nur den ersten nennt. Aus Sicht des
     * Zusagers ist der Unterschied keiner: Der Termin faellt aus, und er soll es erfahren -
     * unabhaengig davon, welches Formular der Admin benutzt hat. Ausgeloest wird deshalb am
     * Statuswechsel und nicht am Endpunkt.
     */
    @Test
    void dieAbsageUeberDasBearbeitungsformularErreichtDieZusager() {
        Long zusager = spielerNach(0);
        Long terminId = terminAnlegen(BASIS_TAGE);
        Long erwartetesAbo = aboAnlegen(zusager, "zusager");
        teilnahme(terminId, zusager, true);

        terminService.aendern(new TerminAendernRequest(terminId, terminVersion(terminId),
                null, null, null, TerminStatus.ABGESAGT), adminSpielerId(), CLIENT_IP);

        assertThat(versender.angesprocheneAbos()).containsExactly(erwartetesAbo);
        assertThat(terminStatus(terminId)).isEqualTo("ABGESAGT");
    }

    /**
     * Pruefpunkt 21, dritter Ausloeserpfad: die Hallenabsage (A23).
     *
     * <p>Sie sagt den Termin mit ab, wenn er noch geplant war - und dann gilt fuer die Zusager
     * dasselbe wie bei den anderen beiden Wegen. Die Mail an den Hallenbetreiber geht an den
     * {@link MailErsatz}; ihr Inhalt ist Sache von {@code HallenmodusTests}.
     */
    @Test
    void dieHallenabsageErreichtDieZusager() {
        Long zusager = spielerNach(0);
        Long terminId = terminAnlegen(BASIS_TAGE);
        Long erwartetesAbo = aboAnlegen(zusager, "zusager");
        teilnahme(terminId, zusager, true);
        hallenmodusEinschalten();

        hallenService.absagen(terminId, adminSpielerId(), CLIENT_IP);

        assertThat(mailErsatz.nachrichten()).hasSize(1);
        assertThat(versender.angesprocheneAbos()).containsExactly(erwartetesAbo);
        assertThat(terminStatus(terminId)).isEqualTo("ABGESAGT");
    }

    /**
     * Pruefpunkt 22: Scheitert der Mailversand der Hallenabsage, geht <b>keine</b> Push-Nachricht
     * hinaus.
     *
     * <p><b>Das ist der Fall, fuer den {@code AFTER_COMMIT} ueberhaupt gewaehlt wurde.</b> Ein
     * direkter Aufruf im Dienst versendete die Push-Nachricht, bevor feststeht, ob die Absage
     * Bestand hat - und der Rollback nach dem gescheiterten Mailversand naehme sie nicht zurueck.
     * Die Zusager bekaemen "Training faellt aus", waehrend der Termin in der Datenbank weiter auf
     * {@code GEPLANT} steht. <b>Eine zugestellte Nachricht laesst sich nicht zurueckrollen</b>;
     * wo Versand und Datenbankzustand auseinanderfallen koennen, wird die Richtung gewaehlt, in
     * der hoechstens eine Nachricht zu wenig hinausgeht.
     */
    @Test
    void scheiternderMailversandDerHallenabsageVersendetKeinePushNachricht() {
        Long zusager = spielerNach(0);
        Long terminId = terminAnlegen(BASIS_TAGE);
        aboAnlegen(zusager, "zusager");
        teilnahme(terminId, zusager, true);
        hallenmodusEinschalten();
        mailErsatz.laesstScheitern(true);

        assertThatThrownBy(() -> hallenService.absagen(terminId, adminSpielerId(), CLIENT_IP))
                .isInstanceOf(FachlicherFehler.class);

        assertThat(versender.aufrufe()).isEmpty();
        assertThat(terminStatus(terminId))
                .as("die Absage ist mit zurueckgerollt")
                .isEqualTo("GEPLANT");
    }

    // ==================================================================== Protokoll

    /**
     * Der Erinnerungslauf schreibt einen Protokolleintrag - <b>je Lauf und Termin</b>, nicht je
     * Empfaenger.
     *
     * <p>Bei dreissig Spielern waeren das sonst dreissig Zeilen fuer einen Vorgang, und die
     * Loeschfrist von 30 Tagen traefe sie ohnehin alle gemeinsam. Die Zahlen stehen in den
     * Details: {@code empfaenger} zaehlt Personen, {@code geraete} Abonnements. <b>Der
     * Unterschied ist der Grund fuer beide Zahlen</b> - ein Spieler mit Telefon und Rechner ist
     * ein Empfaenger und zwei Geraete, und ohne die Trennung liesse sich "zehn Nachrichten
     * versandt" nicht deuten.
     */
    @Test
    void derErinnerungslaufSchreibtEinenProtokolleintrag() {
        Long spielerId = spielerNach(0);
        Long terminId = terminAnlegen(1);
        aboAnlegen(spielerId, "telefon");
        aboAnlegen(spielerId, "rechner");

        benachrichtigungService.erinnerungVersenden();

        assertThat(versender.aufrufe()).hasSize(2);
        assertThat(auditDetail(terminId, "PUSH_ERINNERUNG_VERSANDT", "empfaenger")).isEqualTo("1");
        assertThat(auditDetail(terminId, "PUSH_ERINNERUNG_VERSANDT", "geraete")).isEqualTo("2");
        assertThat(auditDetail(terminId, "PUSH_ERINNERUNG_VERSANDT", "zugestellt")).isEqualTo("2");
    }

    /**
     * Der Absagezuhoerer schreibt seinen Protokolleintrag, <b>obwohl er nach dem Commit
     * laeuft</b>.
     *
     * <p>Das ist die Gegenprobe zu {@code Propagation.NOT_SUPPORTED} am Zuhoerer - und der Grund,
     * aus dem dieser Fall ueberhaupt existiert. Der Rueckruf eines
     * {@code @TransactionalEventListener(AFTER_COMMIT)} laeuft <b>innerhalb</b> des Commits, mit
     * noch gebundener Transaktionssynchronisation. Ein {@code @Transactional} in Voreinstellung
     * ({@code REQUIRED}) schloesse sich dort der bereits committeten Transaktion an: Die
     * Schreibanweisung liefe durch, meldete keinen Fehler - und <b>waere hinterher nicht in der
     * Tabelle</b>. Ein Fehler, den man weder im Protokoll noch an einer Ausnahme sieht, sondern
     * nur an einer fehlenden Zeile.
     *
     * <p><b>In einer Test-Transaktion waere dieser Fall gruen und prueft nichts</b>, weil die
     * Zeile dann in derselben Transaktion staende, in der auch gelesen wird.
     */
    @Test
    void dieAbsageSchreibtIhrenProtokolleintragTrotzCommitGrenze() {
        Long zusager = spielerNach(0);
        Long terminId = terminAnlegen(BASIS_TAGE);
        aboAnlegen(zusager, "zusager");
        teilnahme(terminId, zusager, true);

        terminService.absagen(terminId, adminSpielerId(), CLIENT_IP);

        assertThat(auditDetail(terminId, "PUSH_ABSAGE_VERSANDT", "empfaenger")).isEqualTo("1");
        assertThat(auditDetail(terminId, "PUSH_ABSAGE_VERSANDT", "geraete")).isEqualTo("1");
        assertThat(auditAkteur(terminId, "PUSH_ABSAGE_VERSANDT"))
                .as("eine Systemhandlung ohne Handelnden")
                .isEqualTo(AKTEUR);
    }

    // ==================================================================== Aufraeumen

    /**
     * Pruefpunkt 23: Der Aufraeumlauf entfernt erloschene Abonnements aelter als 30 Tage und
     * laesst juengere stehen.
     *
     * <p>Die Frist ist dieselbe wie beim Protokoll und bei den Zuruecksetz-Anforderungen. Sie
     * gibt einem Browser, der sich nach einer Stoerung wieder meldet, Zeit, seine alte Zeile
     * wiederzubeleben, statt sie neu anlegen zu muessen - und sie haelt die Tabelle klein.
     *
     * <p><b>Kein eigener {@code @Scheduled}-Auftrag</b>: Der Lauf haengt am naechtlichen
     * Aufraeumen der Sitzungen. Ein zweiter Takt fuer dieselbe Sache waere ein zweiter Ort, an
     * dem jemand eine Frist verstellt.
     */
    @Test
    void derAufraeumlaufEntferntNurErloscheneAelterAls30Tage() {
        Long spielerId = spielerNach(0);
        Long alt = aboAnlegen(spielerId, "alt");
        Long jung = aboAnlegen(spielerId, "jung");
        deaktiviertSeit(alt, 40);
        deaktiviertSeit(jung, 10);
        Long aktiv = aboAnlegen(spielerId, "aktiv");

        int entfernt = pushService.erloscheneEntfernen();

        assertThat(entfernt).isEqualTo(1);
        assertThat(aboVorhanden(alt)).isFalse();
        assertThat(aboVorhanden(jung)).as("innerhalb der Frist - kann wiederbelebt werden").isTrue();
        assertThat(aboVorhanden(aktiv)).as("aktive Abonnements gehen den Lauf nichts an").isTrue();
    }

    // ==================================================================== Hilfsmittel

    /**
     * Legt einen Termin an und gibt seine Id zurueck.
     *
     * <p>Ueber SQL und nicht ueber den Endpunkt: Der Aufbau soll nicht am Terminendpunkt haengen,
     * und ein Termin in 800 Tagen liefe dort gegen keine Pruefung, aber der Umweg braeuchte eine
     * Adminsitzung. Jeder Termin eines Falls bekommt einen eigenen Tag - die Uhrzeit ist der
     * Zeitstreifen der Klasse und fuer alle gleich.
     *
     * @param tageVoraus Abstand zum heutigen Tag
     */
    private Long terminAnlegen(int tageVoraus) {
        LocalDate datum = LocalDate.now().plusDays(tageVoraus + terminZaehler++);
        return jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit, ort, status)
                     VALUES (?, ?, 'Sporthalle Nord', 'GEPLANT')
                  RETURNING id
                """, Long.class, datum, UHRZEIT);
    }

    /**
     * Legt ein aktives Abonnement an und gibt seine Id zurueck.
     *
     * <p>Die Kennung geht in die Adresse ein und macht sie eindeutig -
     * {@code uq_push_abo_endpoint_hash} gilt global, auch innerhalb eines Falls.
     */
    private Long aboAnlegen(Long spielerId, String kennung) {
        String endpoint = ENDPOINT_PRAEFIX + kennung;
        return jdbc.queryForObject("""
                INSERT INTO profil.push_abo
                            (spieler_id, endpoint, endpoint_hash, p256dh, auth, geraet_bezeichnung)
                     VALUES (?, ?, ?, ?, ?, 'Pruefgeraet')
                  RETURNING id
                """, Long.class, spielerId, endpoint, TokenGenerator.hash(endpoint), P256DH, AUTH);
    }

    private void teilnahme(Long terminId, Long spielerId, boolean zusage) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage)
                     VALUES (?, ?, ?)
                """, terminId, spielerId, zusage);
    }

    private void deaktiviertSeit(Long aboId, int tage) {
        jdbc.update("""
                UPDATE profil.push_abo
                   SET deaktiviert_am = now() - CAST(? AS interval), version = version + 1
                 WHERE id = ?
                """, tage + " days", aboId);
    }

    // --------------------------------------------------------------------- Konfiguration

    private Map<String, Object> konfigurationsZeile() {
        return jdbc.queryForMap("SELECT * FROM configs.app_config WHERE id = 1");
    }

    private void hauptschalter(boolean an) {
        jdbc.update("UPDATE configs.app_config SET push_aktiv = ?, version = version + 1 "
                + "WHERE id = 1", an);
    }

    private void vorlaufSetzen(int stunden) {
        jdbc.update("UPDATE configs.app_config SET push_erinnerung_stunden = CAST(? AS smallint), "
                + "version = version + 1 WHERE id = 1", stunden);
    }

    /** Hallenmodus und Empfaengeradresse - beides Vorbedingungen von {@code HallenService}. */
    private void hallenmodusEinschalten() {
        jdbc.update("""
                UPDATE configs.app_config
                   SET hallen_modus_aktiv = true, halle_email = ?, version = version + 1
                 WHERE id = 1
                """, HALLE);
    }

    // --------------------------------------------------------------------- Auswertung

    private static List<Long> ids(List<Erinnerungstermin> termine) {
        return termine.stream().map(Erinnerungstermin::id).toList();
    }

    private String pushErinnerungAm(Long terminId) {
        return jdbc.queryForObject(
                "SELECT push_erinnerung_am::text FROM spieltag.termin WHERE id = ?",
                String.class, terminId);
    }

    /**
     * Der Terminstatus aus der Datenbank.
     *
     * <p>Die Methode heisst nicht {@code status}: In den Controllertests verdeckte ein solcher
     * Name den statischen Import {@code MockMvcResultMatchers.status()} vollstaendig. Hier gibt
     * es den Import nicht, aber ein Name, der in der Nachbarklasse eine Falle ist, soll auch
     * hier nicht stehen.
     */
    private String terminStatus(Long terminId) {
        return jdbc.queryForObject("SELECT status FROM spieltag.termin WHERE id = ?",
                String.class, terminId);
    }

    private Long terminVersion(Long terminId) {
        return jdbc.queryForObject("SELECT version FROM spieltag.termin WHERE id = ?",
                Long.class, terminId);
    }

    private Map<String, Object> aboZeile(Long aboId) {
        return jdbc.queryForMap("SELECT * FROM profil.push_abo WHERE id = ?", aboId);
    }

    private boolean aboVorhanden(Long aboId) {
        return jdbc.queryForObject("SELECT count(*) FROM profil.push_abo WHERE id = ?",
                Integer.class, aboId) == 1;
    }

    /**
     * Ein Detail aus dem juengsten Protokolleintrag zu diesem Termin.
     *
     * <p>{@code ->>} liefert den Wert als Text - auch bei Zahlen. <b>Der jsonb-Existenzoperator
     * {@code ?} waere hier nicht verwendbar</b>: Fuer JDBC ist das Fragezeichen ein
     * Bind-Platzhalter.
     */
    private String auditDetail(Long terminId, String aktion, String schluessel) {
        return jdbc.query("""
                SELECT details ->> CAST(? AS text)
                  FROM profil.audit_log
                 WHERE aktion = CAST(? AS varchar)
                   AND entitaet = 'termin'
                   AND entitaet_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, zeile) -> rs.getString(1), schluessel, aktion, terminId)
                .stream().findFirst().orElse(null);
    }

    private String auditAkteur(Long terminId, String aktion) {
        return jdbc.query("""
                SELECT akteur_bezeichnung
                  FROM profil.audit_log
                 WHERE aktion = CAST(? AS varchar)
                   AND entitaet = 'termin'
                   AND entitaet_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, zeile) -> rs.getString(1), aktion, terminId)
                .stream().findFirst().orElse(null);
    }

    /** Zahl aus einer Datenbankzeile; der Treiber liefert SMALLINT je nach Fassung verschieden. */
    private static int zahl(Map<String, Object> zeile, String name) {
        return ((Number) zeile.get(name)).intValue();
    }

    // --------------------------------------------------------------------- Profile

    /** Ein aktives Spielerprofil aus den Beispieldaten (keine realen Namen, keine Adminzeile). */
    private Long spielerNach(int uebersprungen) {
        return jdbc.queryForObject("""
                SELECT id FROM profil.spieler
                 WHERE rolle = 'USER'
                 ORDER BY name
                 LIMIT 1 OFFSET ?
                """, Long.class, uebersprungen);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject("SELECT spieler_id FROM profil.admin_konto WHERE id = 1",
                Long.class);
    }
}
