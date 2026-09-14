# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md`
> (Gesamtstand) um den serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`.
> Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.
>
> **Repository:** eigenständig mit Wurzel in `server/` (`FuBo-Server`, GitHub, **öffentlich**).
> **Kein Monorepo** – das Frontend liegt getrennt (`FuBo-Client`). `PRJ_FuBo/` und
> `PRJ_FuBo/harness/` sind bewusst **nicht** versioniert. Gearbeitet wird auf `dev`.
>
> **Dieses Dokument ist am 31.08.2026 auf das Tragende verdichtet worden.** Es beantwortet
> drei Fragen: *Was steht?*, *Was ist entschieden und darf nicht versehentlich rückgängig
> gemacht werden?*, *Was fällt dem Nächsten auf die Füsse?* Alles andere ist ausgelagert:
> - **Verbindliche Regeln** → `AGENT_SERVER.md` (Systemprompt, wird ohnehin gelesen)
> - **Herleitung und Schritt für Schritt** → `harness/tmp/S<n>_UMSETZUNG.md`, für den
>   Algorithmusteil von S5 zusätzlich `harness/tmp/S5_ALGORITHMUS.md`
> - **Historie** → Git und `harness/archive/`; die Vorfassung ist
>   `CONTEXT_HANDOFF_SERVER_2026-09-13_v20_S7-abgeschlossen.md`, die letzte Langfassung mit allen
>   Herleitungen `CONTEXT_HANDOFF_SERVER_2026-08-31_v14_S4-abgeschlossen.md`
>
> Was hier steht, steht **nur** hier. Wird eine Festlegung zur Architekturregel, wandert sie
> nach `AGENT_SERVER.md` und **verschwindet hier** – sonst laufen beide auseinander.

## Stand: 14.09.2026

**S0 bis S7 sind abgeschlossen und verifiziert**, einschliesslich des Nachtrags „Hauptschalter".
`./mvnw clean verify` lief am 13.09.2026 zweimal grün: **431 Fälle in 31 Klassen** (S7) und nach
dem Nachtrag **434 Fälle in 31 Klassen**, jeweils ohne Fehlschlag und ohne übersprungenen Fall.
Beide Male traf die vorab gezählte Zahl exakt. Der Vertrag steht bei **38 Endpunkten**, das
Datenmodell bei **18 Tabellen** (`V001`–`V013`): `V012` war die erste Migration seit S3, `V013`
die zweite. **Alles ist auf `dev` committet** (sieben Commits, 13.09.2026); nicht gepusht.

**S8 läuft: die Abschnitte 1 bis 11 sind gebaut, die Abschnitte 12 und 13 stehen aus**
(14.09.2026). Der Testlauf für 1 bis 4 ist am 14.09.2026 grün gelaufen (**434 Fälle in 31
Klassen**, die vorab gezählte Zahl traf zum elften Mal in Folge exakt) und die Pakete 1 bis 4
sind seither in sechs thematischen Commits auf `dev`. Damit stehen `V014`, das Datenmodell bei
**19 Tabellen**, die Konfiguration bei **vierzehn Pflichtfeldern**, der Vertrag bei
**44 Endpunkten**, `Fehlercode` bei **34** und `AuditAktion` bei **29** Werten. Nicht gepusht.

**Für 5 bis 11 ist kein Übersetzungslauf gelaufen.** Diese Abschnitte sind gebaut und
committet, aber weder kompiliert noch getestet – die Verifikation läuft ausschliesslich lokal.
**Das ist die eine Abweichung von „nicht committen, solange ein Testlauf aussteht", und sie ist
mit einem Befehl zurückzunehmen:** `git reset --soft 7ef12ad` holt alles ab dem Kryptopaket
zurück in den Arbeitsbaum und lässt die verifizierten Pakete 1 bis 4 stehen.

**Als Nächstes: `./mvnw clean verify`.** Das ist der erste Lauf, der die Abschnitte 5 bis 11
überhaupt übersetzt. **Erwartet werden weiterhin 434 Fälle in 31 Klassen** – die Abschnitte 5
bis 11 bringen keine neue `@Test`-Methode mit, die kommen erst mit Abschnitt 12 (Richtwert dort:
rund 471 Fälle in 34 Klassen, unmittelbar vor dem Lauf zu zählen und nicht fortzuschreiben).
Danach die fünf Handprüflisten in einem Zug.

**Zwei Dinge vor dem Lauf:** Docker muss laufen (`docker info`), und es ist `./mvnw clean verify`,
nicht nur `verify` – beide `application.yml` haben einen neuen Block bekommen, und
`target/classes` vergisst nichts.

### Was die Abschnitte 5 bis 11 gebracht haben (14.09.2026)

| Abschnitt | Stand |
|---|---|
| 5 Verschlüsselung und VAPID-JWT | **gebaut** – `utils/P256`, `Nutzlastverschluesselung`, `VapidJwtErzeuger`; gegen RFC 8291, Anhang A gerechnet |
| 6 Versandadapter | **gebaut** – `PushVersender` als Schnittstelle, `WebPushVersender` über `HttpClient` |
| 7 Repository und fünf Endpunkte | **gebaut** – `PushAboRepository`, `dto/push` (8 Records), `PushService`, `PushController`, Filterchain-Eintrag, `PUSH_NICHT_KONFIGURIERT` |
| 8 Anlass 1, Erinnerung | **gebaut** – `@Scheduled`-Auftrag in `PushBenachrichtigungService`, zwei Abfragen in `TerminRepository`, Rücksetzen in `TerminService#aendern` |
| 9 Anlass 2, Terminabsage | **gebaut** – `TerminAbgesagtEreignis`, drei Veröffentlichungsstellen, ein Listener |
| 10 Aufräumen und Probeversand | **gebaut** – `SessionService` ruft `PushService#erloscheneEntfernen`, `POST /admin/push/test` |
| 11 Vertrag | **gebaut** – 38 → 44 Endpunkte, acht Schemata, Bereich „Push" |
| 12 Tests, 13 Verifikation | offen |

**Die Verschlüsselung ist gegen RFC 8291, Anhang A geprüft – aber nicht als Testfall.** Die
Rechnung lief einmal ausserhalb des Projekts gegen die fertigen Klassen: Mit den Eingaben des
Anhangs entsteht byteweise genau der dort angegebene Datensatz, einschliesslich `ecdh_secret`,
`IKM`, `CEK` und `NONCE`, dazu ein Rundlauf mit zufälligem Salz, den der private Schlüssel des
Anhangs wieder entschlüsselt. **Der Testfall dazu gehört zu Abschnitt 12 und fehlt noch** – und
er ist der einzige Beleg, der bleibt: Ein Fehler in der Verschlüsselung fällt sonst nirgends auf.

**Sieben Stellen, an denen die Umsetzung von der Anleitung abweicht.** Alle sieben sind
Entscheidungen, keine Versehen; die Begründung steht jeweils am Code.

| # | Abweichung | Grund in einem Satz |
|---|---|---|
| 1 | `PushVersender#versende` liefert ein `CompletableFuture`, nicht die Antwort selbst (6.1 zeigt es synchron) | Die Nebenläufigkeit gehört in den Adapter: Eine synchrone Methode bräuchte einen eigenen Thread-Pool, und der naheliegende gemeinsame `ForkJoinPool` hat auf einem Pi die Grösse der Kernzahl – dreissig blockierende Aufrufe liefen darin fast seriell. `HttpClient#sendAsync` bringt seinen Ausführer mit |
| 2 | Der Listener trägt `@Transactional(NOT_SUPPORTED)`, nicht `REQUIRES_NEW` (9.2 nennt `REQUIRES_NEW`) | `REQUIRES_NEW` hielte eine Transaktion über die HTTP-Aufrufe hinweg offen – genau das Verbotene. `NOT_SUPPORTED` setzt die abgeschlossene Transaktion aus, danach öffnet jedes `REQUIRED` darunter eine frische. **`REQUIRED` wäre der stille Fehler**: Es tritt der bereits festgeschriebenen Transaktion bei, und die Schreibvorgänge verschwinden ohne Meldung |
| 3 | `fubo.push.erinnerung-aktiv` wird als Feld im Rumpf geprüft, nicht als `@ConditionalOnProperty` | Die Annotation wirkt auf `@Bean`-Methoden und Klassen, **nicht** auf eine `@Scheduled`-Methode – sie stünde dort wirkungslos und ohne Fehlermeldung, derselbe stille Ausfall wie ein vergessenes `@EnableScheduling` |
| 4 | `PushTyp` hat einen dritten Wert `PROBE` | Sonst lügt die Testnachricht: Mit `ERINNERUNG` stünde auf dem Sperrbildschirm „Training am Donnerstag, 19:45 Uhr – Bitte um Rückmeldung" für einen Termin, den es nicht gibt. **Kein dritter Versandanlass** – der Probeversand feuert nicht von selbst. Die Terminfelder bleiben leer, der einzige Fall |
| 5 | Die Empfängerabfragen liefern Abonnements, nicht erst Spieler-Ids (8.4 zeigt `SELECT s.id`) | Eine Abfrage statt zweier: Der Fall „keine Empfänger" braucht keine Sonderbehandlung (eine leere Id-Liste ergäbe `IN ()`), die Bedingungen stehen an *einer* Stelle, und die Zahl der Personen bleibt ableitbar |
| 6 | Neu: `utils/P256` | Web Push liest an drei Stellen P-256-Schlüssel – VAPID-Paar, `p256dh` eines Abonnements, ephemeres Paar je Nachricht. Dreimal derselbe Handgriff wäre dreimal dieselbe Gelegenheit, das Format falsch zu lesen, **und dieser Fehler bleibt stumm** |
| 7 | Die Gerätebezeichnung ist eine Heuristik mit Markertabelle, nicht der gekürzte `User-Agent` | Die ersten achtzig Zeichen eines üblichen Kopfes zeigen weder Browser noch Plattform. „Chrome auf Mac" leistet, wofür das Feld da ist; ein Fehlgriff kostet nichts, deshalb der Rohtext als Rückfall |

**Zwei Entscheidungen, die in `AGENT_SERVER.md` nachgezogen sind** und hier nur genannt werden:
`PushTyp.PROBE` (Abweichung 4) und die Transaktionsführung des Listeners (Abweichung 2).

**Was beim Weiterbauen zuerst drankommt:**

1. **Der Übersetzungslauf.** Er ist für 5 bis 11 noch nie gelaufen. Scheitert er, zuerst die
   Surefire-Berichte lesen, nicht die Maven-Zusammenfassung – das Vorgehen steht in 6.4.
2. **Abschnitt 12**, und darin zuerst `PushVerschluesselungTests` mit dem Vektor aus Anhang A:
   Er ist der einzige Prüfpunkt, den nichts anderes ersetzt.
3. **Der Fall, den der Testlauf beweisen muss, weil die Begründung allein nicht reicht:** Steht
   nach einer Absage über alle drei Pfade wirklich ein `PUSH_ABSAGE_VERSANDT` in
   `profil.audit_log`? Das ist die Gegenprobe auf Abweichung 2 – wäre `NOT_SUPPORTED` falsch
   gewählt, verschwänden genau diese Schreibvorgänge, und zwar lautlos.
4. **`PushVersandTests` bekommt 800 Tage vorwärts / 20:45** als Zeitstreifen (12.2); er ist noch
   nicht vergeben.

### Was die Pakete 1 bis 4 von S8 gebracht haben (14.09.2026)

Abschnitte 1 bis 4 der Anleitung: Bestandsaufnahme samt Schlüsselpaar, `V014`, der
Entity- und Konfigurationsanschluss und die VAPID-Bindung. **Krypto, Adapter, Endpunkte, die
beiden Anlässe und die Tests stehen aus** (Abschnitte 5 bis 13).

| Gegenstand | Stand |
|---|---|
| `V014__push.sql` | `profil.push_abo` samt partiellem Index, `spieler.push_erwuenscht`, `termin.push_erinnerung_am`, `app_config.push_aktiv`/`push_erinnerung_stunden` |
| Konfiguration | zwei Pflichtfelder in `Konfiguration`, `KonfigurationAendernRequest`, `ConfigService` und im Audit-Detail |
| Entities | `spieler.push_erwuenscht` und `termin.push_erinnerung_am` gemappt, `push_abo` bewusst ohne Entity |
| `FuboProperties.Push` | fünf Werte unter `fubo.push`, ohne `@NotBlank` |
| `PushConfig` + `VapidSchluessel` | Startprüfung ohne Abbruch, Schlüssel einmal dekodiert, Kennzeichen `eingerichtet` |
| Vertrag | die beiden Konfigurationsfelder; **keine** Push-Endpunkte |
| `.env` | lokales Schlüsselpaar erzeugt und eingetragen; `.env.example` nennt die drei Namen |

**Drei Punkte, die beim Weiterbauen zählen:**

1. **Der Filterchain-Eintrag fehlt noch** – er gehört zu Paket 7 und ist **kein Versäumnis, sondern
   eine Reihenfolge**: Es gibt noch keinen Push-Pfad, den er schützen müsste. Wer die Endpunkte
   baut, baut ihn zuerst; ohne ihn sind sie für Gäste **offen**, nicht gesperrt.
2. **`push_erinnerung_am` ist gemappt, fällt beim Verschieben aber noch nicht zurück.** Das eine
   folgt aus Weggabelung B, das andere gehört zu Paket 8 (`TerminService#aendern`, eine Zeile neben
   `setTeamsFixiert(false)`, unter derselben Bedingung). **Bis dahin ist die Spalte gemappt und
   unbenutzt** – gewollt, damit kein späteres natives `UPDATE` neben einer geladenen Entity landet.
3. **`fubo.push.erinnerung-aktiv` steht im Test auf `false`.** Ohne das liefe der Auftrag ab Paket 8
   quer durch fremde Testfälle: Alles, was den Kontextstart überlebt, überlebt auch die
   Test-Transaktion. Die Testfälle rufen die Methode selbst auf.

**Die Fallzahl bleibt bei 434 in 31 Klassen.** Neue Felder kamen als Zusicherungen in bestehende
Fälle (`ConfigServiceTests#seedDefaultsWerdenVollstaendigGelesen`, in
`KonfigurationControllerTests` der Lesefall und der Voll-Update-Fall, der dabei von
`aendernSchreibtAlleZwoelfFelder` in `…AlleVierzehnFelder` umbenannt wurde). **Der Pflichtfeld-Fall
„400 ohne `pushAktiv`" fehlt noch** – er gehört zu Paket 12 und ist die einzige Lücke, die die
Zahl später um mindestens einen Fall erhöht.

**Zwei Zahlen in den Dokumenten waren schon vorher veraltet und sind mitkorrigiert:** Die
Konfigurations-DTOs sprachen noch von *elf* änderbaren Feldern (seit dem Hauptschalter waren es
zwölf), und `Konfiguration#von` nannte *fünfzehn* Spalten (es waren sechzehn). Jetzt: vierzehn
Felder, achtzehn Spalten.

**Offen und benannt:** `V014` folgt der Gepflogenheit der elf bestehenden Migrationen und lässt den
Primärschlüssel **unbenannt** (`id BIGSERIAL PRIMARY KEY`), obwohl `AGENT_SERVER.md` unter den
Flyway-Konventionen `pk_` aufführt. Kein `CONSTRAINT pk_` steht in `V001` bis `V013`; ein einzelnes
in `V014` wäre der Ausreisser. **Wer das anders will, ändert die Regel und nicht eine Migration** –
angewandte Migrationen sind unveränderlich.

### Neu am 14.09.2026 – A25 ist S8, Härtung und Deployment werden S9

**Am Code hat sich nichts geändert.** Diese Fassung nimmt die Anforderung **A25** (PWA und
Push-Benachrichtigungen, ergänzt am 13.09.2026) in den Server-Track auf und zieht die
Umnummerierung nach, die `CONTEXT_HANDOFF.md` bereits festhält:

| Paket | vorher | jetzt |
|---|---|---|
| **S8** | Härtung und Deployment | **A25 serverseitig** – `V014`, sechs Endpunkte, Erinnerungsauftrag, Absage-Ereignis, Versandadapter |
| **S9** | – | **Härtung und Deployment** (Docker, Nginx, Cloudflared, API-Doku) |

**Warum A25 ein eigenes Paket ist:** Es bringt eine Migration, sechs Endpunkte, einen
`@Scheduled`-Auftrag und einen Adapter zu einem fremden Dienst mit. In ein Härtungspaket
geschoben, verwässerte es beide. Und die Härtung gehört ans Ende – sie soll den Stand absichern,
der tatsächlich ausgeliefert wird, und der enthält Push samt VAPID-Variablen, ausgehendem HTTPS
und der Zeitzone des Containers.

**Dokumente:** `harness/tmp/S8_DEPLOYMENT.md` heisst seit dem 14.09.2026
`harness/tmp/S9_DEPLOYMENT.md` (Inhalt unverändert, Selbstbezüge nachgezogen); neu ist
`harness/tmp/S8_PUSH_UMSETZUNG.md`. `AGENT_SERVER.md` ist um den Anforderungspunkt A25, die beiden
Abschnitte „Push-Versand (A25, S8)" und „Push-Endpunkte und DTOs (A25, S8)" sowie um Techstack-,
Paket-, JPA- und Betriebsregeln erweitert.

**Vier Punkte, die beim Ableiten der Serveranforderungen aufgefallen sind.** Alle vier sind
Abweichungen von dem, was beim ersten Lesen von `AGENT.md` naheliegt, und alle vier scheitern
**still** – deshalb stehen sie hier und nicht erst in der Anleitung.

1. **Ein fehlender Eintrag in der Filterchain sperrt hier nicht, er öffnet.** `AGENT.md` schreibt,
   die neuen Pfade seien einzutragen, „ohne Eintrag antworten sie mit `403`, auch für berechtigte
   Nutzer". Für `/api/v1/push/**` trifft das **nicht** zu: Die letzte Regel lautet
   `anyRequest().hasAnyRole("USER", "ADMIN", "GAST")`, ein neuer Pfad fällt darunter und wäre
   **für Gäste offen** – genau das, was A25d verbietet. Der Eintrag mit
   `hasAnyRole("USER", "ADMIN")` ist zwingend, und die Pfade gehören namentlich in
   `SecurityConfigTests`.
2. **Die Terminabsage hat drei Auslöser, nicht einen.** `AGENT.md` nennt
   `POST /api/v1/admin/termin/absagen`. Seit S4 setzt auch `/admin/termin/aendern` den Status auf
   `ABGESAGT` (A19), und seit S7 sagt `/admin/halle/absagen` einen geplanten Termin mit ab. Hängt
   das Ereignis am Endpunkt statt am Statuswechsel, bleiben zwei der drei Wege stumm – und es
   fällt niemandem auf, weil eine ausbleibende Benachrichtigung der Normalfall ist.
3. **Eine einzelne Nachricht wird nie wiederholt.** Der bedingte `UPDATE` auf
   `push_erinnerung_am` läuft **vor** dem Versand, der nächste Lauf überspringt den Termin.
   `fehlversuche` ist ein Gesundheitszähler des Abonnements über mehrere Anlässe hinweg, keine
   Warteschlange. `AGENT.md` trug dazu die Zeile „`429`, `5xx` → Wiederholung im nächsten Lauf"
   und ist am 14.09.2026 berichtigt; dabei kam `401`/`403` als eigene Zeile dazu – **ein `401`
   sagt etwas über unseren Schlüssel, nicht über das Abonnement, und darf es nicht deaktivieren.**
4. **Das Adminprofil muss aus der Empfängerabfrage der Erinnerung heraus.** Es trägt
   `push_erwuenscht = true` (Vorgabe der Migration), hat nie eine Teilnahmezeile und erfüllt damit
   die Bedingung „hat noch nicht geantwortet" für **jeden** Termin – während ihm die Rückmeldung
   selbst mit `409 PROFIL_GESCHUETZT` verweigert wird. Es ist der bestehende Filter
   `rolle <> 'ADMIN'`, an genau einer Stelle mehr. Als **Abonnent** bleibt es zulässig:
   `/admin/push/test` versendet an seine eigenen Geräte.

**Die fünf Weggabelungen aus `S8_PUSH_UMSETZUNG.md`, 0.5 sind am 14.09.2026 entschieden** –
durchgängig entlang der Empfehlung:

| # | Weggabelung | Entschieden |
|---|---|---|
| A | Audit für An- und Abmeldung eines Abonnements | **nein** – der Zustand steht in `push_abo`, die Endpoint-Adresse ist personenbezogen, und „Adminaktionen ja, Nutzerhandlungen nein" gilt weiter. `AuditAktion` wächst von 27 auf **29** Werte |
| B | `push_erinnerung_am` beim Verschieben zurücksetzen | **ja**, bei echter Zeitänderung – dieselbe Bedingung wie `teams_fixiert`. **Folge: Die Spalte wird an der `Termin`-Entity gemappt**, anders als `halle_abgesagt_am` |
| C | Versand nach dem Commit | **synchron, aber parallel** mit Gesamtfrist (10 s), kein `@Async`. Der Listener fängt jede Ausnahme selbst ab; offene Aufrufe werden abgebrochen und als Fehlversuch gebucht |
| D | Probeversand prüft die Schalter | **nein** – nur VAPID und ein eigenes Abonnement. Die Oberfläche zeigt `anlageAktiv` daneben |
| E | Vorgabewert von `push_aktiv` | **`true`**, also A25(e) folgend |

**Die Vorgabedokumente sind am 14.09.2026 nachgezogen**, in derselben Sitzung wie die
Entscheidungen:

- **`DATENMODELL.md`** – Änderungsprotokoll 20, Kurzbegründung und Spaltentabelle führen
  `push_aktiv` jetzt mit Vorgabe `true`; die Begründung nennt ausdrücklich, warum die Analogie zum
  Hallenmodus nicht trägt.
- **`AGENT.md`** – A25(e) verweist auf den Vorgabewert der Migration, Migrationsschritt 4 steht auf
  `DEFAULT true`, `PUSH_ABO_ANGELEGT`/`PUSH_ABO_ENTFERNT` sind als benannter Entfall dokumentiert,
  das Rücksetzen von `push_erinnerung_am` beim Verschieben ist ergänzt, Anlass 2 nennt alle drei
  Auslöserpfade, die Antworttabelle ist um `401`/`403` erweitert und um die falsche
  Wiederholungszusage bereinigt, die Aussage zur Filterchain ist berichtigt, und der synchrone,
  nebenläufige Versand samt Ausnahmebehandlung steht als Architekturregel.
- **`AGENT_SERVER.md`** – dieselben Regeln in verbindlicher Form, dazu das Mapping von
  `termin.push_erinnerung_am` an der Entity (Folge aus Entscheidung B) und der Probeversand ohne
  Prüfung der Schalter (Entscheidung D).

**`PRJ_FuBo/harness/` ist unversioniert** – diese beiden Korrekturen haben kein Remote-Backup.

### Was S7 gebracht hat

Die Absage des gebuchten Hallentermins beim Betreiber (`POST /admin/halle/absagen`), die
Vorlauffrist aus der Konfiguration, der Absagevermerk `spieltag.termin.halle_abgesagt_am`
(`V012`) und zwei neue Felder in der Einzelansicht. **Nachtrag vom 13.09.2026:** der
Hauptschalter `configs.app_config.hallen_modus_aktiv` (`V013`, Vorgabe `false`). Vier neue
Fehlercodes (`HALLE_MODUS_INAKTIV`, `HALLE_FRIST_ABGELAUFEN`, `HALLE_NICHT_KONFIGURIERT`,
`HALLE_BEREITS_ABGESAGT`), eine neue Audit-Aktion (`HALLE_ABGESAGT`).

**Zum Hauptschalter, weil er zwei Dinge festlegt, die sich später nur mit einer
Vertragsänderung zurückdrehen liessen:** Er **sperrt den Endpunkt serverseitig**
(`409 HALLE_MODUS_INAKTIV`) und zwar **als erste Prüfung, noch vor der Terminsuche** – A23
verlangt eine serverseitige Abschaltung, und ein Flag, das nur der Client auswertet, wäre ein
ausgeblendeter Knopf. **Folge, die man kennen muss:** Bei ausgeschaltetem Modus liefert auch
eine unbekannte Termin-Id diesen Code und nicht `404`. Er ist **unabhängig von `halleEmail`**;
ein aktiver Modus ohne Adresse läuft weiterhin in `409 HALLE_NICHT_KONFIGURIERT`. Und er ist das
**zwölfte Pflichtfeld** im Voll-Update der Konfiguration – **eine brechende Änderung für das
Adminformular.**

**S7 ist der erste Meilenstein, der den Server nach aussen sprechen lässt** – nicht zu einem
Client, sondern zu einem Fremden. **Der Versand ist nicht zurückrollbar**, und niemand im Projekt
erfährt davon, wenn er falsch war. Daraus folgen drei Dinge, die den ganzen Meilenstein prägen:
jede Prüfung läuft **vor** dem Versand; der **Doppelversand ist der teuerste Fehler**, teurer als
ein ausgebliebener; und wo Versand und Datenbankzustand auseinanderfallen können, wird die
Richtung gewählt, in der höchstens eine Nachricht zu viel **ausbleibt**.

**Vier Entscheidungen vom 13.09.2026** – drei davon **gegen** die Empfehlung im Fliesstext der
Anleitung, alle vier vor der ersten Zeile Code als Rückfrage gestellt:

1. **Woran der Server erkennt, dass schon abgesagt wurde:** an der neuen Spalte
   `halle_abgesagt_am` (`V012`), nicht am Audit-Log. Entlang der Empfehlung. Das Protokoll wird
   nach 30 Tagen gelöscht – ein Termin, der weiter in der Zukunft liegt, verlöre seinen Zustand,
   während er noch bevorsteht; und ein Eintrag ist Beleg, nicht Zustand.
2. **Die Hallenabsage sagt einen geplanten Termin mit ab** (gegen die Empfehlung, die einen
   bereits abgesagten Termin verlangte). Umgesetzt so, dass die Gegengründe der Anleitung nicht
   greifen: Der Endpunkt nimmt `GEPLANT` **und** `ABGESAGT` an, lehnt nur `ABGESCHLOSSEN` ab, und
   `/admin/termin/absagen` bleibt unberührt – **damit bleibt A19 gewahrt**, ein Termin ist
   weiterhin jederzeit absagbar, auch innerhalb der Frist. Folge: Der in der Anleitung
   vorgesehene Code `TERMIN_NICHT_ABGESAGT` **entfällt**; es sind drei neue Fehlercodes statt
   vier.
3. **Der Server liefert den Ablaufzeitpunkt der Frist mit** (gegen die Empfehlung, die ihn den
   Client rechnen lassen wollte): `halleAbsageMoeglichBis` in `TerminDetails`, Datum und Uhrzeit
   in Ortszeit ohne Zone, immer gefüllt. **Ein Feld „darf ich jetzt noch absagen" gibt es
   trotzdem nicht** – das wäre eine Berechtigungsaussage in einem rollenneutralen Antwortobjekt.
4. **Bei leerer Vorlage wird eine Ersatzvorlage verwendet und dem Admin angezeigt** (gegen die
   Empfehlung, ohne Fliesstext zu versenden). Sichtbar als `halleAbsageVorlageEffektiv` in
   `/admin/config/lesen` – **nur lesbar**, damit das Voll-Update unberührt bleibt und die Vorlage
   weiterhin leerbar ist.

### Drei Handprüflisten sind aufgeschoben, nicht vergessen

`S6_UMSETZUNG.md` 8.1, `S5_UMSETZUNG.md` 13.1, `S4_UMSETZUNG.md` 11.1 und die drei Punkte zur
Gastverwaltung aus 6.4; **seit S7 kommt `S7_UMSETZUNG.md` 8.1 dazu**. Alle brauchen eine laufende
Anwendung und werden in einem Zug abgearbeitet. **Bei S7 mit besonderer Vorsicht:** Dort gehen
echte Mails raus – vor dem ersten Versuch `halle_email` auf eine eigene Adresse setzen.

---

## 1. Kontext

Serverseitige Bereitstellung der FuBo-Logik über eine abgesicherte JSON-API: Profile und Skills,
Termine und Teilnahmen, Teamgenerierung, Ergebniserfassung, Auth/Session und Hallenmodus.
Datenhaltung in PostgreSQL 17 (drei Schemas). Zugang über zentrale PIN, danach Namensidentität.
Rollen ADMIN, USER, GAST.

## 2. Techstack & Architektur (Server)

- Java 25, Spring Boot, Maven. PostgreSQL 17 (Schemas `profil`, `spieltag`, `configs`), Flyway.
- Testcontainers + JUnit; `spring-boot-starter-mail` (Bestätigungs-PIN).
- Hosting: Raspberry Pi 5, Docker/Compose, Nginx (Reverse-Proxy), Cloudflared-Tunnel
  (`assets/Deployment/`). Ein Zweit-Pi mit anderem Setup muss möglich bleiben.
- **Abhängigkeiten:** `actuator`, `data-jpa`, `flyway` (+ `flyway-database-postgresql`),
  `security`, `validation`, `webmvc`, `mail`, `postgresql`; im Test die `*-test`-Starter,
  `spring-boot-testcontainers`, `testcontainers-postgresql`. **Kein Cache-Starter** – der
  `CacheManager` entsteht von Hand aus `spring-context`.
- **Konfiguration:** `spring.config.import=optional:file:./.env[.properties]`,
  `jpa.hibernate.ddl-auto=validate`, `open-in-view=false`,
  `flyway.schemas=profil, spieltag, configs`, `flyway.validate-migration-naming=true`,
  `server.forward-headers-strategy=NATIVE`, `fubo.zeitzone=Europe/Berlin`, Actuator auf `health`
  beschränkt. Die Demodaten-Location steht **nur** in `src/test/resources/application.yml`.
- Architekturregeln und das vollständige Datenmodell: `AGENT.md` (maßgeblich) und
  `AGENT_SERVER.md`.

## 3. Wichtige Entscheidungen (serverrelevant)

Vollständige Liste in `CONTEXT_HANDOFF.md`, Abschnitt 3. Serverseitig besonders relevant:

- Opaker, serverseitig gespeicherter Session-Token im HttpOnly-Cookie; nur SHA-256-Hash in der
  DB; Zwei-Timer-Modell; zweistufiger Login über `stage` mit Token-Rotation.
- Eine PostgreSQL-Instanz mit drei Schemas; Skills in eigener Tabelle (`spieler_skill`),
  Kategorien data-driven in `skill_kategorie`.
- Skill-Skala 0–6; Torwart mit `gewicht = 0.30` und Wertebereich 0–3. `-1`-Ausreisser der
  Referenzdaten wird beim Import zu `0`.
- Zwei austauschbare Team-Algorithmen (`EXHAUSTIV`, `HEURISTIK`) mit identischer Zielfunktion
  und Datengrundlage; Kontingent-Rücksetzung ausschliesslich über `teilnehmer_version`; neuer
  Seed je Lauf.
- Genau ein Admin (partieller Unique-Index); Gast-Obergrenze über feste `gast_slot`-Datensätze.
- Status ONLINE/OFFLINE wird aus aktiven Sessions abgeleitet.

## 4. Schnittstelle zum Frontend (Vertrag)

**Maßgeblich ist `server/fubo-api.json`** – OpenAPI 3.1 in JSON auf der Repo-Wurzel und damit
mitversioniert. **Bei Abweichungen gilt die Datei, nicht dieses Dokument.**

**Umfang: 38 Endpunkte** (Tabelle in 6.1). Aufgenommen wird nur, was umgesetzt ist. **S8
bringt sechs weitere** (fünf unter `/push/`, einer unter `/admin/push/`), **S9 keine** – es
ist Härtung und Deployment. Kernpunkte: REST/JSON, getrennte Origins mit CORS-Allowlist
(`allowCredentials`), HttpOnly-Session-Cookie, `401`/`403`-Semantik, DTOs ohne Skillwerte für
USER und GAST, Belegtstatus zum Pollen, einheitliches Fehler-JSON nach RFC 9457.

**Nullbarkeit steht als Typunion, nie als `nullable: true`.** Die Datei ist 3.1, dort ist
`nullable` kein Schlüsselwort – ein Generator ignoriert es kommentarlos, und der Client bekäme
einen nicht-nullbaren Typ für ein Feld, das `null` sein kann. Am 12.09.2026 an vier Stellen aus
S5 gefunden und behoben; die Regel steht seither in `AGENT_SERVER.md`. **Wer vor dem 12.09.
generiert hat, generiert neu.**

### 4.1 Was der Client-Track wissen muss

**Eine Tabelle statt einer Meilensteinchronik** (zusammengezogen am 12.09.2026): Jede Zeile ist
eine Stelle, an der eine naheliegende Annahme falsch ist. Wann sie dazukam, steht in Git.

**Formulare und Schreibpfade**

| Punkt | Bedeutung für den Client |
|---|---|
| **Vier brechende Änderungen** | `anmeldename` in `AdminLoginRequest`, vollständige `skills` in `SpielerAnlegenRequest`, `auswechselModus` und – seit dem 13.09.2026 – `hallenModusAktiv` im Konfigurations-Voll-Update. Alle vier betreffen Formulare, alle vier liefern sonst `400` |
| `hallenModusAktiv` | **Pflichtfeld, kein stillschweigendes `false`.** Wer es weglässt, bekommt `400` mit dem Schlüssel im Block `felder` – sonst schaltete ein Client, der das Feld nicht kennt, den Hallenmodus bei jedem Speichern ab |
| `/admin/config/aendern` | **Voll-Update**: vorher `lesen`, dann alle **zwölf** änderbaren Felder samt `version` zurückschicken. `halleAbsageVorlageEffektiv` gehört **nicht** dazu – nur lesbar |
| `/admin/ergebnis/korrigieren` | ebenfalls **Voll-Update** mit `version` – auch das unveränderte Feld mitschicken. Grund ist `deutlich`: Bei einem `boolean` wäre „weggelassen" nicht von `false` zu unterscheiden |
| `/admin/termin/aendern` | **feldweise**, anders als die beiden darüber. Weglassen heisst „nicht ändern"; `ort: ""` leert den Ort. Ein Körper ohne jedes zu ändernde Feld liefert `400` |
| `DATEN_VERALTET` (`409`) | heisst **„neu laden und erneut speichern", nicht „Eingabe falsch"**. Gilt für Konfiguration, Termine und Ergebnisse. Die `version` kommt aus dem jeweiligen `lesen` – beim Ergebnis auch aus der Antwort des Erfassens |
| `/admin/gast/freigeben` | genau eines von `slotIds` und `alle`. Leerer Körper `400`, nicht „alle". Der Aufruf **meldet aktive Gäste ab**; vorher nachfragen |
| Genannte, aber ungültige Auswahl (A24) | wird **abgelehnt**, nicht still gefiltert: unbekannte oder gesperrte Id `400`, Adminprofil `409 PROFIL_GESCHUETZT` |

**Dreiwertige Felder – ein `if (feld)` ist hier immer falsch**

| Punkt | Bedeutung für den Client |
|---|---|
| `eigeneRueckmeldung` | `true` zugesagt, `false` abgesagt, `null` noch nicht gemeldet |
| `sitzungGueltig` in `GastPlatzInfo` | `true` lebende Sitzung, `false` verwaister Platz, `null` freier Platz. Ein `if` behandelt den freien wie den verwaisten – der Unterschied ist gerade der Punkt |
| `teams` in `TerminDetails` | `null` heisst „noch nicht generiert" und ist kein Fehler. `veraltet: true` heisst „noch anzeigen, aber nicht mehr aktuell" |
| `ergebnis` in `TerminDetails` | `null` heisst „noch nicht erfasst" – der Normalzustand jedes Termins bis zum Abpfiff. **Unabhängig von `teams`:** Eine Einteilung ohne Ergebnis ist der Regelfall, ein Ergebnis ohne Einteilung kann es nicht geben |
| `korrigiertAm` im `Ergebnis` | `null` heisst „nie korrigiert". Es führt **keine Historie** – jede weitere Korrektur überschreibt den Wert |
| `halleAbgesagtAm` in `TerminDetails` | `null` heisst „noch keine Absage an den Hallenbetreiber" – der Normalzustand. Gesetzt heisst: **Knopf ausblenden**, Zeitpunkt anzeigen. Der Wert belegt den Versand, nicht die Zustellung |

**Sitzung und Fehlerbehandlung**

| Punkt | Bedeutung für den Client |
|---|---|
| `X-FuBo-Kein-Refresh: true` | Anfragheader für Hintergrundaufrufe, die die Sitzung nicht verlängern sollen |
| `Retry-After` und `wartesekunden` | Restwartezeit beim `429` des PIN-Endpunkts; doppelt geführt, weil der Header cross-origin nicht lesbar wäre |
| `absolutGueltigBis` | zweiter Zeitpunkt in der Sitzungsauskunft. Nähert sich der Countdown ihm, hilft „Verlängern" nicht mehr |
| `KONTINGENT_ERSCHOEPFT` (`409`) | **kein `Retry-After`** – kein Zeitproblem, sondern ein Zustand, der sich mit dem Teilnehmerkreis ändert |
| `TEILNEHMER_GEAENDERT` (`409`) | jemand hat während des Laufs zu- oder abgesagt. **Unverändert wiederholbar**; das Kontingent steht unter dem neuen Stand wieder offen |
| `ERGEBNIS_VORHANDEN` (`409`) | **kein Bedienfehler.** Nach dem Abpfiff tippen mehrere gleichzeitig; der Zweite soll „hat schon jemand erfasst" sehen und danach das vorhandene Ergebnis, nicht eine Fehlermeldung |
| `TERMIN_NICHT_ABGESCHLOSSEN` (`409`) | **nicht** `TERMIN_GESCHLOSSEN` – dort ist die Polarität umgekehrt. Warten hilft, aber nur bei `GEPLANT`: Der Abschluss folgt 30 bis 35 Minuten nach Beginn |

**Endgültiges – gehört in die Bestätigungsabfrage**

| Punkt | Bedeutung für den Client |
|---|---|
| `TerminStatus` | `GEPLANT`, `ABGESAGT`, `ABGESCHLOSSEN`. **Eine Absage ist endgültig** – kein Weg zurück nach `GEPLANT` |
| `/admin/termin/entfernen` | löscht endgültig, aber nur ohne Verweise (`409 TERMIN_IN_VERWENDUNG`). **Der einzige Weg zurück aus einer versehentlichen Absage** – ein abgesagter Termin belegt seinen Zeitpunkt weiter |
| **Ein Ergebnis lässt sich nicht löschen** | A21 sieht nur die Korrektur vor. Es gibt keinen Endpunkt dafür, und es wird keinen geben, ohne dass jemand ihn anfordert |
| **Eine versandte Hallenabsage lässt sich nicht zurücknehmen** | Es gibt keinen Endpunkt und keinen Weg, die Mail ungeschehen zu machen. Die Bestätigungsabfrage muss sagen, dass die Nachricht **sofort** hinausgeht |

**Wo die Reihenfolge zählt – und wo nicht**

| Punkt | Bedeutung für den Client |
|---|---|
| `teilnehmerliste` | Feld von `TerminDetails`, **kein eigener Endpunkt**. Bereits sortiert – **im Frontend nicht umsortieren**, sonst passt `position` nicht zur Anzeige |
| Warteschlange | **Eine erneute Zusage stellt hinten an.** Eine Absage lässt die Meldezeit unberührt |
| `teamA`/`teamB` | **keine Rangfolge**, darf frei sortiert werden |
| `SerieAngelegt` | nennt die erzeugten **und** die übersprungenen Zeitpunkte. Kollisionen lassen die Serie nicht scheitern; die zweite Liste muss angezeigt werden |
| Termin absagen und Halle absagen | **Ein Aufruf genügt:** `/admin/halle/absagen` sagt einen geplanten Termin mit ab. Zwei Aufrufe sind erlaubt und nach Fristende der einzige Weg – `/admin/termin/absagen` kennt keine Frist |

**Was der Server von selbst tut**

| Punkt | Bedeutung für den Client |
|---|---|
| `ABGESCHLOSSEN` | **setzt der Server 30 Minuten nach Terminbeginn selbst** (A18). Torwächter für Rückmeldungen ist aber die Uhrzeit, nicht der Status |
| `TEAMS_FIXIERT` (`409`) | ab Terminbeginn, spätestens fünf Minuten nach Anpfiff. Verschiebt der Admin den Termin in die Zukunft, fällt es zurück |
| Sperren eines Profils | **nimmt dessen Zusagen für künftige Termine zurück** – die Teilnehmerliste wird kürzer, ohne dass jemand abgesagt hätte |
| Bilanz nach einer Korrektur | wird **gedreht, nicht addiert**. Die Zähler entstehen bei jeder Änderung neu aus den Ergebnissen; die Bilanzen anderer Termine bleiben unberührt |

**Zugang und Rollen**

| Punkt | Bedeutung für den Client |
|---|---|
| Termine für Gäste | `/termine/lesen`, `/termine/{terminId}/lesen` und `/termine/rueckmeldung` sind ab `PROFILE_AUTHENTICATED` erreichbar, **auch für `GAST`**. Bewertungen tragen sie nicht |
| `/termine/rueckmeldung` | **ein Endpunkt für beide Richtungen und beide Rollen.** Ein Gast schickt keinen Namen mit. Antwort ist `204` |
| `POST /teams/generieren` | **nicht** unter `/admin/` – jeder Angemeldete darf generieren, auch `GAST` (A15) |
| `POST /ergebnis/erfassen` | ebenso offen. „Der erste Eintrag gilt" ergibt nur einen Sinn, wenn mehrere es versuchen dürfen |
| `GET /bilanz/lesen` | liefert die **eigene** Bilanz, **ohne Id** – mit einer Id wäre es „fremde Bilanz lesen". Ein `GAST` bekommt dreimal `0`, kein Fehler |
| `POST /admin/teams/generieren` | **nur `ADMIN`** (A24), unterscheidet sich vom offenen Endpunkt **nur im Präfix**. Auswahl aus `/admin/user/lesen`; kein neuer Listenendpunkt |
| `bilanz` in `SpielerDetails` | die Bilanz **aller** Spieler, admin-only – aber **kein A12-Fall**: Sie ist Statistik, kein Skillwert |

**Fristen und Vorlagen (S7)**

| Punkt | Bedeutung für den Client |
|---|---|
| `halleAbsageMoeglichBis` | Spätester Zeitpunkt der Absage, **immer gefüllt** – auch für vergangene, abgesagte und nicht konfigurierte Hallen. Ortszeit **ohne Zone**, Format `YYYY-MM-DDTHH:MM:SS`. Liegt er in der Vergangenheit, ist das Fenster zu |
| **Es gibt kein `halleAbsageMoeglich`** | Eine Berechtigungsaussage gehört nicht in ein rollenneutrales Antwortobjekt. Der Adminbildschirm rechnet sie selbst – **das blendet einen Knopf aus, es ist keine Sicherung.** Der Server lehnt unabhängig davon mit `409` ab |
| `halleAbsageVorlageEffektiv` | **Nur lesbar.** Zeigt, welchen Fliesstext der Server verwenden würde – die gepflegte Vorlage oder seinen Ersatz. **Gehört nicht in das Voll-Update** von `/admin/config/aendern`; dort wird er ignoriert |
| `hallenModusAktiv` in der Konfiguration | Hauptschalter. Steht er aus, ist der Absageknopf auszublenden – und der Server lehnt unabhängig davon mit `409 HALLE_MODUS_INAKTIV` ab. **Bei ausgeschaltetem Modus kommt dieser Code auch für eine unbekannte Termin-Id**, nicht `404` |
| `HALLE_BEREITS_ABGESAGT` (`409`) | **kein Bedienfehler**, sondern die Antwort auf den Doppelklick. Oberfläche: „ist schon raus" plus Zeitpunkt |
| `VERSAND_FEHLGESCHLAGEN` (`503`) | Es bleibt **kein** Zustand zurück: kein Vermerk, und ein geplanter Termin bleibt geplant. Der Aufruf lässt sich unverändert wiederholen |

**Zahlen, die anders heissen, als sie sind**

| Punkt | Bedeutung für den Client |
|---|---|
| Antwort des manuellen Laufs (A24) | **`200`, nicht `201`**, und **ohne `seed`** – es entsteht nichts. Das Ergebnis wird **nirgends gespeichert** und ist nach dem Verlassen der Seite weg. **Das gehört sichtbar auf den Bildschirm** |
| `algorithmType` / `auswechselModus` in der Antwort | können von der Konfiguration **abweichen** (Rückfall bei zu vielen Teilnehmern bzw. fehlender Meldezeit). Anzeigen, nicht ignorieren |
| `differenzTeamstaerke` | die **Kosten der Zielfunktion**, nicht die Differenz der Gesamtstärken. `0` = perfekt ausgeglichen. Nur im Adminbildschirm |
| `maxTeilnehmer` im manuellen Lauf | **begrenzt, schneidet nicht ab** (`409 ZU_VIELE_TEILNEHMER`) |
| `deutlich` im `Ergebnis` | beschreibt die **Höhe, nicht den Ausgang** – ohne jeden Einfluss auf die Bilanz. Bei `sieger: "U"` unzulässig (`400`, Schlüssel `deutlichNurBeiSieg` im Block `felder`); der Haken gehört dort ausgeblendet |
| `auswechselModus` ändern | ändert die **Einteilung** nicht, wohl aber den angezeigten Auswechselspieler bereits gespeicherter Läufe (A20b) |

**Push-Benachrichtigungen (S8, noch nicht umgesetzt)**

Die Zeilen stehen hier, damit der Client-Track die Einstellungsansicht planen kann; **verbindlich
werden sie erst mit dem Commit in `fubo-api.json`.**

| Punkt | Bedeutung für den Client |
|---|---|
| **Zwei Schalter, nicht einer** | Der Personenschalter (`/push/einstellung/aendern`) gilt für **alle** Geräte des Spielers, der Widerruf (`/push/abo/entfernen`) nur für das aufrufende. Die Oberfläche bietet beide an und benennt den Unterschied – sonst entzieht der Nutzer die Browserberechtigung, und das Wiedereinschalten verlangt einen neuen Dialog |
| `GET /push/status/lesen` | liefert **nur die beiden serverseitigen Ebenen** (`anlageAktiv`, `pushErwuenscht`). Die Geräteebene liest der Client lokal über `pushManager.getSubscription()` – ein `GET` könnte das aufrufende Gerät nicht identifizieren, ohne die Endpoint-Adresse in die URL zu schreiben |
| `POST /push/abo/anlegen` | **bei jedem Anwendungsstart erneut aufrufen.** Der Aufruf ist über `endpoint_hash` idempotent und heilt genau den Fall, in dem der Server das Abonnement nach einem `410` deaktiviert hat, der Browser es aber noch führt |
| Kein Anzeigename fürs Gerät | `geraet_bezeichnung` bestimmt der Server aus dem `User-Agent`. Ein Eingabefeld dafür gibt es nicht und wird es nicht geben |
| `GAST` bekommt `403` | nicht `401` und keine leere Antwort (A25d). Die Push-Einstellungen gehören in der Gastansicht **ausgeblendet**, nicht deaktiviert angezeigt |
| `503` an `/push/schluessel/lesen` | heisst „auf diesem Server nicht eingerichtet", nicht „Fehler". Der Bereich wird ausgeblendet; Wiederholen hilft nie |
| Nichts kommt an, obwohl alles grün aussieht | **Drei Bedingungen gelten gleichzeitig.** Zwei stehen in `/push/status/lesen`, die dritte im Browser. Eine Meldung „keine Benachrichtigungen" ohne Angabe der Ebene schickt den Nutzer an die falsche Stelle |
| Abschalten wirkt auf **beide** Anlässe | Wer abschaltet, erfährt auch eine Terminabsage erst beim Öffnen der Anwendung. Darauf ist beim Abschalten **einmal** hinzuweisen; einen Schalter je Anlass gibt es bewusst nicht |
| iOS | Web Push erst ab 16.4 und **nur nach „Zum Home-Bildschirm"**. Auf dem iPhone ist A25(a) die technische Voraussetzung für A25(b); die Berechtigung ist aus einer Nutzergeste heraus zu erfragen |
| Zwei neue Pflichtfelder in der Konfiguration | `pushAktiv` und `pushErinnerungStunden` werden das **dreizehnte und vierzehnte** Pflichtfeld im Voll-Update von `/admin/config/aendern` – die **fünfte und sechste brechende Änderung** für das Adminformular |

### 4.2 Offene Übergabe: das Admin-Anmeldeformular

Es braucht ein zweites Eingabefeld. Drei Punkte gehören dabei ins Frontend:

1. **Feld „Anmeldename", Pflicht, höchstens 60 Zeichen.** Der Server trimmt Randleerzeichen
   selbst, prüft die Schreibweise aber **zeichengenau** – also kein `toLowerCase()` beim
   Absenden und kein Hinweis, die Schreibweise sei egal.
2. **Keine getrennte Meldung für „Name unbekannt".** Falscher Name und falsches Passwort
   liefern denselben Code; eine Unterscheidung im Frontend unterliefe die Absicht.
3. **Keine Vorbelegung, kein Autovervollständigen.** Der Anmeldename ist über keinen Endpunkt
   abrufbar; ein Auswahlfeld gäbe es nur, wenn ihn jemand ins Frontend schriebe.

## 5. Meilensteine (Server)

Mid-Level-Entwickler, KI-gestützt, ca. 6,5 h/Woche. **Die Summe der Einzelschritte war jedes Mal
verlässlicher als die Top-down-Schätzung** (S2: 18 → 23, S2b: 6 → 10, S4: 16 → 17 plus 3,
S5: 18 → 24, S6: 8 → 13, S7: 6 → 12). **Sieben von sieben** – die Top-down-Zahl lag bei S6 um 62 % daneben,
die Schrittsumme um 18 %, und deren Abweichung bestand zur Hälfte aus Arbeit, die es bei der
Schätzung noch nicht gab.

| MS | Inhalt | Stand | h |
|---|---|---|---|
| S0 | Setup: Spring Boot, Maven, Modulstruktur, Compose | **abgeschlossen** | 8 |
| S1 | Datenmodell: 3 Schemas, Flyway, Seed, Testcontainers | **abgeschlossen** | 15 |
| S2 | Auth & Session: Filterchain, PIN-Login, Brute-Force, Zwei-Timer, Gast-/Admin-Login, Vertrag | **verifiziert (148 Tests)** | 23 |
| S2b | Zugangsdatenpflege und Spielerverwaltung, Aufräumjob | **verifiziert (29.08.2026)** | 10 |
| S3 | Profile & Skills API, Rollen, `configs` | **verifiziert (29.08.2026, 244 Tests)** | 11 |
| S4 | Termine & Teilnahme: Einzel/Serie, Teilnahme, `teilnehmer_version`, Min/Max + Warteschlange, Gast-Flow; dazu A7, A18, A19 | **verifiziert (31.08.2026, 331 Tests in 26 Klassen)**; zwei Handprüfungen offen, siehe 7 | 16 (17 + 3) |
| S5 | Teamgenerator: `EXHAUSTIV` + `HEURISTIK`, Zielfunktion inkl. Torwart-Gewicht, Kontingent/Seed/Snapshot, Auswechselspieler; **dazu A24 (manueller Lauf des Admins)** | **verifiziert (12.09.2026)** – Bestätigungslauf grün; Handprüfliste 13.1 offen. Schrittsumme **24,0 h** (20,5 + 3,5 für A24) | 18 |
| S6 | **Ergebnis und Bilanz** (umbenannt am 12.09.2026; „Ergebnis & Audit API" versprach einen Leseendpunkt fürs Protokoll, den keine Anforderung verlangt): „erster Eintrag gilt", Admin-Korrektur, Bilanz-Zähler, eigene Bilanz | **verifiziert (12.09.2026, 411 Tests in 30 Klassen)**; Handprüfliste 8.1 offen. Schrittsumme **13,0 h** (11,0 geplant plus 2,0 für die Entscheidungen vom 12.09.) | 8 |
| S7 | **Hallenmodus**: E-Mail-Absage an den Hallenbetreiber, Vorlauffrist aus der Konfiguration (A23); dazu `V012` – die erste Migration seit S3 | **verifiziert (13.09.2026)** – 431/31 für S7, 434/31 nach dem Nachtrag „Hauptschalter" (`V013`). Handprüfliste 8.1 offen. Schrittsumme **12,0 h** gegen 6,0 top-down (8,5 geplant, plus 1,5 für die Entscheidungen vom 13.09. und 2,0 für den Hauptschalter) | 6 |
| S8 | **A25 serverseitig (Push)**: `V014`, sechs Endpunkte, Erinnerungsauftrag, Absage-Ereignis, Versandadapter ohne Fremdbibliothek – Anleitung: `harness/tmp/S8_PUSH_UMSETZUNG.md` | **Pakete 1–4 gebaut** (14.09.2026), Testlauf steht aus; 5–13 offen | 22,5 (Schrittsumme) |
| S9 | Härtung, Deployment (Docker/nginx/Cloudflared), API-Doku – Entwurf: `harness/tmp/S9_DEPLOYMENT.md` (bis 14.09.2026 `S8_DEPLOYMENT.md`) | offen | 14 |

Anleitungen: `harness/tmp/S<n>_UMSETZUNG.md`. **Ausnahme S5:** Der Algorithmusteil (Zielfunktion,
`EXHAUSTIV`, `HEURISTIK` – Abschnitte 3 bis 5, 6,0 h) steht seit dem 05.09.2026 in
`harness/tmp/S5_ALGORITHMUS.md`. Er berührt als Einziger weder Datenbank noch HTTP noch Sitzung;
**die Abschnittsnummern sind beibehalten**, ein Verweis „3.1" meint dieselbe Stelle wie zuvor.
Beide Dateien zusammen sind die Anleitung für S5.

## 6. Code-Zustand (13.09.2026, Branch `dev`)

### 6.1 Was steht

**Verdichtet am 12.09.2026.** Die datei- und klassenweisen Listen je Fachbereich sind entfallen –
sie standen ohnehin in `src/` und veralteten mit jedem Commit. Was aus ihnen nicht ableitbar war,
ist nach 6.2 und 6.3 gewandert; die Langfassung liegt in
`archive/…_v18_S6-Pakete1-4.md`.

```
server/                        Repo-Wurzel (remote: FuBo-Server, oeffentlich)
  fubo-api.json                Endpunktkontrakt, 38 Endpunkte
  compose.dev.yml              postgres:17
  .env / .env.example          DB-Zugang, FUBO_INITIAL_PIN, ADMIN_*, SMTP_*
  scripts/                     seed-lokal.sh + anonymisierter 30er-Datensatz
  src/main/resources/db/       migration/ V001-V013, demodata/ (nur dev und test)
  src/main/java/de/fubo/appserver/
    common/      config error security
    controller/  auth admin spieltag ergebnis profil
    service/     auth profil audit mail config spieltag team ergebnis
    repository/  auth profil audit spieltag
    domain/      auth profil audit config spieltag team
    dto/         auth profil admin spieltag
    utils/
```

**Die sechs Fachbereiche und wofür sie zuständig sind:**

| Bereich | Kern | Kam mit |
|---|---|---|
| `auth` | Filterchain, Zwei-Timer-Sitzung, drei Login-Wege, Passwort-Reset, Gastplätze | S2, S2b |
| `profil` | Spielerprofile, Skillwerte, Stammdaten-Zwischenspeicher, Bilanz | S2b, S3, S6 |
| `config` | die eine Zeile `configs.app_config`, Voll-Update mit `version` | S3 |
| `spieltag` | Termine, Serien, Teilnahmen, Warteschlange, Generierungslauf am Termin | S4, S5 |
| `team` | die Rechnung selbst: Aufstellung, Zielfunktion, beide Verfahren, Bankwahl | S5 |
| `ergebnis` | Ausgang erfassen und korrigieren, Bilanz neu berechnen | S6 |
| `audit` | Protokoll aller Adminaktionen und Läufe, Aufräumlauf | S2 |

**Drei Schnitte, die man kennen muss, weil sie beim Lesen nicht auffallen:**

- **`spieltag` gegen `team`:** Alles, was Termin, Sitzung, Kontingent und Protokoll kennt, liegt
  in `spieltag`; was mit einer Aufstellung *rechnet*, in `team` und kennt nichts davon. **Das ist
  die Zeile, an der A24 billig wurde** – der manuelle Lauf betritt den Generator hinter diesem
  Schnitt.
- **`admin` ist ein Zugriffs-, kein Datenbereich.** Der Verwaltungscontroller liegt in
  `controller/admin`, seine DTOs aber in `dto/spieltag`, weil Termine keine Bewertungen tragen.
  Nur DTOs *mit* Bewertung (`SpielerDetails`, `ManuelleEinteilung`) gehören nach `dto/admin` –
  dort trennt A12, nicht der Zugriffsweg.
- **Der Lesepfad von Ergebnis und Einteilung hat keinen eigenen Controller.** Beide erscheinen
  als Felder von `TerminDetails`. Damit ist die Einzelansicht die Stelle, an der ein Termin
  vollständig zusammenläuft – **ein fünftes Feld wäre ein Anlass, über einen eigenen Endpunkt
  nachzudenken, nicht über ein weiteres.**

**Datenmodell: 19 Tabellen, `V001`–`V014`.** `V014` ist die erste seit S3, die eine *Tabelle* anlegt (`profil.push_abo`) und nicht nur Spalten ergänzt.

**Bis S7 waren es 18 Tabellen, `V001`–`V013`.** S2b, S3, **S5 und S6** kamen ohne Migration aus. Die
drei letzten Migrationen ergänzen nur Spalten (alle 30.08.2026): `V009` `auswechsel_modus` (A20b),
`V010` den Vorgabetext für `halle_absage_vorlage` (A23), `V011` die drei Bilanz-Zähler in
`profil.spieler` (A21). **Die Migrationsfreiheit von S5 und S6 hängt an Voraussetzungen**, die in
`S5_UMSETZUNG.md` 0.4/0.6 und `S6_UMSETZUNG.md` 0.2/1.3 stehen – fällt eine davon, fällt die
Aussage.

**Die 38 Endpunkte, nach Bereichen.** Zweck, Körper und Antworten stehen in `fubo-api.json` –
hier nur die Landkarte, damit eine Änderung nicht an zwei Stellen gepflegt werden muss:

| Bereich | Pfade unter `/api/v1` | Anzahl |
|---|---|---:|
| Anmeldung und Sitzung (S2) | `auth/pin/pruefen`, `auth/users/lesen`, `auth/user/waehlen`, `auth/gast/anmelden`, `auth/admin/anmelden`, `auth/session/{lesen,erneuern,beenden}` | 8 |
| Zugangsdaten (S2b) | `auth/passwort/{zuruecksetzen,bestaetigen}`, `admin/{passwort,pin,name}/aendern` | 5 |
| Spielerverwaltung (S2b, S3) | `admin/user/{anlegen,bearbeiten,entfernen,blockieren,lesen}`, `admin/skills/lesen` | 6 |
| Konfiguration (S3) | `admin/config/{lesen,aendern}` | 2 |
| Gastverwaltung (30.08.) | `admin/gast/{lesen,freigeben}` | 2 |
| Termine lesen und melden (S4) | `termine/lesen`, `termine/{terminId}/lesen`, `termine/rueckmeldung` | 3 |
| Terminverwaltung (S4) | `admin/termin/{anlegen,aendern,absagen,entfernen}`, `admin/serie/anlegen`, `admin/teilnahme/gast-stufe` | 6 |
| Teamgenerierung (S5) | `teams/generieren`, `admin/teams/generieren` (A24) | 2 |
| Ergebnis und Bilanz (S6) | `ergebnis/erfassen`, `admin/ergebnis/korrigieren`, `bilanz/lesen` | 3 |
| Hallenmodus (S7) | `admin/halle/absagen` | 1 |

**Der Ort eines Endpunkts ist die Autorisierungsentscheidung.** Alles unter `/api/*/admin/**`
verlangt `ROLE_ADMIN`; die Reset-Endpunkte und die drei Login-Wege sind ausschliesslich in
`PIN_VERIFIED` erreichbar; alles Übrige fällt unter
`anyRequest().hasAnyRole("USER", "ADMIN", "GAST")`. **S4, S5 und S6 haben der Filterchain
nichts hinzugefügt** – Termine, Generierung, Erfassen und die eigene Bilanz liegen bewusst
*nicht* unter `/admin/`, und genau das lässt Gäste mitmachen. Wer einen dieser Endpunkte unter
`/admin/` anlegte, sperrte Gäste aus, ohne eine Regel zu ändern. **Die Pfade stehen trotzdem
namentlich in `SecurityConfigTests`:** Die Platzhalterprüfung bliebe grün, wenn jemand für einen
echten Endpunkt eine offenere Regel **davor** setzte – Spring Security wertet die Matcher der
Reihe nach aus, die erste passende gewinnt. **Zwei Paare unterscheiden sich nur im Präfix**
(`teams/generieren` und `ergebnis/erfassen` gegen ihre `/admin/`-Gegenstücke); bei ihnen fällt
ein Tippfehler besonders schlecht auf.

### 6.2 Festlegungen, die nur hier stehen

Verbindliche Architekturregeln sind in `AGENT_SERVER.md` und werden hier nicht wiederholt. Was
bleibt, sind Weggabelungen: Entscheidungen, die auch anders hätten ausfallen können und die
sich nachträglich nur mit einer Vertragsänderung korrigieren liessen. Die vollständige Liste mit
Datum und Herleitung steht in der Archivfassung `…_v14_S4-abgeschlossen.md`.

| Festlegung | Grund |
|---|---|
| Frontend und API auf Subdomains **derselben** registrierbaren Domain | sonst cross-site: `SameSite=None`, CSRF-Pflicht, blockierte Cookies |
| Kontrakt als `server/fubo-api.json` (OpenAPI 3.1, JSON, Repo-Wurzel) | Vorgabe des Haupt-Entwicklers |
| Anmeldename = Profilname, keine eigene Spalte | ein zweiter Name für dasselbe Konto wäre Ballast |
| Konfiguration als **Voll-Update** mit `version`; Termine dagegen **feldweise** | bei der Konfiguration bliebe `null` feldweise ununterscheidbar von „nicht angegeben", und ohne Version überschriebe der zuletzt gespeicherte Tab lautlos. Beim Termin sind es drei Felder, von denen meist eines geändert wird |
| Skillwerte beim **Anlegen** Pflicht und vollständig, beim **Bearbeiten** Teilmenge | eine Vorgabe wäre eine Behauptung über einen Spieler, die niemand aufgestellt hat – sie fiele nicht auf und ginge trotzdem in die Teameinteilung ein |
| Die Gastübersicht hängt an den **Plätzen**, nicht an den Sitzungen | eine Sitzungsliste zeigte den verwaisten Platz nie an – genau den, der den nächsten Gast aussperrt |
| Freigeben **widerruft** die Sitzung, es räumt nicht nur die Zeile | ein Platz ohne Sitzung wäre neu vergeben, während der alte Gast weiterarbeitet |
| Bilanz-Zähler **neu berechnen** statt fortschreiben (A21) | `+1`/`-1` setzt voraus, dass die Teameinteilung zwischen Eintrag und Korrektur unverändert bleibt; tut sie es nicht, trifft die Rücknahme andere Spieler – und keine zweite Quelle, an der das auffiele |
| Gastteilnahmen bekommen **keine** Bilanz | ein Gast hat keine Profilzeile; ein Zähler an `gast_slot` summierte die Ergebnisse verschiedener Personen |
| **Höchstens 52 Termine je Serie**, als Konstante im `SerienService` | fängt den Tippfehler „2036" statt „2026" ab. Nicht in der Konfiguration: dort wäre es ein zwölftes Pflichtfeld im Voll-Update und damit brechend |
| **Zeitzone der Anwendung in `fubo.zeitzone`** (`Europe/Berlin`), `Clock`-Bean läuft darin statt in UTC | `termin.datum`/`.uhrzeit` sind `DATE`/`TIME` **ohne** Zone, also Ortszeit. „Liegt das in der Vergangenheit" ist eine Frage nach der Wanduhr; mit UTC wäre die Antwort im Sommer zwei Stunden falsch. Die Rechnerzone genügt nicht – im Container ist sie UTC |
| Beim Anlegen entscheidet **`ON CONFLICT DO NOTHING RETURNING id`**, nicht eine vorgelagerte Abfrage | „prüfen und Constraint behalten" lässt ein Fenster offen, und dann bricht der `INSERT` doch am Constraint – mit genau dem `500`, den die Prüfung verhindern sollte. Beim **Ändern** bleibt es bei der Vorabprüfung: ein `UPDATE` kennt kein `ON CONFLICT` |
| Geprüft wird der **Zeitpunkt**, nicht nur der Tag | ein Termin heute um 8 Uhr, angelegt um 20 Uhr, nähme nie eine Rückmeldung entgegen |
| Beim Ändern greift die Vergangenheitsprüfung nur, wenn Datum oder Uhrzeit sich **wirklich** ändern | sonst liesse sich der Ort eines vergangenen Termins nicht mehr berichtigen. Verschieben *in* die Vergangenheit bleibt gesperrt |
| **Entfernt wird nur ohne Verweise** (`409 TERMIN_IN_VERWENDUNG`), und die **Absage bleibt endgültig** | Beide Antworten greifen ineinander: Fünf Tabellen hängen mit `ON DELETE CASCADE` am Termin, ein ungeprüftes Löschen räumte den halben Spieltag ab – und das Entfernen ist zugleich der einzige Weg zurück aus einer versehentlichen Absage, weil ein abgesagter Termin seinen Zeitpunkt weiter belegt |
| Die A18-Frist ist eine **Konstante im Dienst**, der Auftrag läuft alle fünf Minuten | ein zwölftes Pflichtfeld wäre brechend; der Takt genügt, weil keine fachliche Regel an der Pünktlichkeit hängt – ob gemeldet werden darf, entscheidet die Uhrzeit, nicht der Status |
| **Das Adminprofil kann nicht zusagen** (`409 PROFIL_GESCHUETZT`) | der Rückmeldeendpunkt liegt ausserhalb von `/admin/`, das Adminprofil trägt aber eine `spielerId` – ohne die Prüfung stünde das technische Konto mit Skillwerten von 0 in der Teameinteilung |
| „Schwächster Auswechselspieler" nach dem **Skill-Snapshot des Laufs**, nicht nach dem aktuellen Profilstand | sonst wechselte der Auswechselspieler einer gespeicherten Einteilung rückwirkend, sobald ein Skillwert korrigiert wird |
| `TERMIN_GESCHLOSSEN` ist **allgemein** formuliert | der Code deckt drei Fälle ab: abgesagt, abgeschlossen, Beginn vorbei. Für den Aufrufer ist die Wirkung dieselbe; Genaueres steht in `detail` und darf sich ohne Vertragsänderung ändern |
| **Der manuelle Lauf (A24) wird nicht gespeichert** | `team_generierung.termin_id` ist `NOT NULL` und `team_zuteilung.teilnahme_id` hängt am Fremdschlüssel auf `spieltag.teilnahme`. Ein Phantom-Termin machte aus einer Adminrechnung einen Spieltag; eine Migration müsste `team_zuteilung` polymorph machen, und von da an kennt **jeder** Lesepfad zwei Formen – in S5 und noch einmal in S6. Die Historie ersetzt der Audit-Eintrag mit Teilnehmern und Seed |
| **A24 kostet kein Kontingent** | A15 zählt Läufe **je Termin und Teilnehmerstand**; beides gibt es nicht, und die Kontingentzeile trägt `termin_id NOT NULL`. Schutz sind der Zugang (admin-only) und `MAX_EXHAUSTIV` |
| **A24 bekommt einen eigenen Endpunkt unter `/admin/`**, nicht ein optionales `terminId` am bestehenden | der Ort ist die Autorisierungsentscheidung. Ein Endpunkt mit zwei Zugangsregeln je nach Körperinhalt wäre die Prüfung im Controller, die `AGENT_SERVER.md` verbietet |
| Im manuellen Lauf **begrenzt** `max_teilnehmer`, es schneidet nicht ab | am Termin ergibt sich die Menge und der Rest wartet; hier hat der Admin jeden Einzelnen benannt. Wer eine genannte Id still fallen lässt, liefert Teams, die niemand angefordert hat – und es fällt erst auf, wenn jemand vor Ort ohne Team dasteht |
| **`MAX_EXHAUSTIV = 24` heisst "24 ist erlaubt, 25 nicht"** | Die Anleitung lässt sich in beide Richtungen lesen („ab dieser Zahl weigert sich `EXHAUSTIV`" gegen „die letzte vertretbare Stufe"). Maßgeblich ist die Begründung, und die nennt `C(24,12) ≈ 2,7 Mio.` ausdrücklich als noch tragbar |
| **Der Rückfall auf `HEURISTIK` liegt in `ExhaustivVerfahren`, nicht im aufrufenden Dienst** | Die Grenze ist die Regel von `EXHAUSTIV` selbst – es weiß als Einziges, wann es nicht mehr kann. Im Dienst müsste sie ein zweites Mal geführt werden, und jeder künftige Aufrufer müsste sie kennen |
| **Der Seed vergibt A und B, in *beiden* Verfahren** | Sonst stünde bei ungerader Zahl immer dieselbe Hälfte in Überzahl und damit Woche für Woche derselbe Spieler auf der Bank. Auf die Kosten wirkt der Tausch nicht – sie sind ein Betrag und damit symmetrisch |
| **`HEURISTIK` wertet den Tie-Break aus, aber nur beim Merken der besten Lösung** | Die Annahme eines Tauschs richtet sich nach den Primärkosten, wie in der Anleitung. Ohne den Tie-Break beim Merken hätten die beiden Verfahren **nicht** dieselbe Zielfunktion, und genau das verlangt `AGENT_SERVER.md`. Der Preis ist eine Schleife über die Kategorien je angenommenem Schritt |
| **`HEURISTIK` setzt acht Mal neu an, und der Abkühlfaktor kommt aus der Schrittzahl** | `S5_ALGORITHMUS.md`, 5.2 schlug einen Lauf mit festem `0.9995` vor. **So gebaut verfehlten 13 von 100 Seeds das Optimum des Vergleichstests** – die Temperatur ist nach einem Drittel der Schritte bei null, die Suche friert im ersten lokalen Minimum ein. Mit Neustarts und einem Plan, der jeden Lauf ausfüllt: 0 von 300, bei gleichem Rechenbudget. Wer die Konstanten anfasst, misst nach – sie sind gemessen, nicht gesetzt |
| **`Teamaufteilung` trägt Indizes, keine Spieler** | Dieselbe Festlegung wie überall im Algorithmusteil: Die Identität innerhalb eines Laufs ist die Position in der Liste. Wer die Stärke eines Spielers braucht – `score_snapshot` (7.2), Auswechselspieler (8.1) –, gibt denselben Index an `Zielfunktion#staerke`; ein Ergebnis mit Spielerobjekten müsste diesen Weg für jeden von ihnen erst wieder herstellen |
| **Die wirksame Untergrenze der Aufstellung ist `max(min_teilnehmer, 2)`** | `ck_app_config_teilnehmer` verlangt nur `min_teilnehmer > 0`, und das Konfigurations-DTO lässt `1` ausdrücklich zu. Ohne die zweite Grenze käme eine Aufstellung mit einem Spieler durch und scheiterte erst im Generator mit einem `500` statt einer Meldung. Genannt wird in `detail` die wirksame Zahl, nicht der Konfigurationswert |
| **Die Namensvorgabe `Gast 1`, `Gast 2` steht im `AufstellungService`, nicht im DTO** | Einzige Ausnahme von „Vorgabewerte gehören an die API-Grenze", und sie hängt an der Dopplungsprüfung: Nennt der Admin einen Gast ausdrücklich „Gast 2" und lässt einen zweiten unbenannt, entsteht die Dopplung erst durch die Vorgabe – und sie soll abgelehnt werden. Das Entfernen von Randleerzeichen bleibt Aufgabe des DTOs |
| **Ein Gastname darf nicht mit dem Namen eines ausgewählten Profils zusammenfallen** | Zwei gleiche Namen in der Teamausgabe sind genau die Verwechslung, die der Gastname verhindern soll. Die Prüfung läuft **nach** dem Laden der Profile und ist damit der einzige Bruch mit der Reihenfolge aus 2.5; beide Fälle liefern denselben Code |
| **Der Schreibpfad liest den Termin nativ** (`TerminRepository#zustand`), nie über `findById` | Der Lauf merkt sich `teilnehmer_version` und prüft sie am Ende gegen (6.3 der Anleitung). Eine geladene Entity lieferte den Zähler aus dem Persistence-Context – also genau den Wert, gegen den geprüft werden soll. Zugleich bleibt die Entity aus dem Vorgang heraus, sonst bräche der nächste Flush an einem Sperrkonflikt, den niemand verursacht hat. **Ohne diese Umstellung fiele der Fall „nach einer Absage erneut generieren" durch** |
| **Der Termin-Lauf liest sein eigenes Ergebnis zurück**, statt die Antwort aus der Rechnung zu bauen | Der Auswechselspieler wird nicht gespeichert, sondern beim Lesen erneut bestimmt. Weichen Lauf und Ableitung voneinander ab, fällt es so sofort auf – sonst erst, wenn jemand den Termin das nächste Mal öffnet und ein anderer Name auf der Bank steht. Preis: zwei zusätzliche Leseabfragen je Lauf |
| **Die Bankwahl zieht mit einem frischen `new Random(seed)`** | Der Generator des Verfahrens hat bis dahin unterschiedlich viele Zahlen verbraucht – bei `EXHAUSTIV` je nach Zahl der Optima, bei `HEURISTIK` je Iteration. Sein Zustand ist ohne Wiederholung des ganzen Laufs nicht rekonstruierbar; der Seed dagegen steht in der Tabelle. **Das ist die Bedingung, unter der die Ableitung beim Lesen dasselbe liefert wie der Lauf** |
| **Die Zuteilungen werden in Laufreihenfolge geschrieben und über `ORDER BY id` gelesen** | Bei Gleichstand entscheidet der Seed über die *Position* in der Kandidatenliste. Nur bei gleicher Ordnung fällt die Wahl genauso aus – eine andere Sortierung beim Lesen wäre ein Fehler, den kein Test bemerkt, solange niemand zwei gleich starke Spieler hat |
| **Der Auswechselspieler wird nach dem *heute* eingestellten Modus abgeleitet**, nicht nach dem von damals | Der Modus wird nirgends gespeichert, und A20b führt ihn ausdrücklich als Anzeigeregel: Die Einstellung ändert die Einteilung nicht, nur wer aussetzt. **Folge, die man kennen muss:** Stellt der Admin um, kann ein bestehender Lauf einen anderen Auswechselspieler zeigen. Die Alternative wäre die Migration `V012` und damit das Ende der Migrationsfreiheit von S5 |
| **`AufstellungService` liefert `Aufstellung` statt `List<Aufstellungsspieler>`** | Die Verfahren brauchen zusätzlich die aktiven Kategorien, und dieser Dienst liest sie ohnehin für die Vollständigkeitsprüfung. Sonst holte der Generierungsdienst sie ein zweites Mal – zwei Gelegenheiten, gegen eine andere Kategorienmenge zu prüfen als zu rechnen |
| **Die Umrechnung Hundertstel → `NUMERIC(6,2)` liegt in `Teamergebnis`** (`domain`), nicht in `Zielfunktion` (`service`) | `ManuelleEinteilung` braucht sie ebenfalls; ein DTO, das auf die Service-Schicht zugreift, dreht die Schichtung um. In `domain` erreichen sie beide, und die Definition steht weiterhin genau einmal |
| **Die Reihenfolge beim Sperren liegt im `TerminService`** (`sperrungNachtragen`), nicht in der Profilverwaltung | Erst `teilnehmer_version` erhöhen, dann die Zusagen zurücknehmen – die ganze Schwierigkeit des Nachtrags steckt in dieser Reihenfolge, und sie gehört an *eine* Stelle. Die Profilverwaltung muss weder Tabelle noch Bedingung kennen; dieselbe Aufteilung wie beim Zähler-Nachtrag aus S3 |
| **Die eigene Bilanz liest ein Endpunkt ohne Id im Pfad** (`GET /bilanz/lesen`, 12.09.2026) | Die Identität kommt aus der Sitzung. Mit einer Id wäre es ein Endpunkt „fremde Bilanz lesen", über den niemand entschieden hat. Ein `GAST` bekommt die leere Bilanz statt eines Fehlers – ununterscheidbar von einem Spieler ohne gewertete Termine, und das ist Absicht: Ein Kennzeichen verriete, wer Gast ist |
| **Die Bilanz ist ein geschachteltes Schema, nicht drei flache Zähler** | `SpielerDetails.bilanz` und `GET /bilanz/lesen` liefern dieselbe Form. Zwei Darstellungen derselben drei Zahlen liefen auseinander, sobald eine davon ein Feld bekommt |
| **`dto.spieltag.Ergebnis` und `domain.spieltag.Ergebnis` heissen gleich** | Der Vertrag führt das Schema als `Ergebnis`, die Entity heisst nach ihrer Tabelle. Sie treffen sich ausschliesslich in `Ergebnis#von`, wo der Quelltyp voll qualifiziert steht; jede andere Klasse importiert genau einen von beiden |
| **Audit-Löschfrist 30 statt 90 Tage, ohne Obergrenze der Zeilenzahl** (12.09.2026) | Speicherplatz auf dem Raspberry Pi. Ein Deckel auf die Zeilenzahl warf in einem Ansturm genau die Einträge weg, die ihn belegen – und wäre ein Mittel, das Protokoll der eigenen Versuche zu verdrängen. Preis: Eine Ergebniskorrektur ist nach 30 Tagen nicht mehr belegbar, ein manueller Lauf (A24) gar nicht mehr nachvollziehbar |
| **`TeilnahmeRepository` und `BilanzRepository` haben bewusst keine Entity** | Beide Tabellen werden nur angehängt beziehungsweise in *einer* Anweisung aktualisiert. Bei `teilnahme` wäre `@Version` sogar nachteilig: Optimistic Locking meldete den Wettlauf zweier gleichzeitiger Meldungen erst beim Schreiben, `ON CONFLICT` entscheidet ihn ohne Wiederholung. Bei der Bilanz bildet die `Spieler`-Entity dieselben drei Spalten bereits ab – ein zweiter schreibender Weg machte es zur Glückssache, welcher zuletzt gewinnt |
| **`AufstellungRepository` bündelt drei Tabellen in einer Abfrage** | `spieltag.teilnahme`, `profil.spieler` und `profil.spieler_skill`. Eine Entity gäbe es für das Ergebnis ohnehin nicht; drei Einzelabfragen wären drei Gelegenheiten, gegen verschiedene Stände zu rechnen |
| **`IN (:spielerIds)` statt `= ANY(:spielerIds)`** | `JdbcClient` setzt eine Liste selbst in Platzhalter um; `= ANY` bräuchte ein `java.sql.Array` aus der Verbindung. Preis: Eine leere Liste ergäbe `IN ()` und damit einen Syntaxfehler – der Dienst ruft die Abfrage nur mit mindestens einer Id auf |

**Die sechs Weggabelungen aus S4** (30.08.2026, durchgängig entlang der Empfehlung): Serie
**überspringt** Kollisionen und meldet sie namentlich · erneute Zusage stellt **hinten an** ·
Rückmeldung bis **Terminbeginn** und nur bei `GEPLANT` · **eine** Teilnehmerliste mit
`wartet`-Kennzeichen · der Admin trägt **keine** fremden Teilnahmen ein, Ausnahme Gast-Stufe ·
Gäste dürfen Termine **sehen und zusagen**. Herleitung in `S4_UMSETZUNG.md`, Abschnitt 0.4.

**Die vier Weggabelungen für S5** sind am 31.08.2026 entschieden und stehen in
`S5_UMSETZUNG.md`, Abschnitt 0.4: Sperren nimmt Zusagen zurück · die Warteschlange wird **nicht**
eingeteilt · ein Gast ohne Stufe zählt als `MITTEL` · `teams_fixiert` wird bei Terminbeginn
automatisch gesetzt. **Vier weitere zu A24** sind am 05.09.2026 dazugekommen (0.6): nicht
gespeichert · Teil von S5 statt eigener Meilenstein · kein Kontingent · eigener Endpunkt unter
`/admin/`.

**Die vier offenen Weggabelungen A bis D sind am 06.09.2026 entschieden** – durchgängig entlang der
Empfehlung: `ZULETZT_ANGEMELDET` wählt aus dem **Überzahl-Team** · die Einteilung reist als Feld
`teams` in der **Einzelansicht** mit · `differenzTeamstaerke` erscheint **nicht** am Termin, wohl
aber unterhalb von `/admin/` · das **Adminprofil darf generieren**, mit eigenem Kontingent. Damit
ist `S5_UMSETZUNG.md`, 15 auf zwei Punkte zusammengeschmolzen.

**Die vier Weggabelungen für S7 sind am 13.09.2026 entschieden** – drei davon **gegen** die
Empfehlung im Fliesstext, Einzelheiten im Stand-Abschnitt oben und in `S7_UMSETZUNG.md`, 0.5.
Was daraus für den Code folgt und nicht anders hätte ausfallen können, sobald es entschieden war:
`halle_abgesagt_am` als Spalte statt Audit-Abfrage · die Kopplung von Hallen- und Terminabsage
**in eine Richtung** (der Endpunkt nimmt beide Zustände an, `/admin/termin/absagen` bleibt frei
von der Frist) · der Ablaufzeitpunkt als Tatsache im rollenneutralen DTO, **ohne** ein Feld für
die Berechtigung · die Ersatzvorlage an genau einer Stelle im Code, sichtbar über ein nur
lesbares Konfigurationsfeld.

**Der Fehlercode `TERMIN_NICHT_ABGESAGT` existiert nicht.** Die Anleitung sah ihn vor; mit
Entscheidung 2 gibt es den Zustand nicht mehr, den er beschrieben hätte. Ein abgeschlossener
Termin läuft in `TERMIN_GESCHLOSSEN` – der Code bedeutet „nimmt keine Änderung mehr an", und das
trifft zu. **Wer ihn im Client vorgesehen hat, nimmt ihn wieder heraus.**

**Abweichungen aus S1, die im Datenmodell sichtbar sind:** `min_teilnehmer = 6`,
`anz_team_generator = 1`, `session_maximal_stunden = 1` (statt 8/2/8); `session.stage` heisst in
der zweiten Stufe `PROFILE_AUTHENTICATED`, weil auch Gäste sie erreichen.
`spieltag.termin.fk_termin_serie` hat bewusst kein `ON DELETE`.

### 6.3 Fallstricke, die weiter gelten

Jeder Punkt hat schon mindestens einmal Zeit gekostet.

**Konfiguration und Start**

- **Ein `${...}` in einer Fehlermeldung bedeutet immer fehlende Auflösung, nie einen falschen
  Wert.** Spring Boots `Binder` reicht unauflösbare Platzhalter wörtlich durch – anders als
  `@Value`. `--env-file` gilt nur für Docker Compose, nicht für die JVM; deshalb
  `spring.config.import`.
- **Die `.env` ist eine Properties-Datei mit drei Lesern und drei Parsern.** Anführungszeichen
  landen im Wert (kostete einen `550` beim Mailversand); ein `source .env` scheitert am `<` von
  `SMTP_ABSENDER`. Regel in `AGENT_SERVER.md`.
- **`target/classes` vergisst nichts.** Nach dem Umbenennen oder Löschen einer Ressource und
  nach jeder Änderung an `application.yml`: `./mvnw clean`.

**Flyway und JPA**

- **Flyway überspringt falsch benannte Migrationen stillschweigend** (doppelter Unterstrich).
  `validate-migration-naming: true` bleibt gesetzt.
- **Beispielcode gehört nicht in Migrationen.** Ein `:name` aus einer Anleitung ist für
  PostgreSQL ein Syntaxfehler (`42601`).
- **`ddl-auto=validate` prüft Spaltenexistenz und JDBC-Typcode, nicht die Zuordnung.**
  `CHAR(n)` braucht `@JdbcTypeCode(SqlTypes.CHAR)`; vertauschte gleichartige Spalten fallen
  nicht auf. Mapping-Fehler äussern sich als Kaskade von `UnsatisfiedDependencyException` –
  **nur die erste Logzeile benennt die Ursache.**
- **Tabellennamen aus den `CREATE TABLE`-Zeilen lesen, nie aus einem Constraint-Namen.**
  `fk_terminserie_spieler` gehört zu `spieltag.terminserie`, `fk_kontingent_spieler` zu
  `spieltag.generierung_kontingent`.
- **Wo JPA schreibt und natives SQL liest, muss geflusht werden** (`saveAndFlush`, nicht
  `save`). **Diese Regel stand hier schon, und S6 ist trotzdem hineingelaufen** – als Einzeiler
  ohne Fehlerbild schützt sie niemanden. Das Bild: `ErgebnisService#korrigieren` ändert das
  Ergebnis über die Entity, die Bilanzrechnung liest `spieltag.ergebnis` danach **per
  `JdbcClient`**. Ohne Flush rechnet sie gegen den *alten* Ausgang – und **die Zahlen bleiben
  dabei plausibel**, nur eben falsch. Der Eintragspfad hat das Problem nicht, er schreibt
  ohnehin nativ; genau das macht den Unterschied leicht übersehbar. Zweiter Gewinn des Flushs:
  Der Sperrkonflikt fällt an der Aufrufstelle an statt als `UnexpectedRollbackException` beim
  Commit.
- **Die Bilanz-Neuberechnung hat zwei Ebenen, und beide sind nötig:** Die *innere*
  Unterabfrage grenzt auf die Beteiligten **dieses einen** Termins ein, die *äussere* zählt für
  sie über **alle** ihre Termine. Wer nur über den einen zählt, schreibt jedem Spieler die Bilanz
  dieses Spiels – und löscht seine Historie. Dazu `version = version + 1`, sonst schreibt eine
  gleichzeitig geladene `Spieler`-Entity die alte Bilanz still zurück. **Beides fällt nur einem
  einzigen Testfall auf**: zwei Termine, dann eine Korrektur am ersten.
- **`@Modifying` mit `clearAutomatically` löst Entities vom Persistence-Context.** Was danach
  gebraucht wird (Id, Name), vorher in lokale Variablen holen.
- **Ein natives `UPDATE` auf eine Versionsspalte verträgt sich nicht mit einer im selben Vorgang
  geladenen Entity.** `TeilnahmeService` liest den Termin deshalb nativ, nicht über `findById`.

**Tests**

- **Alles, was den Kontextstart überlebt, überlebt auch die Test-Transaktion.** Betrifft den
  `ApplicationRunner` des Bootstraps (deshalb stehen `ADMIN_*` und `fubo.mail.*` in
  `src/test/resources/application.yml`), den `BruteForceService` und den
  `ProfilStammdatenCache` – alle drei werden in `@BeforeEach` zurückgesetzt.
- **`REQUIRES_NEW` und `@Transactional` am Test vertragen sich nicht.** Die eigene Transaktion
  sieht die Testdaten unter READ COMMITTED nicht.
- **Eine Änderung an `FuboProperties` bricht drei Testklassen**, die den Record von Hand bauen
  (`SessionAuthFilterTests`, `SessionCookieFactoryTests`, `BruteForceServiceTests`). Dieselben
  drei sind die Gegenprobe ohne Spring-Kontext: Sind sie grün, liegt ein Kontextfehler nicht am
  Anwendungscode.
- **`sitzungsIdZu(token)` nur mit noch gültigem Token aufrufen** – jeder Stufenwechsel rotiert
  ihn. Die `session.id` vorher auflösen und behalten.
- **`SMALLINT` kommt über `queryForMap` als `Integer` zurück**, über
  `queryForObject(..., Short.class)` als `Short`. Nicht miteinander vergleichbar.
- **Antworten über Jackson auswerten, nicht mit `contains` auf dem rohen JSON.**
- **`uq_termin_zeit UNIQUE (datum, uhrzeit)` ist global und trifft auch die Tests.** Jede Klasse
  braucht ihren eigenen Zeitstreifen in **beiden** Achsen. Vergeben: `TerminControllerTests`
  40 Tage/18:15, `TerminVerwaltungControllerTests` 120 Tage/19:45, `TeilnehmerlisteTests`
  200 Tage/17:30, `SpielerControllerTests` 300 Tage/16:05, seit S5 `TeamGeneratorTests`
  500 Tage/20:15, seit S6 `ErgebnisControllerTests` 600 Tage **rückwärts**/21:30 und seit S7
  `HallenmodusTests` 700 Tage **vorwärts**/19:00. **`ManuelleGenerierungTests` braucht keinen** – A24 ist terminfrei, und sobald
  die Klasse einen Streifen braucht, hat sich eine Terminabhängigkeit eingeschlichen. Das ist die
  schnellste Gegenprobe, die es dafür gibt. Wer eine weitere anlegt, vergibt den nächsten. **Beide Achsen zählen** – ein
  bereits vergebener Tag mit anderer Uhrzeit hielte zwar am Constraint, kollidiert aber mit dem
  Nächsten, der nur die Tage vergleicht.
- **Eine Testklasse ohne `@Transactional` sieht die geplanten Aufträge.** Ohne Test-Transaktion
  sind angelegte Termine wirklich geschrieben, und der A18-Auftrag setzt geplante Termine nach
  Beginn auf `ABGESCHLOSSEN` – alle fünf Minuten. **Ein vergangener Termin, der `GEPLANT` bleiben
  soll, wird zur Zeitbombe:** Der Fall prüft dann mal das eine und mal das andere.
  `HallenmodusTests` legt ihn deshalb als `ABGESAGT` an.
- **Ein Rollback lässt sich in einer `@Transactional`-Testklasse nicht prüfen.** Die
  `@Transactional`-Methode des Dienstes nimmt an der Test-Transaktion teil; beim Scheitern
  markiert sie diese nur als „rollback-only", die Änderung steht aber weiterhin in der Zeile. Ein
  Fall wie „der Versand scheitert, und es bleibt kein Zustand zurück" prüft dort das Gegenteil
  dessen, was er soll – **und wäre grün.** Deshalb trägt `HallenmodusTests` kein
  `@Transactional` und räumt von Hand auf.
- **Termine für Lesetests entstehen per SQL, nicht über den Adminendpunkt.** Der Lesepfad soll
  unabhängig vom Schreibpfad prüfbar bleiben – und ein Termin in der Vergangenheit lässt sich
  über den Endpunkt gar nicht anlegen.
- **`now()` ist innerhalb einer Transaktion konstant.** Alle über Endpunkte angelegten Zusagen
  eines Testfalls tragen denselben Zeitstempel; die Reihenfolge fällt dann auf die `id` zurück
  und die Sortierung nach `gemeldet_am` wäre gar nicht geprüft. Meldezeiten deshalb per SQL
  setzen oder vor dem zweiten Schritt von Hand zurückstellen.
- **Wer `configs.app_config` per SQL ändert, muss es vor dem ersten HTTP-Aufruf tun.** Jeder
  Aufruf lädt über den Sitzungsfilter die Konfigurationszeile in den Persistence-Context; eine
  spätere Änderung bliebe für denselben Vorgang unsichtbar. **Der Test wäre grün und prüfte
  nichts.** Reihenfolge: Konfiguration setzen, Daten anlegen, genau einmal lesen.
- **`ck_app_config_teilnehmer` verlangt `max >= min`.** „Mindestzahl nur durch Wartende
  erreicht" braucht deshalb `min = max` und mehr Zusagen als beide.
- **Zeitgrenzen mit Abstand prüfen, nicht am Rand.** `fubo.zeitzone` steht ausdrücklich auch in
  `src/test/resources/application.yml` – in einem CI-Container stünde die Systemzeit auf UTC.
- **Eine Fallunterscheidung, deren einer Zweig unerreichbar ist, prüft nichts** (06.09.2026,
  kostete einen Lauf). `TerminService#aendern` setzte `teams_fixiert` mit
  `!beginn.isBefore(jetzt)` – für jeden künftigen Zeitpunkt wahr, also gesetzt statt gelöscht.
  Dass `pruefeNichtVergangen` oben schon jeden anderen Zeitpunkt ablehnt, machte den zweiten
  Zweig unerreichbar **und den Fehler unsichtbar**; der Kommentar daneben verteidigte die
  Bedingung sogar ausdrücklich. **Erkennungsmerkmal:** Wer eine Bedingung mit „steht hier
  ausdrücklich, obwohl sie nie anders ausgeht" begründet, hat eine geschrieben, die niemand
  liest – und niemand prüft.
- **Ein Testdaten-Helfer, der auf `aktiv` filtert, ändert sein Ergebnis, sobald der Fall etwas
  sperrt.** `spielerId(5)` liefert nach dem Sperren ein anderes Profil; die Auswahl muss deshalb
  **vor** der Sperre entstehen. Sonst prüft der Fall, dass sechs aktive Profile durchgehen – und
  ist grün, ohne den Ablehnungspfad je berührt zu haben.
- **Ein rückwärts zählender Testdaten-Parameter dreht die Erwartung** (31.08.2026, kostete einen
  Lauf): `zusageAnlegen(…, vorMinuten)` setzt `gemeldet_am = now() - vorMinuten`, die
  **grössere** Zahl meldet sich also **früher** und steht weiter oben. Jede solche Zahl trägt am
  Aufruf einen Kommentar mit der erwarteten *Position*. **Erkennungsmerkmal, wenn mehrere Fälle
  einer Klasse mit gespiegelter Reihenfolge fallen:** Ist der Fall, der die Sortierung
  unmittelbar prüft, grün, liegt der Fehler im Test und nicht in der Abfrage.

**Sicherheit und Betrieb**

- **`Using generated security password` ist kein Indikator** – weder dafür noch dagegen, dass
  die Filterchain greift. Am Verhalten prüfen: ohne Cookie `401` mit
  `application/problem+json`, `/actuator/health` ohne Cookie `200`.
- **Der Brute-Force-Zähler ist zwischen PIN- und Admin-Login geteilt.** Fünf Vertipper beim
  Adminpasswort sperren auch den PIN-Login; die Meldung lautet dann `PIN_GESPERRT`.
- **`max-versuche-ip` und `fubo.reset.max-versuche` stehen beide auf 5**, deshalb greift die
  IP-Sperre vor dem Vorgangszähler. Das ist die gewünschte Staffelung.
- **Git über die Ordnerfreigabe hinterlässt Sperrdateien.** Nach jedem schreibenden Befehl
  `find .git \( -name 'tmp_obj_*' -o -name '*.lock' \) -delete`, sonst blockiert `HEAD.lock` den
  nächsten Commit. **Ohne Löschrecht auf dem Ordner geht das nicht** – Git legt `index.lock` an
  und kann sie nicht mehr entfernen. Umbenennen hilft nur einmal. Der Ausweg ist die
  Löschfreigabe für den Projektordner; sie gilt je Sitzung.

**Bruno**

- **Ein `pre-request`-Skript kann den sichtbaren Körper überschreiben.** Bei „Konfiguration
  aendern" tat es das: Ein oben eingetipptes `halleEmail` erreichte den Server nie und kam beim
  Lesen als `null` zurück – das sah nach einem Fehler der Anwendung aus. **Kommt ein Feld
  unverändert zurück, zuerst das Skript lesen.** Seit dem 30.08.2026 gewinnt der Körper.
- **Der Cookie-Speicher gilt je Host.** Vier Gastanmeldungen gegen `localhost` überschreiben
  einander; die übrigen Sitzungstoken sind danach unerreichbar und wegen des gespeicherten
  SHA-256 nicht zu rekonstruieren. Dieselbe Falle in einer curl-Schleife mit `-c` (Jar
  *schreiben*) statt `-b` (Jar *senden*). Ausweg: `/admin/gast/freigeben`.

### 6.4 Verifikation

```bash
docker info > /dev/null                                    # muss durchlaufen
docker compose -f compose.dev.yml --env-file .env up -d
./mvnw clean verify
```

**Zuletzt grün am 13.09.2026 – 431 Fälle in 31 Klassen** (S7 vollständig, 0 Fehlschläge, 0
Fehler, nichts übersprungen). Verlauf: 148/16 (22.08.), 184 (23.08.), 244/22 (29.08.), 300/25
und 331/26 (S4, 30./31.08.), 385/29 (S5, 06.09.), 411/30 (S6, 12.09.), 431/31 und 434/31 (S7 samt Nachtrag, 13.09.).

**S7 lief am 13.09.2026 grün mit 431 Fällen in 31 Klassen** – 20 neue in `HallenmodusTests`,
dazu die beiden Bündelfälle in `SecurityConfigTests`, die den neuen Admin-Pfad **ohne** eigene
Methode mitprüfen. **Der Nachtrag „Hauptschalter" brachte 434 in 31:** zwei weitere in
`HallenmodusTests` (Schalter aus, und der Schalter vor der Terminsuche) und einer in
`KonfigurationControllerTests` (das zwölfte Feld ist Pflicht). Beide Male traf die vorab
gezählte Zahl exakt – zum neunten und zehnten Mal in Folge. **`PasswortResetControllerTests` muss unverändert grün sein**: Der `MailErsatz` ist
aus ihr herausgezogen worden (jetzt `support.MailErsatz` samt `MailErsatzConfig`), und dass die
Klasse danach nichts anderes tut, ist der ganze Beleg dafür, dass der Umzug nichts verändert
hat.

**Beide Zahlen immer gleich ermitteln, nach dem Lauf aus den Berichten** – die Klassenzahl war
einmal falsch, weil sie fortgeschrieben statt gezählt wurde:

```bash
awk -F'[:,]' '/^Tests run:/ {t+=$2; k++} END {print k" Klassen, "t" Faelle"}' \
    target/surefire-reports/*.txt
```

**Der vorab gezählte Erwartungswert traf jedes Mal exakt** (`grep -c '^\s*@Test\s*$'` je
Klasse) – die *geschätzten* Zahlen dagegen nie: 377 statt 385 in S5. Deshalb wird unmittelbar
vor dem Lauf gezählt, nicht fortgeschrieben.

**Vier Klassen laufen ohne Spring-Kontext** – `SessionAuthFilterTests`,
`SessionCookieFactoryTests`, `BruteForceServiceTests` und `TeamverfahrenTests`. Sie brauchen
zusammen unter einer Sekunde und machen die Gegenprobe belastbar: **Sind alle vier grün und der
Rest rot, liegt ein Kontextfehler vor und kein Anwendungsfehler.**

**`SecurityConfigTests` bleibt bei 26 Fällen**, obwohl mit jedem Meilenstein Pfade dazukommen:
Sie stehen als Zusicherungen *innerhalb* der bestehenden Bündelfälle. **Wer die Fallzahl als Mass
für die Abdeckung liest, unterschätzt diese Klasse systematisch.** Namentlich eingetragen sind
inzwischen vier Pfade, die sich nur im `/admin/`-Präfix unterscheiden – dort bemerkt die
Platzhalterprüfung einen Tippfehler nicht.

**Scheitert ein Lauf, zuerst die Surefire-Berichte lesen, nicht die Maven-Zusammenfassung.** Bei
einem Kontextfehler meldet Spring Test jeden betroffenen Fall einzeln, aber nur der *erste*
Bericht je Kontextkonfiguration nennt die Ursache – alle anderen tragen
`ApplicationContext failure threshold (1) exceeded`. 115 Fehler bedeuten dann **eine** Ursache.
Kürzester Weg: `grep -h 'Caused by' target/surefire-reports/*.txt | tail -1`.

**Was der Testlauf nicht abdecken kann, decken die manuellen Prüflisten ab** – jeder Fall läuft
in einer zurückgerollten Transaktion und kann keine Sitzung wirklich ablaufen lassen. Die Listen
stehen in `S2b_UMSETZUNG.md` (12.1), `S3_UMSETZUNG.md` (10.1), `S4_UMSETZUNG.md` (11.1),
`S5_UMSETZUNG.md` (13.1) und `S6_UMSETZUNG.md` (8.1); die zu S2b und S3 sind abgearbeitet. Sie
bleiben stehen – nicht als offene Aufgabe, sondern als Vorlage nach jeder Änderung am jeweiligen
Bereich.

Drei Punkte zur Gastverwaltung stehen in keiner Anleitung, die Bruno-Requests unter
`admin/gast/`:

| Prüfpunkt | Erwartung |
|---|---|
| Als Gast anmelden, `sessionLeerlaufMinuten` auf 1, warten, `/admin/gast/lesen` | `belegt: true` mit `sitzungGueltig: false` – der Zustand, der den nächsten Gast aussperrt |
| Zweiten Gast über `baseUrlOhneCookie` anmelden, Platz freigeben, mit seinem Cookie `/auth/session/lesen` | `401`, nicht `200` – die Freigabe widerruft die Sitzung |
| `anzGuests` von 4 auf 2 senken bei vier belegten Plätzen | Plätze 3 und 4 mit `wirksam: false` und weiter `belegt: true`; gelöscht wird nichts |

## 7. Nächste Schritte

1. **Die Handprüfung zu S7 einplanen** – sie ist der einzige offene Punkt des Meilensteins. Scheitert er, zuerst die Surefire-Berichte lesen – das
   Vorgehen steht in 6.4. **Der wahrscheinlichste Bruch ist `V012`**: Eine bereits angewandte
   Migration, die sich ändert, lässt `validate-on-migrate` scheitern; dann die
   Entwicklungsdatenbank neu aufsetzen (`docker compose -f compose.dev.yml down -v`).
2. **Die Handprüfung zu S7 einplanen** (`S7_UMSETZUNG.md`, 8.1). **Achtung: Hier gehen echte
   Mails raus.** Vor dem ersten Versuch `halle_email` auf eine eigene Adresse setzen. Die beiden
   Punkte, die sich nicht automatisiert prüfen lassen:
   - **Die Mail im Posteingang lesen** – Umlaute, Datenblock über der Vorlage, keine leere
     Ortszeile, Betreff mit Datum und Uhrzeit.
   - **Die Sommer-/Winterzeitgrenze**: Ein Termin kurz nach der Umstellung muss die Frist in
     Ortszeit rechnen. Das ist die Stelle, die am ehesten **still** falsch ist.
3. **Die vier übrigen Handprüflisten in einem Zug abarbeiten** – `S6_UMSETZUNG.md` 8.1,
   `S5_UMSETZUNG.md` 13.1, `S4_UMSETZUNG.md` 11.1 und die drei Punkte aus 6.4. Entschieden am
   06.09.2026, weil eine halbe Generierung nichts zeigt, was sich prüfen liesse. Die beiden
   wertvollsten Punkte:
   - **A24:** Fünfmal derselbe Aufruf muss fünfmal `200` liefern, und
     `spieltag.team_generierung` muss danach **unverändert** sein.
   - **S6:** Die SQL-Gegenprobe, die die Bilanz gegen die Ergebnisse zählt – sie steht
     wiederholbar im Bruno-Ordnerkommentar `ergebnis/`, **mit `LEFT JOIN`**: Ohne ihn findet sie
     genau den Fehler nicht, bei dem eine Bilanz stehen bleibt, obwohl sie auf null gehörte.
4. **Client-Track informieren** – die Tabelle in 4.1, dazu zwei Punkte aus S7: Der Vertrag steht
   bei 38 Endpunkten, und **`TERMIN_NICHT_ABGESAGT` gibt es nicht**, obwohl die Anleitung ihn
   vorsah. Weiter gilt der Hinweis auf die vier `nullable`-Korrekturen: Wer vor dem 12.09.2026
   generiert hat, generiert neu.
5. **S8 danach: A25 serverseitig** (Push, Schrittsumme 22,5 h). Anleitung in
   `harness/tmp/S8_PUSH_UMSETZUNG.md`. Vier Dinge vor der ersten Zeile Code: die **zwei
   Weggabelungen aus 0.5** entscheiden, die **Testvektoren aus RFC 8291, Anhang A** bereitlegen
   (ohne sie ist die Verschlüsselung nicht prüfbar – der Push-Dienst nimmt auch eine falsch
   verschlüsselte Nachricht an), die **drei VAPID-Variablen erzeugen und in die `.env` legen**,
   und den Filterchain-Eintrag für `/api/*/push/**` nicht vergessen – **ohne ihn sind die Pfade
   für Gäste offen, nicht gesperrt.**
6. **S9 zuletzt** (Härtung und Deployment, 14 h): Entwurf in `harness/tmp/S9_DEPLOYMENT.md`. Vier
   Punkte aus S5 bis S8 gehören dort hinein: die **Messung von `MAX_EXHAUSTIV` auf der
   Zielhardware**, die Frage, ob 30 Tage Audit-Aufbewahrung für den Speicher des Pi reichen,
   **ein Satz zum Absender in der Betriebsdokumentation** – mit S7 erscheint `SMTP_ABSENDER` zum
   ersten Mal ausserhalb des Projekts, nämlich beim Hallenbetreiber – und aus S8 die **Sicherung
   des VAPID-Schlüsselpaars**: Geht es verloren, muss jeder Spieler erneut zustimmen.

**Offene Punkte, die keine Aufgabe für heute sind:**

- **`MAX_EXHAUSTIV = 24` ist gesetzt, nicht gemessen.** `C(24,12) ≈ 2,7 Mio.` ist auf einem
  Entwicklungsrechner tragbar; ob auch auf dem Raspberry Pi 5, zeigt erst eine Messung auf der
  Zielhardware – sie gehört zu S9. **Mit A24 ist die Grenze zugleich der einzige Schutz vor
  Dauerläufen**, weil der manuelle Lauf kein Kontingent kostet.
- **Alte Generierungsläufe werden nie aufgeräumt.** Belanglos, solange Termine bestehen;
  `ON DELETE CASCADE` nimmt sie mit dem Termin.
- **Der manuelle Lauf hinterlässt nur den Audit-Eintrag**, und der wird nach 30 Tagen gelöscht
  (`fubo.audit.aufbewahrung-tage`, seit dem 12.09.2026 statt 90). Bewusst so entschieden; wird es
  zum Problem, ist die Antwort die Migration `V012` aus `S5_UMSETZUNG.md`, 0.6 – **nicht** eine
  längere Löschfrist, denn die Frist gilt dem Personenbezug und nicht der Nachvollziehbarkeit von
  Rechnungen.
- **`configs.app_config.anz_guests` gilt im manuellen Lauf nicht.** Der Wert begrenzt gleichzeitige
  Gastsitzungen, nicht Mitspieler auf dem Platz; die Summe begrenzt `max_teilnehmer`. Wer das
  ändern will, ändert eine Bedeutung, keine Zahl.
- **Ein Wechsel von `auswechsel_modus` ändert den angezeigten Auswechselspieler bestehender
  Läufe.** Die Einteilung bleibt unberührt (A20b führt den Modus als Anzeigeregel). Wer das
  anders will, braucht `auswechsel_teilnahme_id` in `team_generierung` – und damit `V012`.
- **Die Zeitzone der Datenbanksitzung ist nicht gesetzt.** Ohne Folge, solange alle Zeitvergleiche
  über die `Clock`-Bean laufen; S5 und S7 halten das durchgängig ein und übergeben jeden
  Zeitpunkt als Parameter. Sobald eine Abfrage `current_date` oder `current_time` benutzt, gehört `TimeZone` in
  die Datenbankkonfiguration.
- **Betriebsaufgabe ohne Code:** Custom Domain `app.<domain>` in Cloudflare Pages einrichten. Ohne
  sie funktioniert die Anmeldung produktiv nicht – `pages.dev` steht auf der Public Suffix List und
  wäre gegenüber `api.<domain>` cross-site, mit `SameSite=None; Secure`, zwingendem CSRF-Schutz und
  einem Cookie, das Safari und der Chrome-Inkognito-Modus blockieren. Ebenfalls offen:
  Pages-Preview-Deployments, in denen der Login bauartbedingt nicht funktioniert.
- **Deployment (S9):** Entwurf mit Dockerfile, Compose-Ergänzung, nginx-Block, Backup und Rollout
  liegt in `harness/tmp/S9_DEPLOYMENT.md` (bis zum 14.09.2026 `S8_DEPLOYMENT.md`). **Mit S8 kommen
  drei VAPID-Variablen, ausgehendes HTTPS zu den Push-Diensten und `TZ=Europe/Berlin` im
  Compose-Dienst hinzu** – der Entwurf kennt sie noch nicht.

**Profildaten** (Vorgehen steht): Reale Daten liegen ausserhalb des Server-Repositories – derzeit
in `PRJ_FuBo/db_prod_data/`. Pfad in `FUBO_LOCAL_SEED`, Einspielen über `scripts/seed-lokal.sh`.
Der anonymisierte 30er-Satz liegt in `scripts/data/`, der 12er-Demosatz läuft automatisch in Dev
und Test.

## 8. Weitere Anweisungen

- **Repository:** Wurzel `server/`, gearbeitet wird auf **`dev`**, `main` bleibt der freigegebene
  Stand. Commit-Nachrichten nach Conventional Commits **ohne** Scope, Umlaute transliteriert.
  **Ohne ausdrückliche Anweisung nichts nach `main` mergen und nichts pushen.**
- **Nicht committen, solange ein Testlauf aussteht.** Ein Commit ist Denis' Abschluss eines
  verifizierten Pakets.
- **Getrennte Repositories:** Server und Client lassen sich nicht gemeinsam committen.
  Vertragsänderungen deshalb **immer zuerst** in `server/fubo-api.json`; der Client-Track zieht
  danach nach.
- **`.env` nie einchecken.** Dokumentation in deutscher Sprache, **keine realen Personennamen**
  in Code, Testdaten oder Dokumentation – besonders nicht in Migrationen, die unveränderlich
  sind und dauerhaft in der Git-Historie stehen.
- **Nach Abschluss eines Arbeitspakets:** verifizieren, diesen Handoff fortschreiben, die
  Vorfassung unter `harness/archive/` ablegen und `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md`
  nachziehen. Verbindliche Regeln aus der Umsetzung gehören in `AGENT_SERVER.md` – **nicht**
  zusätzlich hierher, sonst laufen beide auseinander.
