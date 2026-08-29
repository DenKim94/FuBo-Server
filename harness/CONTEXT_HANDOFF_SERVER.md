# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md`
> (Gesamtstand) um den serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`.
> Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.
>
> **Repository:** eigenständig mit Wurzel in `server/` (`FuBo-Server`, GitHub, privat).
> **Kein Monorepo** – das Frontend liegt getrennt (`FuBo-Client`). `PRJ_FuBo/` und
> `PRJ_FuBo/harness/` sind bewusst **nicht** versioniert. Gearbeitet wird auf `dev`.

## Stand: 29.08.2026

**Abgeschlossen und verifiziert: S0, S1, S2, S2b und S3 (Pakete 0 bis 4).**
`./mvnw clean verify` grün – **227 Tests in 21 Klassen**, keine Fehler, keine Abbrüche, keine
übersprungenen Tests; die Anwendung startet auf einer frischen Datenbank durch. Der Lauf
braucht Docker (Testcontainers, `postgres:17`) und läuft ausschliesslich lokal.

**Offen aus S3: die Pakete 5 bis 7** – Admin-Konfiguration lesen und ändern, Gastplätze
mitpflegen (offener Punkt 18 aus S2), Bestandsaufnahme der Autorisierung. Der Fehlercode
`DATEN_VERALTET` und der Tag „Konfiguration" gehören dorthin und fehlen deshalb noch im
Vertrag; er steht bei **19 Endpunkten** und wächst dann auf 21.

**Als Nächstes:** die manuelle Prüfliste (`harness/tmp/S2b_UMSETZUNG.md`, Abschnitt 12.1),
danach S3 mit den Paketen 5 bis 7. Vor dem nächsten Bruno-Lauf `adminName` und
`neuerAdminName` in den Umgebungen füllen – beim Anmeldenamen zählt die Schreibweise.

> **Zur Fassung:** Dieses Dokument ist am 29.08.2026 von 836 auf rund 450 Zeilen eingedampft
> worden. Entscheidungen, die inzwischen **verbindliche Regeln** sind, stehen nur noch in
> `AGENT_SERVER.md` und werden hier nicht wiederholt; die Schritt-für-Schritt-Erzählung der
> abgeschlossenen Meilensteine lebt in `harness/archive/` und in den `S<n>_UMSETZUNG.md`
> weiter. Zuletzt archiviert:
> `CONTEXT_HANDOFF_SERVER_2026-08-29_v9_S3-Pakete0-4.md`.

---

## 1. Kontext
Serverseitige Bereitstellung der FuBo-Logik über eine abgesicherte JSON-API: Profile und Skills, Termine
und Teilnahmen, Teamgenerierung, Ergebniserfassung, Auth/Session und Hallenmodus. Datenhaltung in
PostgreSQL 17 (drei Schemas). Zugang über zentrale PIN, danach Namensidentität. Rollen ADMIN, USER, GAST.

## 2. Techstack & Architektur (Server)
- Java 25, Spring Boot, Maven. PostgreSQL 17 (Schemas `profil`, `spieltag`, `configs`), Flyway.
- Testcontainers + JUnit; `spring-boot-starter-mail` (Bestätigungs-PIN).
- Hosting: Raspberry Pi 5, Docker/Docker-Compose, Nginx (Reverse-Proxy), Cloudflared-Tunnel
  (`assets/Deployment/`). Zweit-Pi mit anderem Setup muss ebenfalls möglich sein.
- Architekturregeln und das vollständige Datenmodell: `/PRJ_FuBo/harness/AGENT.md` (maßgeblich) und `AGENT_SERVER.md`.

## 3. Wichtige Entscheidungen (serverrelevant)
Vollständige Liste in `CONTEXT_HANDOFF.md`, Abschnitt 3. Serverseitig besonders relevant:
- Opaker, serverseitig gespeicherter Session-Token im HttpOnly-Cookie; nur SHA-256-Hash in der DB;
  Zwei-Timer-Modell; zweistufiger Login über `stage` mit Token-Rotation.
- Eine PostgreSQL-Instanz mit drei Schemas; Skills in eigener Tabelle (`spieler_skill`), Kategorien
  data-driven in `skill_kategorie`.
- Skill-Skala 0–6; Torwart mit `gewicht = 0.30` und Wertebereich 0–3 (fließt leicht in die Balance ein,
  dominiert die Feldbalance aber nicht). `-1`-Ausreißer der Referenzdaten wird beim Import zu `0`.
- Zwei austauschbare Team-Algorithmen (`EXHAUSTIV`, `HEURISTIK`) mit identischer Zielfunktion und
  Datengrundlage; Kontingent-Rücksetzung ausschließlich über `teilnehmer_version`; neuer Seed je Lauf.
- Genau ein Admin (partieller Unique-Index); Gast-Obergrenze über feste `gast_slot`-Datensätze.
- Status ONLINE/OFFLINE wird aus aktiven Sessions abgeleitet.

## 4. Schnittstelle zum Frontend (Vertrag)

**Der maßgebliche Endpunktkontrakt liegt seit dem 22.08.2026 als `server/fubo-api.json` vor** –
OpenAPI 3.1 in JSON, auf der Wurzel des Server-Repositories und damit mitversioniert.

**Ablageort (Entscheidung des Haupt-Entwicklers, 22.08.2026):** Repo-Wurzel `server/`, nicht
`src/main/resources/openapi/` wie ursprünglich in der S2-Anleitung vorgesehen und nicht der
übergeordnete Ordner `PRJ_FuBo/`. Begründung: Der Vertrag ist kein Anwendungsartefakt, das zur
Laufzeit gelesen wird – er gehört dorthin, wo ihn jemand sucht, der das Repository öffnet. Und
`PRJ_FuBo/` ist bewusst unversioniert; dort hätte der Vertrag keine Historie und wäre für den
Client-Track nur lokal sichtbar. Da Server und Client in getrennten Repositories liegen, ist ein
gemeinsamer Commit ohnehin unmöglich: Jede Vertragsänderung wird **zuerst** in dieser Datei
abgebildet, der Client-Track zieht danach nach.

**Format JSON statt YAML** – ebenfalls Vorgabe des Haupt-Entwicklers. Praktischer Nebeneffekt: Die
Datei lässt sich ohne Zusatzwerkzeug maschinell prüfen, und die üblichen
TypeScript-Generatoren des Client-Tracks lesen sie unverändert.

**Umfang:** ausschliesslich das, was tatsächlich umgesetzt ist – **19 Endpunkte**: die acht
Auth- und Sitzungsendpunkte aus S2, die sieben aus S2b (Zugangsdatenpflege und
Spielerverwaltung) und seit dem 29.08.2026 die vier aus den S3-Paketen 2 bis 4
(Abschnitt 6.1). Spekulative Endpunkte wären ein Vertrag über etwas, das es nicht gibt;
S3 bis S6 tragen ihre Endpunkte jeweils bei Fertigstellung nach.

Inhaltliche Kernpunkte (unverändert, Herleitung in `AGENT_SERVER.md`, Abschnitt „Schnittstelle zum
Frontend"): REST/JSON, getrennte Origins mit CORS-Allowlist (`allowCredentials`),
HttpOnly-Session-Cookie, `401`/`403`-Semantik, DTOs ohne Skillwerte für USER/GAST,
Belegtstatus-Endpunkt zum Pollen, einheitliches Fehler-JSON nach RFC 9457.

Neu im Vertrag seit dem 22.08.2026:
- **`X-FuBo-Kein-Refresh: true`** als Anfrageheader für Hintergrundaufrufe.
- **`Retry-After`** und das Feld `wartesekunden` beim `429` des PIN-Endpunkts.
- **`absolutGueltigBis`** in der Sitzungsauskunft, zusätzlich zu `gueltigBis`.

Geändert am 29.08.2026:
- **`AdminLoginRequest` hat ein zweites Pflichtfeld `anmeldename`** (max. 60 Zeichen,
  zeichengenau geprüft). Das ist
  eine **brechende** Vertragsänderung für den Client-Track: Ein Anfragekörper mit nur
  `passwort` liefert jetzt `400 EINGABE_UNGUELTIG`. Kein neuer Fehlercode, kein neuer
  Endpunkt; die Fehlercodeliste bleibt unverändert.

## 4a. Offene Übergabe an den Client-Track

Das Anmeldeformular des Admins braucht ein zweites Eingabefeld. Der Client-Track zieht nach,
sobald `fubo-api.json` bei ihm angekommen ist. Drei Punkte gehören dabei ins Frontend:

1. **Ein Feld „Anmeldename", Pflicht, maximal 60 Zeichen.** Der Server trimmt Randleerzeichen
   selbst – das muss das Frontend nicht nachbauen –, prüft die **Schreibweise aber
   zeichengenau**. Also kein `toLowerCase()` beim Absenden und kein Hinweis, die
   Schreibweise sei egal.
2. **Keine getrennte Fehlermeldung für „Name unbekannt".** Der Server liefert für falschen
   Namen und falsches Passwort denselben Code; eine Unterscheidung im Frontend hätte nichts,
   woran sie sich festmachen könnte, und würde die Absicht unterlaufen.
3. **Keine Vorbelegung, kein Autovervollständigen aus einer Liste.** Der Anmeldename ist über
   keinen Endpunkt abrufbar; ein Auswahlfeld gäbe es nur, wenn ihn jemand ins Frontend
   schriebe.

## 5. Meilensteine & Aufwandsschätzung (Server)
Mid-Level-Entwickler, KI-gestützt, ca. 6,5 h/Woche.

| MS | Inhalt                                                                                                                                                                                                                                                                                                                          | Aufwand (h) |
|---|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---|
| S0 | Backend-Setup: Spring Boot, Maven, Modulstruktur, `.gitignore`, Docker/Compose-Eintrag – **abgeschlossen**                                                                                                                                                                                                                      | 8 |
| S1 | Datenmodell: 3 Schemas, alle Tabellen/Constraints, Flyway-Migrationen, Seed (Kategorien, `gast_vorlage`, anonymisierte Beispielprofile), lokale Datenversorgung, Testcontainers-Grundgerüst – **abgeschlossen**                                                                                                                 | 15 |
| S2 | Auth & Session: Security-Filterchain, PIN-Login + Brute-Force-Schutz, `stage`-Erzwingung, opaker Token/HttpOnly, Zwei-Timer-Modell, Namensliste/-belegung, Gast-Login, Admin-Login, Bootstrap, Sitzungsendpunkte, Online-Status, API-Vertrag – **abgeschlossen und verifiziert (148 Tests)**, Anleitung `harness/tmp/S2_UMSETZUNG.md` | 23 |
| S2b | Zugangsdatenpflege und Spielerverwaltung: Passwort-Reset per E-Mail (5-stellige PIN, Rate-Limit, Sitzungswiderruf), Passwortänderung im angemeldeten Zustand, Änderung der zentralen PIN, Anlegen/Entfernen/Sperren von Spielerprofilen, Aufräumjob – **abgeschlossen und verifiziert (29.08.2026)**. Anleitung `harness/tmp/S2b_UMSETZUNG.md` | 10 |
| S3 | Profile & Skills API: Admin-CRUD, Rollen/Autorisierung, `configs` (Import der Referenzdaten entfällt – siehe Abschnitt 7.3)                                                                                                                                                                                                     | 11 |
| S4 | Termine & Teilnahme API: Einzel/Serie, Teilnahme, `teilnehmer_version`, Min/Max + Warteschlange, Gast-Flow/`gast_slot`                                                                                                                                                                                                          | 16 |
| S5 | Teamgenerator: `EXHAUSTIV` + `HEURISTIK`, Zielfunktion inkl. Torwart-Gewicht, Kontingent/Seed/Snapshot, Auswechselspieler, Tests                                                                                                                                                                                                | 18 |
| S6 | Ergebnis & Audit API: „erster Eintrag gilt", Admin-Korrektur, `audit_log`                                                                                                                                                                                                                                                       | 8 |
| S7 | Hallenmodus: E-Mail-Absage, 48-Stunden-Regel, serverseitige Deaktivierung                                                                                                                                                                                                                                                       | 6 |
| S8 | Härtung, Integrationstests, Deployment (Docker/nginx/Cloudflared), API-Doku/OpenAPI – Entwurf liegt vor: `harness/tmp/S8_DEPLOYMENT.md`                                                                                                                                                                                         | 14 |

**Korrektur der S2-Schätzung (08.08.2026):** Die Aufschlüsselung in `S2_UMSETZUNG.md` summiert sich auf
**23 h** statt der ursprünglich veranschlagten 18 h. Die Abweichung entsteht vor allem in der
Filterchain (4 h), beim Brute-Force-Schutz (3 h) und beim API-Vertrag zum Frontend (1 h) – alle drei
waren in der Top-down-Schätzung zu grob angesetzt. Die Summe der Einzelschritte ist verlässlicher als die Gesamtschätzung, deshalb steht hier
der höhere Wert. S1 wurde entsprechend von 14 auf 15 h angehoben, S3 von 12 auf 11 h gesenkt, da der
Import der Referenzdaten dort entfällt.

**Korrektur der S2b-Schätzung (22.08.2026):** Die Aufschlüsselung in `S2b_UMSETZUNG.md` summiert
sich auf **10 h** statt 6 h. Der Unterschied entsteht durch zwei Punkte, die in keiner
Meilensteinliste standen und sonst heimatlos blieben: die Passwortänderung im angemeldeten Zustand
und die Änderung der zentralen PIN durch den Admin (A3). Beide gehören zur Zugangsdatenpflege und
brauchen dieselben Bausteine; sie in S3 unterzubringen hiesse, die zentrale PIN bis dahin nur über
die Datenbank ändern zu können. Wie schon bei S2 gilt: Die Summe der Einzelschritte ist
verlässlicher als die Gesamtschätzung.

**Summe Server ≈ 129 h → ca. 20 Kalenderwochen** bei 6,5 h/Woche (Spanne ±15 %). Kritischer Pfad: S2
und S5. **Abhängigkeit: S2b setzt einen SMTP-Zugang voraus** (Anbieter, Absenderadresse,
Zugangsdaten) – ohne ihn ist der Meilenstein weder umsetzbar noch testbar. Vorschlag und
Alternativen in `S2b_UMSETZUNG.md`, Abschnitt 0.3.

## 6. Aktueller Code-Zustand (Stand 29.08.2026, Branch `dev`)

### 6.1 Was steht

**S0, S1, S2, S2b und S3 (Pakete 0 bis 4) sind abgeschlossen und verifiziert.** Offen aus S3
sind die Pakete 5 bis 7 (Admin-Konfiguration, Gastplätze, Bestandsaufnahme der Autorisierung).

```
server/                        Repo-Wurzel (remote: FuBo-Server, privat)
  fubo-api.json                Endpunktkontrakt, 19 Endpunkte (Abschnitt 4)
  compose.dev.yml              postgres:17
  .env / .env.example          DB-Zugang, FUBO_INITIAL_PIN, ADMIN_*, SMTP_*
  scripts/                     seed-lokal.sh + anonymisierter 30er-Datensatz
  src/main/resources/db/       migration/ V001-V008, demodata/ (nur dev und test)
  src/main/java/de/fubo/appserver/
    common/  config error security
    controller/ auth admin        service/ auth profil audit mail config
    repository/ auth profil audit domain/ auth profil audit config
    dto/ auth profil admin        utils
```

**Abhängigkeiten:** `actuator`, `data-jpa`, `flyway` (+ `flyway-database-postgresql`),
`security`, `validation`, `webmvc`, `mail`, `postgresql`; im Test die `*-test`-Starter,
`spring-boot-testcontainers`, `testcontainers-postgresql`. **Kein Cache-Starter** – der
`CacheManager` entsteht von Hand aus `spring-context`.

**Konfiguration:** `spring.config.import=optional:file:./.env[.properties]`,
`jpa.hibernate.ddl-auto=validate`, `open-in-view=false`, `flyway.schemas=profil, spieltag,
configs`, `flyway.validate-migration-naming=true`, `server.forward-headers-strategy=NATIVE`,
Actuator auf `health` beschränkt. Die Demodaten-Location steht **nur** in
`src/test/resources/application.yml`.

**Datenmodell: 18 Tabellen in drei Schemas, `V001`–`V008`. Seit S2 kam keine Migration mehr
dazu** – weder S2b noch S3 brauchten eine.

**Die 19 Endpunkte** (massgeblich bleibt `fubo-api.json`):

| Methode | Pfad unter `/api/v1` | Zweck |
|---|---|---|
| `POST` | `/auth/pin/pruefen` | Zentrale PIN prüfen, Sitzung in `PIN_VERIFIED` |
| `GET` | `/auth/users/lesen` | Namensliste mit Belegtstatus, **ohne** Skillwerte |
| `POST` | `/auth/user/waehlen` | Profil wählen (zweite Stufe) |
| `POST` | `/auth/gast/anmelden` | Als Gast anmelden (zweite Stufe) |
| `POST` | `/auth/admin/anmelden` | Anmeldename **und** Passwort (zweite Stufe) |
| `GET` | `/auth/session/lesen` | Zustand der eigenen Sitzung |
| `POST` | `/auth/session/erneuern` | Sitzung verlängern, Token rotiert |
| `POST` | `/auth/session/beenden` | Abmelden |
| `POST` | `/auth/passwort/zuruecksetzen` | Reset anfordern, PIN per E-Mail |
| `POST` | `/auth/passwort/bestaetigen` | PIN einlösen, neues Passwort |
| `POST` | `/admin/passwort/aendern` | Passwort im angemeldeten Zustand |
| `POST` | `/admin/pin/aendern` | Zentrale PIN, widerruft **alle** Sitzungen |
| `POST` | `/admin/name/aendern` | Anmeldename, Sitzung **bleibt** |
| `POST` | `/admin/user/anlegen` | Profil anlegen |
| `POST` | `/admin/user/bearbeiten` | Name und/oder Skillwerte |
| `POST` | `/admin/user/entfernen` | Profil endgültig entfernen |
| `POST` | `/admin/user/blockieren` | Sperren und freigeben |
| `GET` | `/admin/user/lesen` | Alle Profile **mit** Skillwerten |
| `GET` | `/admin/skills/lesen` | Skillkategorien und Wertebereiche |

Die ersten acht stammen aus S2, die folgenden fünf aus S2b, die letzten sechs aus S2b und S3.
Alles unterhalb von `/api/*/admin/**` verlangt `ROLE_ADMIN`; die Reset-Endpunkte und die drei
Login-Wege sind ausschliesslich in `PIN_VERIFIED` erreichbar.

### 6.2 Festlegungen mit Datum

**Die meisten Entscheidungen dieser Meilensteine sind inzwischen verbindliche Regeln in
`AGENT_SERVER.md`** – Adminprofil als technisches Konto, Anmeldename, Skill-Geheimhaltung,
Zwischenspeicher, Audit-Log, Start-Bootstrap, Zugangsdatenpflege, Spielerverwaltung. Sie stehen
hier nicht noch einmal; die Herleitung liegt in den archivierten Fassungen und in den
`S<n>_UMSETZUNG.md`. Was hier bleibt, sind die Festlegungen, die **keine** Architekturregel
sind, sondern Weggabelungen mit Datum:

| Datum | Festlegung | Grund |
|---|---|---|
| 09.08. | Auth-Pfade heissen `users`/`user`, nicht `namen`/`name` | Code schlägt Anleitung – dort hängt die Autorisierung samt Tests |
| 09.08. | Frontend und API auf Subdomains **derselben** registrierbaren Domain | sonst cross-site: `SameSite=None`, CSRF-Pflicht, blockierte Cookies |
| 22.08. | Kontrakt als `server/fubo-api.json` (OpenAPI 3.1, JSON, Repo-Wurzel) | Vorgabe des Haupt-Entwicklers; Begründung in Abschnitt 4 |
| 23.08. | Reset unter `/auth/…` statt `/admin/…` | wer sein Passwort vergisst, trägt `ROLE_ADMIN` nicht |
| 23.08. | Zentrale PIN vierstellig | mündliche Weitergabe, Eingabefeld mit vier Kästchen |
| 29.08. | Anmeldename = Profilname, keine eigene Spalte | zweiter Name für dasselbe Konto wäre Ballast |
| 29.08. | Bestehender Fehlercode `ADMIN_PASSWORT_FALSCH` bleibt | kein Vertragsbruch; `detail` darf sich ohne Vertragsänderung ändern |
| 29.08. | Zwischenspeicher anwendungsweit, nicht je Sitzung | es gibt genau einen Admin |
| 29.08. | Übersichtsabfrage in zwei geteilt (Abweichung von der Anleitung) | der Belegtstatus darf nicht gecacht werden |
| 29.08. | Adminprofil-Umbenennung über `/admin/name/aendern` statt `bearbeiten` | Weggabelung C der S3-Anleitung damit überholt |
| 29.08. | Namensänderung widerruft **keine** Sitzung | Vorgabe des Haupt-Entwicklers |

**Zwei Abweichungen aus S1, die im Datenmodell sichtbar sind:** `min_teilnehmer = 6`,
`anz_team_generator = 1`, `session_maximal_stunden = 1` (statt 8/2/8), und `session.stage`
heisst in der zweiten Stufe `PROFILE_AUTHENTICATED`, weil auch Gäste sie erreichen.
`spieltag.termin.fk_termin_serie` hat bewusst kein `ON DELETE` – eine Serie lässt sich nicht
löschen, solange Termine daran hängen; nicht mehr benötigte Termine gehen über den Status
`GEPLANT` und die Fachlogik.

### 6.3 Fallstricke, die weiter gelten

**Konfiguration und Start**

- **Ein `${...}` in einer Fehlermeldung bedeutet immer fehlende Auflösung, nie einen falschen
  Wert.** Spring Boots `Binder` reicht unauflösbare Platzhalter wörtlich durch – anders als
  `@Value`. `--env-file` gilt nur für Docker Compose, nicht für die JVM; deshalb
  `spring.config.import`.
- **In der `.env` nie Anführungszeichen** – sie wird als Java-Properties-Datei gelesen und
  übernimmt sie in den Wert. Kostete am 29.08.2026 einen `550`-Fehler beim Mailversand.
- **`target/classes` vergisst nichts.** Nach dem Umbenennen oder Löschen einer Ressource immer
  `./mvnw clean` – das gilt auch nach jeder Änderung an `application.yml`.

**Flyway und JPA**

- **Flyway überspringt falsch benannte Migrationen stillschweigend** (doppelter Unterstrich!).
  `validate-migration-naming: true` ist deshalb gesetzt und bleibt es.
- **Beispielcode gehört nicht in Migrationen.** Ein `:name` aus einer Anleitung ist für
  PostgreSQL ein Syntaxfehler (`42601`).
- **`ddl-auto=validate` prüft Spaltenexistenz und JDBC-Typcode, nicht die Zuordnung.**
  `CHAR(n)` braucht `@JdbcTypeCode(SqlTypes.CHAR)`; vertauschte gleichartige Spalten fallen
  nicht auf. Mapping-Fehler äussern sich als Kaskade von `UnsatisfiedDependencyException` –
  **nur die erste Logzeile benennt die Ursache.**
- **Tabellennamen immer aus den `CREATE TABLE`-Zeilen lesen, nie aus einem Constraint-Namen.**
  `fk_terminserie_spieler` gehört zu `spieltag.terminserie`, `fk_kontingent_spieler` zu
  `spieltag.generierung_kontingent` – zwei falsch abgeleitete Namen in
  `SpielerRepository#istReferenziert` kosteten am 23.08.2026 einen Testlauf.
- **Wo JPA schreibt und natives SQL liest, muss geflusht werden** (`saveAndFlush`). Ohne das
  bleibt die Änderung bis zum Ende der Transaktion unsichtbar.
- **`@Modifying`-Abfragen mit `clearAutomatically` lösen Entities vom Persistence-Context.**
  Was danach gebraucht wird (Id, Name), vorher in lokale Variablen holen.

**Tests**

- **Alles, was den Kontextstart überlebt, überlebt auch die Test-Transaktion.** Betrifft den
  `ApplicationRunner` des Bootstraps (deshalb stehen `ADMIN_*` und `fubo.mail.*` in
  `src/test/resources/application.yml`), den `BruteForceService` und seit S3 den
  `ProfilStammdatenCache` – alle drei werden in `@BeforeEach` zurückgesetzt.
- **`REQUIRES_NEW` und `@Transactional` am Test vertragen sich nicht.** Die eigene Transaktion
  sieht die Testdaten unter READ COMMITTED nicht. Betroffene Klassen tragen kein
  `@Transactional` und räumen von Hand auf.
- **Eine Änderung an `FuboProperties` bricht drei Testklassen**, die den Record von Hand bauen
  (`SessionAuthFilterTests`, `SessionCookieFactoryTests`, `BruteForceServiceTests`).
- **`sitzungsIdZu(token)` nur mit noch gültigem Token aufrufen** – jeder Stufenwechsel rotiert
  ihn. Die `session.id` vorher auflösen und behalten.
- **`SMALLINT` kommt über `queryForMap` als `Integer` zurück**, über
  `queryForObject(..., Short.class)` als `Short`. Beide Wege sind richtig, nur nicht
  miteinander vergleichbar.
- **Antworten über Jackson auswerten, nicht mit `contains` auf dem rohen JSON** – ein
  Zeichenkettenvergleich trifft auch eine andere Zeile und meldet eine falsche Zuordnung als
  Erfolg.

**Sicherheit und Betrieb**

- **`Using generated security password` ist kein Indikator** – weder dafür noch dagegen, dass
  die Filterchain greift. Am Verhalten prüfen: ohne Cookie `401` mit
  `application/problem+json`, `/actuator/health` ohne Cookie `200`.
- **Der Brute-Force-Zähler ist zwischen PIN- und Admin-Login geteilt.** Fünf Vertipper beim
  Adminpasswort sperren auch den PIN-Login; die Meldung lautet dann `PIN_GESPERRT`.
- **`max-versuche-ip` und `fubo.reset.max-versuche` stehen beide auf 5**, deshalb greift im
  Betrieb die IP-Sperre vor dem Vorgangszähler. Das ist die gewünschte Staffelung.
- **Git über die Ordnerfreigabe hinterlässt Sperrdateien.** Nach jedem schreibenden Befehl
  `find .git \( -name 'tmp_obj_*' -o -name '*.lock' \) -delete`, sonst blockiert `HEAD.lock`
  den nächsten Commit.

### 6.4 Verifikation

**`./mvnw clean verify` grün am 29.08.2026 – 227 Tests in 21 Klassen**, keine Fehler, keine
Abbrüche, keine übersprungenen Tests. Der Lauf schliesst die S2b-Schritte 8 bis 11, den
Nachtrag zum Anmeldenamen und die S3-Pakete 0 bis 4 gemeinsam ab. Die Anwendung startet auf
einer frischen Datenbank durch.

```bash
docker info > /dev/null                                    # muss durchlaufen
docker compose -f compose.dev.yml --env-file .env up -d
./mvnw clean verify
```

| Testklasse | Fälle | | Testklasse | Fälle |
|---|---:|---|---|---:|
| `SpielerControllerTests` | 34 | | `AdminControllerTests` | 11 |
| `SecurityConfigTests` | 26 | | `NamenControllerTests` | 10 |
| `SessionServiceTests` | 18 | | `AuthControllerTests` | 10 |
| `PasswortResetControllerTests` | 15 | | `SessionControllerTests` | 10 |
| `SessionAuthFilterTests` | 14 | | `AdminBootstrapTests` | 9 |
| `ZugangsdatenControllerTests` | 12 | | `SessionCookieFactoryTests` | 9 |
| `BruteForceServiceTests` | 10 | | `GastControllerTests` | 8 |
| `MigrationTests` | 7 | | `AuditServiceTests` | 6 |
| `ApiVersionConfigTests` | 5 | | `MailConfigTests` | 5 |
| `SkillKategorieControllerTests` | 4 | | `ConfigServiceTests` | 2 |
| `GastServiceTransaktionTests` | 2 | | **Summe** | **227** |

**Erwartungswerte vorab: `grep -c '^\s*@Test\s*$'` je Klasse für die Fälle,
`find src/test -name '*Tests.java' | wc -l` für die Klassen.** Die Fallzahl traf am 22.08.
(148), 23.08. (184) und 29.08.2026 (227) exakt; die Klassenzahl war einmal falsch, weil sie
fortgeschrieben statt nachgezählt wurde. **Nach dem Lauf beide Zahlen aus
`target/surefire-reports/` übernehmen**, nicht nur die auffällige.

**Scheitert ein Lauf, zuerst die Surefire-Berichte lesen, nicht die Maven-Zusammenfassung.**
Bei einem Kontextfehler meldet Spring Test jeden betroffenen Fall einzeln, aber nur der *erste*
Bericht je Kontextkonfiguration nennt die Ursache – alle anderen tragen
`ApplicationContext failure threshold (1) exceeded`. 115 Fehler bedeuten dann eine Ursache.
Kürzester Weg: `grep -h 'Caused by' target/surefire-reports/*.txt | tail -1`. Gegenprobe über
die drei Klassen ohne Spring-Kontext (`SessionAuthFilterTests`, `SessionCookieFactoryTests`,
`BruteForceServiceTests`): Sind die grün, liegt es nicht am Anwendungscode. Am 23.08.2026
lautete die Antwort `Could not find a valid Docker environment`.

Die manuelle Prüfliste steht in `S2b_UMSETZUNG.md`, Abschnitt 12.1; die Bruno-Collection deckt
sie ab.

## 7. Nächste Schritte

1. **Manuelle Prüfliste abarbeiten** (`S2b_UMSETZUNG.md`, Abschnitt 12.1). Der Reset lässt sich
   nur mit echtem SMTP-Zugang durchspielen; die Bruno-Collection führt durch die Reihenfolge.
   Vorher `adminName` und `neuerAdminName` in den Umgebungen füllen – beim Anmeldenamen
   **zeichengenau wie `ADMIN_NAME`**, sonst scheitert jeder Admin-Request mit `401`.
   Zusätzlich zu prüfen: „Admin anmelden" mit richtigem Namen, mit falschem Namen und mit
   abweichender Schreibweise – die drei Ablehnungen müssen in Code, Statuscode und Anzeigetext
   identisch sein.
2. **Client-Track über die Vertragsänderung informieren** (Abschnitt 4a). Das Anmeldeformular
   des Admins braucht ein zweites Pflichtfeld, sonst liefert der Endpunkt `400`.
3. **S3 fortsetzen mit den Paketen 5 bis 7** (`harness/tmp/S3_UMSETZUNG.md`, Abschnitte 5
   bis 7): Admin-Konfiguration lesen und ändern samt neuem Fehlercode `DATEN_VERALTET`,
   Gastplätze mitpflegen, Bestandsaufnahme der Autorisierung. **Zwei Punkte, die dabei nicht
   untergehen dürfen:** Der Vertrag wächst um zwei Endpunkte auf 21, und
   `KonfigurationControllerTests` braucht `@Transactional` – die Klasse ändert eine
   anwendungsweit gültige, einzeilige Tabelle und liefe sonst anderen Klassen in die Quere,
   etwa `GastControllerTests`, das sich auf `anz_guests = 4` verlässt.
4. **Danach S4** (Termine & Teilnahme). **Pflichtpunkt von dort:** Eine Skilländerung ist eine
   Teilnehmeränderung (A15) und muss die `teilnehmer_version` betroffener künftiger Termine
   hochzählen – in S3 mangels `spieltag`-Service nicht umsetzbar.

**Offene Punkte, die keine Aufgabe für heute sind:**

- **Punkt 18 (S3/S5):** Weitere Gastplätze anlegen, wenn `anz_guests` über vier steigt.
- **Punkt 20 (S5):** Wie behandelt der Teamgenerator Profile ohne gepflegte Skillwerte? Durch
  S2b und S3 kleiner geworden – über `/admin/user/anlegen` entstandene Profile haben immer
  vollständige Werte, ungepflegt bleiben nur Profile aus einem Datenimport, und
  `/admin/user/lesen` macht die Lücke sichtbar.
- **Betriebsaufgabe ohne Code:** Custom Domain `app.<domain>` in Cloudflare Pages einrichten.
  Ohne sie funktioniert die Anmeldung produktiv nicht – `pages.dev` steht auf der Public Suffix
  List und wäre gegenüber `api.<domain>` cross-site, mit `SameSite=None; Secure`, zwingendem
  CSRF-Schutz und einem Cookie, das Safari und der Chrome-Inkognito-Modus blockieren. Lokale
  Entwicklung und Tests sind nicht betroffen. Ebenfalls offen: der Umgang mit
  Pages-Preview-Deployments, in denen der Login bauartbedingt nicht funktioniert.
- **Deployment (S8):** Entwurf mit Dockerfile, Compose-Ergänzung, nginx-Block, Backup und
  Rollout liegt in `harness/tmp/S8_DEPLOYMENT.md`.

**Profildaten** (Vorgehen steht): Reale Daten liegen ausserhalb von `PRJ_FuBo/`, Pfad in
`FUBO_LOCAL_SEED`, Einspielen über `scripts/seed-lokal.sh`. Der anonymisierte 30er-Satz liegt
in `scripts/data/`, der 12er-Demosatz läuft automatisch in Dev und Test.

## 8. Weitere Anweisungen

- **Repository:** Wurzel `server/`, gearbeitet wird auf **`dev`**, `main` bleibt der
  freigegebene Stand. Commit-Nachrichten nach Conventional Commits **ohne** Scope, Umlaute
  transliteriert. **Ohne ausdrückliche Anweisung nichts nach `main` mergen oder pushen.**
- **Getrennte Repositories:** Server und Client lassen sich nicht gemeinsam committen.
  Vertragsänderungen deshalb **immer zuerst** in `server/fubo-api.json`, der Client-Track zieht
  danach nach.
- **`.env` nie einchecken.** Dokumentation in deutscher Sprache, **keine realen Personennamen**
  in Code, Testdaten oder Dokumentation.
- **Nach Abschluss eines Arbeitspakets:** kurz verifizieren, diesen Handoff fortschreiben und
  die Vorfassung unter `harness/archive/` ablegen; zusätzlich
  `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) nachziehen. Verbindliche Regeln, die
  sich aus der Umsetzung ergeben, gehören in `AGENT_SERVER.md` – **nicht** zusätzlich hierher,
  sonst laufen beide auseinander. `/PRJ_FuBo/harness/` liegt ausserhalb dieses Repositories.
