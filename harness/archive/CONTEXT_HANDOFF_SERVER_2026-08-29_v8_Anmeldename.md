# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) um den
> serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`. Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.

> Projektordner: `<Projektordner>/PRJ_FuBo`, Backend unter `server/`
> Git-Repository: **eigenständiges Repository mit Wurzel in `server/`** (GitHub, privat, `FuBo-Server`).
> **Kein Monorepo.** Das Frontend liegt in einem getrennten Repository (`FuBo-Client`, Ordner `client/`).
> Der übergeordnete Ordner `PRJ_FuBo/` sowie `PRJ_FuBo/harness/` sind bewusst **nicht** versioniert.
> Repository ist angelegt: `https://github.com/DenKim94/FuBo-Server.git` (privat).
> Stand: 29.08.2026, **S0, S1, S2 und S2b abgeschlossen; Nachtrag „Anmeldename beim
> Admin-Login" umgesetzt (Abschnitt 6.13).** S2 und die S2b-Schritte 0 bis 7 sind
> mit `./mvnw clean verify` verifiziert (148 Tests); fuer die Schritte 8 bis 11
> (Spielerverwaltung, Aufraeumjob, Tests) steht der Lauf noch aus – erwartet werden **184 Tests
> in 19 Klassen**, siehe Abschnitt 6.6
> (22.08.2026: Gast-Login mit festen Gastplätzen, Admin-Konto im Bootstrap, die drei
> Sitzungsendpunkte, Ausnahme des Pollings von der Sitzungsverlängerung und maschinenlesbare
> Restwartezeit beim `429` umgesetzt, siehe Abschnitt 6.10; der Endpunktkontrakt liegt jetzt als
> **`server/fubo-api.json`** vor, siehe Abschnitt 4. Danach: Das Adminprofil ist ein technisches
> Konto und aus Namensliste und Teamgenerierung ausgeschlossen; dafür kam der Admin-Login über
> das Passwort hinzu, siehe Abschnitt 6.12)
> (16.08.2026: PIN-Login mit Brute-Force-Schutz, Audit-Log, Namensliste/-belegung/-auswahl und
> PIN-Bootstrap implementiert, siehe Abschnitt 6.6; anschliessend Versionierung der Schnittstelle
> über ein Pfadsegment, siehe Abschnitt 6.7, sowie Löschfrist und Transaktionskopplung des
> Audit-Logs, siehe Abschnitt 6.8; `./mvnw clean verify` am 16.08.2026 grün, 99 Tests)
>
> **Prüfung erfolgreich ausgeführt: `./mvnw clean verify` ist am 22.08.2026 grün durchgelaufen –
> 148 Tests in 16 Klassen, keine Fehler, keine Abbrüche, keine übersprungenen Tests. Die Anwendung
> startet auf einer frischen Datenbank durch.** Aufstellung je Klasse in Abschnitt 6.11. Der Lauf
> braucht Docker (Testcontainers, `postgres:17`) und läuft ausschliesslich lokal.
>
> **Nachtrag vom 29.08.2026 (Abschnitt 6.13):** `POST /auth/admin/anmelden` verlangt jetzt
> **Anmeldename und Passwort**. Der Anmeldename ist der Profilname des Adminprofils
> (`ADMIN_NAME`); es gibt **keine** neue Spalte und **keine** Migration. Betroffen sind
> `AdminLoginRequest`, `AdminService`, `AdminController`, `AdminBootstrap`, `SpielerRepository`,
> `Fehlercode`, `AdminControllerTests`, `AdminBootstrapTests`, `fubo-api.json`, beide
> `README.md` und die Bruno-Collection. Der Vergleich ist **zeichengenau**; der Bootstrap
> sichert das ab. **Der Testlauf steht noch aus:
> erwartet werden jetzt 190 Tests in 19 Klassen** (184 + 5 in `AdminControllerTests`,
> + 1 in `AdminBootstrapTests`).
>
> Als Nächstes: **`./mvnw clean verify`** – deckt die S2b-Schritte 8 bis 11 **und** den
> Nachtrag ab –, dann **S3** (Profile & Skills API). Die manuelle Prüfliste steht in
> `harness/tmp/S2b_UMSETZUNG.md`, Abschnitt 12.1; die Bruno-Collection deckt sie ab.
>
> (Vorfassungen archiviert unter `harness/archive/`, zuletzt
> `CONTEXT_HANDOFF_SERVER_2026-08-23_v7_S2b-vollstaendig.md`. **Die Abschnitte zu S2 sind seit v7
> auf ihre Regeln und Fallstricke eingedampft**; die Schritt-für-Schritt-Erzählung steht in v6.)

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

**Umfang:** ausschliesslich das, was tatsächlich umgesetzt ist – **15 Endpunkte**: die acht
Auth- und Sitzungsendpunkte aus S2 sowie seit dem 23.08.2026 die sieben aus S2b
(Zugangsdatenpflege und Spielerverwaltung, Abschnitt 6.5). Spekulative Endpunkte wären ein Vertrag über etwas, das es nicht gibt;
S3 bis S6 tragen ihre Endpunkte jeweils bei Fertigstellung nach.

Inhaltliche Kernpunkte (unverändert, Herleitung in `AGENT_SERVER.md`, Abschnitt „Schnittstelle zum
Frontend"): REST/JSON, getrennte Origins mit CORS-Allowlist (`allowCredentials`),
HttpOnly-Session-Cookie, `401`/`403`-Semantik, DTOs ohne Skillwerte für USER/GAST,
Belegtstatus-Endpunkt zum Pollen, einheitliches Fehler-JSON nach RFC 9457.

Neu im Vertrag seit dem 22.08.2026:
- **`X-FuBo-Kein-Refresh: true`** als Anfrageheader für Hintergrundaufrufe (Abschnitt 6.10).
- **`Retry-After`** und das Feld `wartesekunden` beim `429` des PIN-Endpunkts.
- **`absolutGueltigBis`** in der Sitzungsauskunft, zusätzlich zu `gueltigBis`.

Geändert am 29.08.2026 (Abschnitt 6.13):
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
| S2b | Zugangsdatenpflege und Spielerverwaltung: Passwort-Reset per E-Mail (5-stellige PIN, Rate-Limit, Sitzungswiderruf), Passwortänderung im angemeldeten Zustand, Änderung der zentralen PIN, Anlegen/Entfernen/Sperren von Spielerprofilen, Aufräumjob – **abgeschlossen**, Verifikation der Schritte 8 bis 11 offen. Anleitung `harness/tmp/S2b_UMSETZUNG.md` | 10 |
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

### 6.1 S0 und S1 sind abgeschlossen

```
server/                                  Repository-Wurzel (remote: FuBo-Server, privat)
  pom.xml                                Spring Boot 4.1.0, Java 25, de.fubo:app-server:0.0.1-SNAPSHOT
  mvnw, mvnw.cmd, .mvn/                  Maven Wrapper
  compose.dev.yml                        postgres:17 (Service fubo-db-dev, Volume fubo_db_dev_data)
  .env / .env.example                    DB-Zugang, FUBO_LOCAL_SEED, FUBO_INITIAL_PIN
  scripts/seed-lokal.sh                  Import lokaler bzw. anonymisierter Profildaten
  scripts/data/spielerprofile_anonym.sql 30 Profile, reale Skillwerte, anonymisierte Namen
  src/main/java/de/fubo/appserver/        AppServerApplication + leere Paketstruktur (siehe 6.2)
  src/main/resources/application.yml
  src/main/resources/db/migration/        V001-V007 (Schema + Referenzdaten)
  src/main/resources/db/demodata/         R__seed_beispielprofile.sql (12 Profile, nur dev/test)
  src/test/java/de/fubo/appserver/database/  MigrationTests, TestcontainersConfiguration
  src/test/resources/application.yml      Testprofil: Demodaten-Location, Datasource-Platzhalter
  harness/                                AGENT_SERVER.md, CONTEXT_HANDOFF_SERVER.md, archive/, tmp/
```

**Abhängigkeiten:** `actuator`, `data-jpa`, `flyway` (+ `flyway-database-postgresql`), `security`,
`validation`, `webmvc`, `postgresql` (runtime); im Test die zugehörigen `*-test`-Starter sowie
`spring-boot-testcontainers` und `testcontainers-postgresql`. **`spring-boot-starter-mail` fehlt noch** –
korrekt so, wird erst in S2b gebraucht.

**Konfiguration (`application.yml`):**
`spring.config.import=optional:file:./.env[.properties]` (siehe 6.4), Datasource über Umgebungsvariablen,
`jpa.hibernate.ddl-auto=validate`, `open-in-view=false`, `flyway.schemas=profil, spieltag, configs`,
`flyway.default-schema=public`, `flyway.locations=classpath:db/migration`,
`server.forward-headers-strategy=NATIVE`, Actuator-Exposure auf `health` beschränkt.
Die Demodaten-Location wird ausschliesslich über `src/test/resources/application.yml` ergänzt – die
Produktionskonfiguration sieht sie nie.

**Datenmodell:** 18 Tabellen in drei Schemas, umgesetzt in `V001`–`V007` gemäss der korrigierten Fassung
in `/PRJ_FuBo/harness/AGENT.md`. `MigrationTests` enthält sieben Tests (Schemas, Seed, partieller
Admin-Index, Wertebereichs-Trigger, `NULLS NOT DISTINCT`, Demodaten, Torwart-Bereich).


### 6.2 Abweichungen, die während S1 bewusst festgelegt wurden

- **Defaults in `configs.app_config`** weichen von der Vorplanung ab und die Dokumentation wurde
  angeglichen (`AGENT.md`, Änderungsprotokoll Punkt 13): `min_teilnehmer = 6` (statt 8, Anforderung 10
  mitgeändert), `anz_team_generator = 1` (statt 2, deckt sich mit dem Wortlaut von A15),
  `session_maximal_stunden = 1` (statt 8).
- **`session.stage`** heisst in der zweiten Stufe `PROFILE_AUTHENTICATED` (vorher
  `PLAYER_AUTHENTICATED`). Begründung: Die Stufe wird auch von Gästen erreicht, die kein Profil in
  `profil.spieler` haben. Einheitlich in allen aktiven Dokumenten nachgezogen.
- **`spieltag.termin.fk_termin_serie` ohne `ON DELETE`**, also `NO ACTION`: Eine Serie lässt sich nicht
  löschen, solange Termine daran hängen. Bewusst konservativ – `ON DELETE CASCADE` hätte über die
  Kaskadenkette auch Teilnahmen, Teameinteilungen und Ergebnisse gelöscht.
  Falls erforderlich werden nicht mehr benötigten Termine einer Serie über den Status 'GEPLANT' identifiziert und über die Fachlogik entfernt.  

### 6.3 Zwei Fallstricke aus S1, die dokumentiert bleiben sollten

**`--env-file` gilt nur für Docker Compose, nicht für die JVM.** Beim Start mit `./mvnw spring-boot:run`
kennt der Java-Prozess `DB_USER`/`DB_PASSWORD` nicht. Spring Boots `Binder` reicht unauflösbare
Platzhalter **wörtlich** durch (`ignoreUnresolvablePlaceholders = true`, anders als `@Value`), weshalb
die Fehlermeldung `password authentication failed for user "${DB_USER}"` lautet. Gelöst über
`spring.config.import=optional:file:./.env[.properties]`. Merkregel: Ein `${...}` in einer
Fehlermeldung bedeutet immer fehlende Auflösung, nie einen falschen Wert.

**Beispielcode gehört nicht in Migrationen.** Ein illustratives `UPDATE ... SET session_id = :sessionId`
aus der S1-Anleitung war versehentlich in `V003` gelandet; `:name` ist ein JDBC-Parameter und für
PostgreSQL ein Syntaxfehler (`42601`). Auf PostgreSQL rollt Flyway eine fehlgeschlagene Migration
vollständig zurück, ein `flyway repair` war nicht nötig.

### 6.4 S2 – Auth und Session (abgeschlossen und verifiziert am 22.08.2026)

Die Schritt-für-Schritt-Erzählung steht in der archivierten Fassung v6 und in
`harness/tmp/S2_UMSETZUNG.md`. Hier bleibt, was für die Weiterarbeit trägt.

```
src/main/java/de/fubo/appserver/
  common/config/      SecurityConfig, CorsConfig, ApiVersionConfig, FuboProperties,
                      SchedulingConfig, ZeitConfig
  common/security/    SessionAuthFilter, SessionCookieFactory, AuthorizationExceptionHandler
  common/error/       Fehlercode, FachlicherFehler, GlobalExceptionHandler
  domain/auth/        Session, AdminKonto, Zugangsdaten, AktiveSitzung, Stage, Rolle, GastStufe
  domain/audit/       AuditAktion
  domain/profil/      Spieler, NamensEintrag
  domain/config/      AppConfig, AlgorithmType
  repository/auth/    SessionRepository (+Custom/Impl), AdminKontoRepository,
                      ZugangsdatenRepository, GastSlotRepository (JdbcClient)
  repository/audit/   AuditLogRepository (JdbcClient)
  repository/profil/  SpielerRepository (+Custom/Impl)
  service/auth/       SessionService, PinService, AdminService, GastService, NamenService,
                      BruteForceService, PinBootstrap, AdminBootstrap
  service/audit/      AuditService
  controller/auth/    AuthController, NamenController, GastController, AdminController,
                      SessionController
  dto/auth/, dto/profil/, utils/
db/migration/         V008__session_rolle_optional.sql (letzte Migration)
```

**Endpunkte aus S2:**

| Methode | Pfad | Erlaubte Stufe | Antwort |
|---|---|---|---|
| `POST` | `/api/v1/auth/pin/pruefen` | offen | `204` + Cookie (`PIN_VERIFIED`) |
| `GET` | `/api/v1/auth/users/lesen` | `PIN_VERIFIED` und höher | `200` Namensliste, **ohne Skillwerte** |
| `POST` | `/api/v1/auth/user/waehlen` | nur `PIN_VERIFIED` | `204` + **neues** Cookie |
| `POST` | `/api/v1/auth/gast/anmelden` | nur `PIN_VERIFIED` | `204` + **neues** Cookie |
| `POST` | `/api/v1/auth/admin/anmelden` | nur `PIN_VERIFIED` | `204` + **neues** Cookie, Rolle `ADMIN` (seit 29.08.2026 mit Anmeldename, Abschnitt 6.13) |
| `GET` | `/api/v1/auth/session/lesen` | `PIN_VERIFIED` und höher | `200` Sitzungsauskunft |
| `POST` | `/api/v1/auth/session/erneuern` | `PIN_VERIFIED` und höher | `204` + **neues** Cookie |
| `POST` | `/api/v1/auth/session/beenden` | `PIN_VERIFIED` und höher | `204`, Cookie gelöscht |

**Die tragenden Entscheidungen** – alle als verbindliche Regeln in `AGENT_SERVER.md`:

1. **Das Adminprofil ist ein technisches Konto.** Nicht in der Namensliste, über die
   Namensauswahl auch mit bekannter Id nicht wählbar (`404`), nie in einem Team. Skillwerte 0 –
   **kein Ersatz für den Ausschluss**. Der Admin meldet sich über einen eigenen Endpunkt an;
   ohne diesen Weg käme niemand mehr an `ROLE_ADMIN`. *(Seit dem 29.08.2026 mit Anmeldename
   **und** Passwort, siehe Abschnitt 6.13.)*
2. **Der Ausschluss wird an jeder Grenze wiederholt**, nicht nur in der Anzeige. Ein Endpunkt,
   der eine Id entgegennimmt, prüft dieselbe Bedingung erneut wie die Liste, aus der sie stammt.
3. **Ein Brute-Force-Zähler für PIN- und Admin-Login.** Derselbe Absender greift dieselbe
   Anwendung an. Sichtbare Folge: Die Sperre meldet `PIN_GESPERRT`, auch wenn sie auf ein
   Passwort antwortet.
4. **Audit-Log: Ausbreitung `REQUIRED`, nie `REQUIRES_NEW`; Ausnahmen werden nicht verschluckt;
   Löschfrist 90 Tage.** Ein Protokolleintrag belegt eine *vollzogene* Änderung.
5. **Zwei-Timer-Modell** mit `gueltigBis` (gleitend) und `absolutGueltigBis` (hart, wandert nie).
   `X-FuBo-Kein-Refresh: true` schaltet auf einen rein lesenden Prüfpfad – damit misst
   „15 Minuten Inaktivität" den Nutzer und nicht den offenen Browser-Tab. Der Header ist eine
   Bitte, kein Sicherheitsmerkmal; Missbrauch schadet nur dem Absender.
6. **Versionierung als Pfadsegment an Index 1** (`/api/v1/...`), eingegrenzt durch ein
   `Predicate` auf `/api/` – ohne das antwortete `/actuator/health` mit `400` und der Container
   bliebe dauerhaft `unhealthy`. Aktionssegment am Pfadende (`/lesen`, `/waehlen`). Feste
   Version `"1"` an jedem Mapping; vor dem Sprung auf v2 ist die Basislinien-Semantik zu klären.
7. **Gast-Slots als feste Datensätze**, Belegung per bedingtem `UPDATE`, Wirksamkeit über
   `id <= anz_guests`. `fk_gast_slot_session` hat kein `ON DELETE`: Jeder Vorgang, der Sitzungen
   löscht, gibt vorher die Plätze frei.
8. **Start-Bootstrap statt Flyway** für zentrale PIN und Admin-Konto; beide idempotent.
   Unvollständige `ADMIN_*`-Angaben brechen den Start ab und die Meldung nennt alle fehlenden
   Werte auf einmal.
9. **`Retry-After` und das Feld `wartesekunden`** beim `429` – doppelt geführt, weil der Header
   die genormte Form ist und das Feld dem Frontend den Header-Zugriff erspart, der bei einer
   Cross-Origin-Antwort ohne `Access-Control-Expose-Headers` gar nicht möglich wäre.

**Fallstricke, die sonst erneut Zeit kosten:**

- **`Using generated security password` ist kein Indikator** – weder dafür noch dagegen, dass die
  Filterchain greift. Sie stammt von `UserDetailsServiceAutoConfiguration`, die als einzige
  Autokonfiguration in `AppServerApplication` ausgenommen ist. **Ob die Filterchain greift, wird
  am Verhalten geprüft:** Aufruf ohne Cookie → `401` mit `application/problem+json` und dem Feld
  `code`, kein `WWW-Authenticate`, kein `Location`; `/actuator/health` ohne Cookie → `200`.
- **`ddl-auto=validate` prüft den JDBC-Typcode, nicht die Zuordnung.** `CHAR(64)` braucht
  `@JdbcTypeCode(SqlTypes.CHAR)`; die beiden `CHAR(1)`-Spalten aus `V006` sind in S5/S6 als
  `Character` abzubilden. Mapping-Fehler äussern sich als Kaskade von
  `UnsatisfiedDependencyException` – **nur die erste Logzeile benennt die Ursache**.
- **Flyway überspringt falsch benannte Migrationen stillschweigend** (doppelter Unterstrich!).
  Deshalb ist `validate-migration-naming: true` gesetzt und bleibt es.
- **`target/classes` vergisst nichts.** Nach dem Umbenennen oder Löschen einer Ressource immer
  `./mvnw clean` – das gilt auch nach jeder Änderung an `application.yml`.
- **`sitzungsIdZu(token)` nur mit einem noch gültigen Token aufrufen.** Jeder Stufenwechsel und
  jede Erneuerung rotiert ihn; danach endet die Abfrage in einer
  `EmptyResultDataAccessException` – ein Fehler, der wie ein Anwendungsfehler aussieht und keiner
  ist. Die `session.id` überdauert die Rotation, der Token nicht.
- **Ein `ApplicationRunner`, der beim Kontextstart schreibt, wirkt in jeden `@SpringBootTest`
  hinein** und wird von keiner Test-Transaktion zurückgerollt. Deshalb stehen `ADMIN_*` und seit
  S2b auch `fubo.mail.*` in `src/test/resources/application.yml`.

### 6.5 S2b – Zugangsdatenpflege und Spielerverwaltung (abgeschlossen am 23.08.2026)

Passwort-Reset per E-Mail, Passwortänderung im angemeldeten Zustand, Wechsel der zentralen PIN,
Verwaltung von Spielerprofilen und der Aufräumjob. Anleitung: `harness/tmp/S2b_UMSETZUNG.md`.

```
pom.xml                                  + spring-boot-starter-mail
fubo-api.json                            + 7 Endpunkte, 6 Fehlercodes, 7 Schemas, 2 Tags
.env / .env.example                      + SMTP_HOST, SMTP_PORT, SMTP_ABSENDER
src/main/java/de/fubo/appserver/
  common/config/      MailConfig (neu), FuboProperties + Mail, Reset
  common/error/       Fehlercode  + RESET_PIN_FALSCH, RESET_UNGUELTIG, RESET_GEDROSSELT,
                                    VERSAND_FEHLGESCHLAGEN, PROFIL_GESCHUETZT, PROFIL_IN_VERWENDUNG
  domain/audit/       AuditAktion + 8 Werte (PASSWORT_*, PIN_GEAENDERT, PROFIL_*)
  domain/auth/        OffenerReset, AnforderungsFenster (neu)
  domain/profil/      SkillKategorie (neu)
  repository/auth/    PasswortResetRepository (neu), SessionRepository + loescheFuerSpieler
  repository/profil/  SkillKategorieRepository (neu), SpielerRepository + vorgabewerteAnlegen,
                                                       skillwertSetzen, istReferenziert
  service/auth/       PasswortResetService (neu), ZugangsdatenService (neu),
                      AdminService + passwortSetzen/email, SessionService + Gastplatzfreigabe,
                      PinBootstrap: Ersatz-PIN jetzt vierstellig
  service/profil/     SpielerVerwaltungService (neu)
  service/mail/       MailService (neu)
  dto/auth/, dto/admin/                  5 neue Records
  controller/auth/    PasswortResetController (neu)
  controller/admin/   ZugangsdatenController (neu), SpielerController (neu)
```

**Keine Schemaänderung.** `V008` bleibt die letzte Migration; `profil.passwort_reset` stammt
unverändert aus `V003`.

**Endpunkte aus S2b:**

| Methode | Pfad | Erlaubte Stufe/Rolle | Antwort |
|---|---|---|---|
| `POST` | `/api/v1/auth/passwort/zuruecksetzen` | nur `PIN_VERIFIED` | `204`, PIN per E-Mail |
| `POST` | `/api/v1/auth/passwort/bestaetigen` | nur `PIN_VERIFIED` | `204`, Adminsitzungen widerrufen |
| `POST` | `/api/v1/admin/passwort/aendern` | `ADMIN` | `204`, Cookie gelöscht |
| `POST` | `/api/v1/admin/pin/aendern` | `ADMIN` | `204`, **alle** Sitzungen widerrufen |
| `POST` | `/api/v1/admin/user/anlegen` | `ADMIN` | `201` mit Id und Name |
| `POST` | `/api/v1/admin/user/entfernen` | `ADMIN` | `204` oder `409` |
| `POST` | `/api/v1/admin/user/blockieren` | `ADMIN` | `204`, sperrt und gibt frei |

**Neun Entscheidungen, die von der Anleitung abweichen oder sie ergänzen:**

1. **Die Reset-Endpunkte liegen unter `/auth/`, nicht unter `/admin/`** (Abschnitt 10 der
   Anleitung nannte `/admin/passwort/zuruecksetzen`). `/api/*/admin/**` verlangt `ROLE_ADMIN` –
   wer sein Passwort vergessen hat, trägt sie gerade nicht. Der Reset gehört zur **Anmeldung**.
   Er liegt trotzdem hinter der zentralen PIN: Er verschickt E-Mails, und die PIN ist der äussere
   Zaun aus A1. Preis: Wer beides vergisst, braucht die Datenbank (Betriebsdokumentation).
2. **Der SMTP-Zugang hängt an `fubo.mail.*`, nicht an `spring.mail.*`**, und die
   `JavaMailSender`-Bean entsteht in `MailConfig` von Hand. Spring Boots `Binder` reicht einen
   unauflösbaren Platzhalter **wörtlich** durch – über `spring.mail.host` liefe die Anwendung mit
   dem Rechnernamen `"${SMTP_HOST}"` durch. `MailConfig` prüft die vier Pflichtwerte und bricht
   mit einer Meldung ab, die die Umgebungsvariable benennt.
3. **Der Reset widerruft nur die Sitzungen des Admins**, nicht alle (offener Punkt 5). Alle
   widerruft ausschliesslich der Wechsel der **zentralen** PIN – dort ändert sich das gemeinsame
   Geheimnis. Dabei werden auch die Gastplätze freigegeben.
4. **Der Versuchszähler läuft mit `REQUIRES_NEW`** (offener Punkt 3) – die einzige gerechtfertigte
   Ausnahme im Projekt. Die Regel in `AGENT_SERVER.md` gilt dem Audit-Log, nicht Zählern. Der
   Protokolleintrag zum Fehlversuch sitzt dagegen **im Controller**, wo keine Transaktion läuft.
5. **Die zentrale PIN besteht aus genau vier Ziffern** (Festlegung des Haupt-Entwicklers).
   `PinBootstrap` erzeugt seine Ersatz-PIN deshalb ebenfalls vierstellig statt sechsstellig – eine
   längere liesse sich über ein darauf ausgelegtes Eingabefeld nicht mehr eingeben. **10 000
   Möglichkeiten tragen nur zusammen mit dem `BruteForceService`; dessen Grenzen dürfen nicht
   gelockert werden.** `/auth/pin/pruefen` schreibt der Eingabe weiterhin kein Format vor, damit
   ein abweichender Bestandswert eingebbar bleibt.
6. **Neue Profile bekommen die Skillvorgaben der Stufe `MITTEL`**, nicht Nullen. Ein Profil mit
   lauter Nullen bekäme in der Teamgenerierung ein Team ohne jede Stärke, ohne dass jemand den
   Grund sähe. Die Werte stammen aus `profil.gast_vorlage`; angegebene Kategorien überschreiben
   sie, auch teilweise.
7. **`entfernen` löscht hart, lehnt aber mit `409 PROFIL_IN_VERWENDUNG` ab**, sobald ein Beleg auf
   das Profil verweist. Offene Sitzungen werden vorher abgeräumt – sie sind flüchtig. Das
   Adminprofil ist in jedem Fall geschützt (`409 PROFIL_GESCHUETZT`).
8. **`blockieren` kennt beide Richtungen** (`blockieren: true|false`) und widerruft beim Sperren
   die Sitzungen des Profils. Ohne die Gegenrichtung käme der Admin an ein versehentlich
   gesperrtes Profil bis S3 nicht mehr heran.
9. **Der Wertebereich der Skillwerte wird im Service geprüft**, nicht nur vom Trigger
   `pruefe_skill_wertebereich`. Der Trigger allein brächte einen `500` statt einer Meldung, die
   die betroffene Kategorie und ihren Bereich nennt.

**Zum Zusammenspiel der Grenzen beim Reset.** Fünf Stellen sind 100 000 Möglichkeiten – für sich
zu wenig. Tragfähig wird die Bestätigungs-PIN erst durch die Summe: fünf Versuche je Vorgang,
15 Minuten Gültigkeit, drei Anforderungen je Stunde und Adresse, BCrypt statt Klartext, der
Endpunkt hinter der zentralen PIN und der zusätzliche Brute-Force-Zähler. **Keine dieser Grenzen
darf entfallen.**

**Beobachtung aus den Tests:** `max-versuche-ip` und `fubo.reset.max-versuche` stehen beide auf 5.
Im Betrieb greift deshalb die IP-Sperre (`429 PIN_GESPERRT`), *bevor* der Vorgangszähler erschöpft
ist – `409 RESET_UNGUELTIG` sieht in der Praxis nur, wer die Adresse wechselt oder wartet. Das ist
die gewünschte Staffelung, keine Panne.

### 6.6 Verifikation

**S2 und die S2b-Schritte 0 bis 7: `./mvnw clean verify` grün am 23.08.2026 – 148 Tests in 16
Klassen**, keine Fehler, keine Abbrüche, keine übersprungenen Tests. Die Anwendung startet auf
einer frischen Datenbank durch.

**Für die S2b-Schritte 8 bis 11 und den Nachtrag vom 29.08.2026 steht der grüne Lauf noch aus.
Erwartet: 190 Tests in 19 Klassen** – 184 aus S2b, fünf neue Fälle in
`AdminControllerTests` und einer in `AdminBootstrapTests` (Abschnitt 6.13). Der erste Lauf am 23.08.2026 brachte fünf Fehler –
**alle drei Ursachen lagen im neuen Code und sind behoben:**

1. **Zwei Tabellennamen in `SpielerRepository#istReferenziert` waren falsch** und führten zu
   `500 relation "spieltag.termin_serie" does not exist`. Sie waren aus den *Constraint-Namen*
   abgeleitet, und die stimmen hier nicht mit den Tabellennamen überein:
   `fk_terminserie_spieler` gehört zu `spieltag.terminserie`, `fk_kontingent_spieler` zu
   `spieltag.generierung_kontingent`. **Tabellennamen immer aus den `CREATE TABLE`-Zeilen der
   Migration lesen, nie aus einem Constraint-Namen.** Die Methode trägt den Hinweis jetzt im
   JavaDoc.
2. **`blockieren` benutzte `save` statt `saveAndFlush`.** Beim Sperren erzwang der anschliessende
   Sitzungswiderruf (`@Modifying(flushAutomatically = true)`) ein Flush, beim Freigeben gab es
   keinen – die Änderung blieb bis zum Ende der Transaktion im Persistence-Context stehen, und
   die Namensliste liest über nativen JDBC-Zugriff. **Merkregel für dieses Projekt: Wo JPA
   schreibt und natives SQL liest, muss geflusht werden.**
3. **`SMALLINT` kommt über `queryForMap` als `Integer` zurück, nicht als `Short`** – der Rohwert
   des Treibers. `queryForObject(..., Short.class)` wandelt dagegen um. Beide Wege sind richtig,
   nur nicht miteinander vergleichbar; der Test vergleicht jetzt über `Number#intValue`.


| Testklasse | Fälle | |
|---|---:|---|
| `MigrationTests` | 7 | |
| `SessionServiceTests` | 18 | |
| `ConfigServiceTests` | 2 | |
| `SessionAuthFilterTests` | 14 | |
| `SessionCookieFactoryTests` | 9 | |
| `SecurityConfigTests` | 24 | |
| `BruteForceServiceTests` | 10 | |
| `AuthControllerTests` | 10 | |
| `NamenControllerTests` | 10 | |
| `ApiVersionConfigTests` | 5 | |
| `AuditServiceTests` | 5 | |
| `GastControllerTests` | 8 | |
| `GastServiceTransaktionTests` | 2 | |
| `SessionControllerTests` | 10 | |
| `AdminBootstrapTests` | 9 | 8 + 1 (Schreibweise, 29.08.2026) |
| `AdminControllerTests` | 11 | 6 + 5 (Anmeldename, 29.08.2026) |
| `PasswortResetControllerTests` | 14 | neu |
| `ZugangsdatenControllerTests` | 6 | neu |
| `SpielerControllerTests` | 16 | neu |
| **Summe** | **190** | |

```bash
docker info > /dev/null                                    # muss durchlaufen
docker compose -f compose.dev.yml --env-file .env up -d
./mvnw clean verify
```

**Scheitert der Lauf, zuerst die Surefire-Berichte lesen, nicht die Maven-Zusammenfassung.**
Bei einem Kontextfehler meldet Spring Test jeden betroffenen Fall einzeln, aber nur der *erste*
Bericht je Kontextkonfiguration nennt die Ursache; alle anderen tragen
`ApplicationContext failure threshold (1) exceeded`. 115 Fehler bedeuten dann eine Ursache, nicht
115. Kürzester Weg: `grep -h 'Caused by' target/surefire-reports/*.txt | tail -1`. Am 23.08.2026
lautete die Antwort `Could not find a valid Docker environment` – Docker lief nicht. Gegenprobe
über die drei Klassen ohne Spring-Kontext (`SessionAuthFilterTests`, `SessionCookieFactoryTests`,
`BruteForceServiceTests`): Sind die grün, liegt es nicht am Anwendungscode.

Die manuelle Prüfliste steht vollständig in `S2b_UMSETZUNG.md`, Abschnitt 12.1; die
Bruno-Collection deckt sie ab.

### 6.13 Nachtrag – Anmeldename beim Admin-Login (29.08.2026)

**Auftrag:** Für die Anmeldung des Admins muss auch der Anmeldename eingegeben und gegen die
Datenbank validiert werden.

**Was sich geändert hat**

`POST /api/v1/auth/admin/anmelden` verlangt jetzt zwei Pflichtfelder statt einem:

```json
{ "anmeldename": "...", "passwort": "..." }
```

```
dto/auth/AdminLoginRequest        + anmeldename (@NotBlank, max 60), + bereinigterAnmeldename()
service/auth/AdminService         + anmeldedatenStimmen(name, passwort), + nameStimmt(...),
                                  + SpielerRepository im Konstruktor,
                                  passwortStimmt() auf passwortVergleichen() zurueckgefuehrt
service/auth/AdminBootstrap       findByName statt findByNameIgnoreCase,
                                  + pruefeSchreibweise (Startabbruch)
repository/profil/SpielerRepository  + findByName (exakt),
                                  findByNameIgnoreCase -> findAllByNameIgnoreCase (List)
controller/auth/AdminController   ruft anmeldedatenStimmen statt passwortStimmt,
                                  eigener Anzeigetext beim 401
common/error/Fehlercode           JavaDoc an ADMIN_PASSWORT_FALSCH (zwei Endpunkte, ein Code)
fubo-api.json                     AdminLoginRequest + anmeldename, Beispiel, 401-Beschreibung
README.md, .env.example           ADMIN_NAME ist zugleich der Anmeldename
```

**Keine Schemaänderung, keine Migration.** `V008` bleibt die letzte Migration.

**Fünf Entscheidungen**

1. **Der Anmeldename ist der Profilname des Adminprofils**, nicht eine neue Spalte
   `admin_konto.anmeldename`. Entscheidung des Haupt-Entwicklers. Der Wert steht bereits in
   `profil.spieler.name` (eindeutig über `uq_spieler_name`), stammt aus `ADMIN_NAME` und ist
   in `.env.example` seit jeher als *Kontoname, der kein Spielername sein muss* beschrieben.
   Eine eigene Spalte hätte eine Migration, eine zweite Umgebungsvariable und eine zweite
   Startprüfung gebraucht – für einen zweiten Namen desselben Kontos.
   **Folge, die in S3 gebraucht wird:** Wird das Adminprofil umbenannt
   (`/admin/user/bearbeiten`), ändert sich damit der Anmeldename. Das ist konsistent – es ist
   dasselbe Konto – und ausdrücklich gewollt: Die Änderung des Anmeldenamens durch den Admin
   ist als S3-Aufgabe bestätigt. Ab dann ist `ADMIN_NAME` in der `.env` **veraltet** und
   taugt nicht mehr als Auskunft über den gültigen Anmeldenamen; der Bootstrap liest den Wert
   ohnehin nur, solange kein Konto existiert. Die drei Auflagen für S3 stehen in
   `harness/tmp/S3_UMSETZUNG.md`, Abschnitt 3.3 – die wichtigste: **den neuen Namen getrimmt
   speichern**, sonst liesse er sich nie eingeben.
2. **Falscher Name und falsches Passwort sind nicht unterscheidbar.** Derselbe Code
   `ADMIN_PASSWORT_FALSCH`, derselbe Anzeigetext („Anmeldename oder Passwort ist nicht
   korrekt."). Ein eigener Code hätte den Namen über die Fehlermeldung erratbar gemacht und
   die zusätzliche Angabe entwertet.
3. **Der bestehende Fehlercode bleibt**, statt eines neuen `ADMIN_ANMELDUNG_FALSCH`.
   Entscheidung des Haupt-Entwicklers: Der Client-Track muss seine Auswertung nicht ändern,
   und `detail` ist ohnehin Anzeigetext, der sich ohne Vertragsänderung ändern darf. Der
   Controller setzt den präziseren Text über den zweiten `FachlicherFehler`-Konstruktor;
   `/admin/passwort/aendern` behält die Standardmeldung, weil dort tatsächlich nur das
   Passwort geprüft wird.
4. **Beide Teilprüfungen laufen immer** – `nameStimmt & passwortStimmt`, verknüpft mit `&`,
   nie mit `&&`. Ein Abbruch beim falschen Namen spart die BCrypt-Berechnung und macht den
   Endpunkt zu einem Zeitorakel: schnelle Ablehnung hiesse „Name falsch", langsame „Name
   richtig". **Wer diese Zeile später vereinfacht, hebt die Massnahme auf.**
5. **Der eingegebene Name geht nicht ins Audit-Log.** Der Eintrag zum Fehlversuch nennt
   weiterhin nur Endpunkt und Adresse. Ein Protokoll, das jede geratene Eingabe mitschreibt,
   sammelt fremde Daten ohne Nutzen und stellt einen Vertipper des Admins neben seinen echten
   Namen.

### Die Schreibweise: erst nachsichtig, dann zeichengenau (am selben Tag korrigiert)

Die erste Fassung verglich mit `equalsIgnoreCase` – aus Sorge vor einer Aussperrung, weil
`AdminBootstrap` das Profil bis dahin ebenfalls unempfindlich suchte (`findByNameIgnoreCase`).
**Der Haupt-Entwickler hat das umgedreht:** Der Anmeldename ist ein Anmeldemerkmal, die
Schreibweise ist über `ADMIN_NAME` eindeutig vorgegeben (ohne die Angabe bricht der Start
ohnehin ab), also wird sie auch geprüft.

**Das war keine Ein-Wort-Änderung.** `equals` allein hätte genau die Aussperrung erzeugt, gegen
die die Nachsicht gedacht war: Stand in der Datenbank „Beispielspieler 05" und in der `.env`
`beispielspieler 05`, übernahm der Bootstrap das vorhandene Profil – und der Betreiber konnte
sich anschliessend mit genau dem Wert **nicht** anmelden, den er selbst gesetzt hatte. Ohne
Rückweg: Der Passwort-Reset holt das Passwort zurück, nie den Namen.

Deshalb sichert der Bootstrap jetzt die Invariante zu, auf der der Login aufsetzt:

> **Der gespeicherte Profilname des Admins entspricht zeichengenau `ADMIN_NAME.trim()`.**

Drei Bausteine tragen sie:

1. **`findByName` statt `findByNameIgnoreCase`.** Nur ein zeichengenauer Treffer wird
   übernommen; sonst entsteht das Profil neu, mit genau der Schreibweise aus der Umgebung.
2. **`pruefeSchreibweise` bricht den Start ab**, wenn ein Profil allein in der Schreibweise
   abweicht – vor jeder Änderung, wie alle Abbruchprüfungen des Runners. Sonst legte der
   Bootstrap ein zweites, nahezu gleichnamiges Profil an: `uq_spieler_name` ist in PostgreSQL
   gross-/kleinschreibungsempfindlich und lässt „Admin" neben „admin" zu. In der Namensliste
   stünden dann zwei fast gleiche Einträge, einer davon ein technisches Konto. Der Abbruch
   kostet einen Neustart mit korrigierter `.env` – die Alternative kostet den Zugang.
3. **Die Gegenprobe läuft über eine Liste** (`findAllByNameIgnoreCase`), nicht über ein
   `Optional`. Existieren bereits zwei Schreibweisen nebeneinander, liefe ein `Optional` in
   eine `IncorrectResultSizeDataAccessException` – ein Startabbruch mit einer Meldung über
   Ergebnismengen statt über die Ursache. Die Meldung nennt jetzt alle gefundenen
   Schreibweisen.

**Randleerzeichen werden weiterhin entfernt**, und zwar im **DTO**
(`bereinigterAnmeldename()`), nicht im Service: Die Auslegung des Anfragekörpers gehört an die
API-Grenze, wie bei `GastAnmeldungRequest#bereinigterName()`. Das ist kein Widerspruch zur
zeichengenauen Prüfung – ein führendes Leerzeichen ist unsichtbar und nie beabsichtigt, eine
Schreibweise ist sichtbar und kann es sein.

**Fünf neue Testfälle in `AdminControllerTests`** (6 → 11): abweichende Schreibweise
(`401`), Randleerzeichen (`204`), falscher Name mit richtigem Passwort, Name eines *anderen*
Profils, fehlender Name (`400`). Die Tests lesen den Anmeldenamen über `adminName()` aus der
Datenbank statt ihn festzuschreiben – er stammt aus `ADMIN_NAME` in
`src/test/resources/application.yml`, und ein Test, der den Wert doppelt führt, bricht bei
jeder Änderung dort mit.

**In `AdminBootstrapTests`** (8 → 9) wurde `abweichendeSchreibweiseDesNamensGenuegt` in sein
Gegenteil verkehrt (`…BrichtDenStartAb`; prüft zusätzlich, dass kein zweites Profil
zurückbleibt und dass die Meldung beide Schreibweisen nennt); dazu kam
`exakteSchreibweiseUebernimmtDasVorhandeneProfil` als Gegenprobe über die Profilanzahl.
`unbekannterNameLegtDasProfilAn` sichert jetzt ausdrücklich zu, dass der Profilname
zeichengenau `ADMIN_NAME` entspricht – das ist die Invariante, auf der der Login aufsetzt.

**`ADMIN_NAME: Beispielspieler 12` in `src/test/resources/application.yml` trifft die
Demodaten zeichengenau**, der Kontextstart ist von der Umstellung also nicht betroffen. Wer
den Wert dort ändert, muss ihn zeichengenau aus `R__seed_beispielprofile.sql` übernehmen –
sonst bricht künftig jeder `@SpringBootTest` beim Kontextstart ab.

**Nicht betroffen:** `SecurityConfigTests` schickt `{}` und erwartet `400` bzw. `403` – beides
gilt unverändert. `/admin/passwort/aendern` prüft weiterhin nur das alte Passwort; dort ist der
Admin bereits angemeldet, ein Anmeldename wäre dort ohne Aussage.

**Bruno-Collection** (`~/Documents/bruno/fubo_server`): neue Umgebungsvariable `adminName` in
allen fünf Umgebungen (in `raspberry-pi` als Secret), Anfragekörper in „Admin anmelden",
„Admin Passwort falsch" und „Admin anmelden in falscher Stufe" ergänzt, neuer Fehlerfall
„Admin Anmeldename falsch" (seq 19). **`adminName` ist in jeder Umgebung noch auf
`BITTE_EINTRAGEN` gesetzt** und muss vor dem nächsten Lauf gefüllt werden.

## 7. Nächste Schritte

1. **`./mvnw clean verify` laufen lassen** – für die S2b-Schritte 8 bis 11 **und** den Nachtrag
   vom 29.08.2026. Erwartet: 190 Tests in 19 Klassen (Aufstellung in Abschnitt 6.6). - **Bestätigt**
2. **`adminName` in den Bruno-Umgebungen füllen** (steht überall auf `BITTE_EINTRAGEN`) –
   **zeichengenau wie `ADMIN_NAME` in der `.env`**, sonst scheitert jeder Admin-Request
   mit `401`.
3. **Manuelle Prüfliste abarbeiten** (`S2b_UMSETZUNG.md`, Abschnitt 12.1). Der Reset lässt sich
   nur mit echtem SMTP-Zugang durchspielen; die Bruno-Collection führt durch die Reihenfolge.
   **Zusätzlich zum Nachtrag:** „Admin anmelden" mit richtigem Namen, mit falschem Namen und
   mit abweichender Schreibweise – die drei Ablehnungen müssen in Code, Statuscode und
   Anzeigetext identisch sein.
4. **Client-Track über die Vertragsänderung informieren** (Abschnitt 4a): Das Anmeldeformular
   des Admins braucht ein zweites Pflichtfeld, sonst liefert der Endpunkt `400`.
5. **Danach S3** (Profile & Skills API). Aus S2b kommt dorthin die Bearbeitung bestehender
   Profile (`/admin/user/bearbeiten`: Name und Skillwerte ändern). Das Anlegen, Entfernen
   und Sperren steht bereits. **Der Anmeldename des Admins wird dort änderbar** – das ist
   dieselbe Operation wie das Umbenennen des Adminprofils (Weggabelung C der S3-Anleitung,
   bestätigt). Die drei Auflagen dazu stehen in `harness/tmp/S3_UMSETZUNG.md`, Abschnitt 3.3:
   den neuen Namen **getrimmt** speichern (sonst liesse er sich nie eingeben und der Admin
   sperrte sich aus), die Änderung der Schreibweise als echte Änderung behandeln, und die
   offene Frage klären, ob das Umbenennen die Adminsitzungen widerruft.
6. **Domainentscheidung – erledigt (09.08.2026).** Frontend und API liegen auf Subdomains **derselben
   registrierbaren Domain** (`app.<domain>` / `api.<domain>`). Damit gilt `SameSite=Lax` und
   `csrf.disable()` bleibt vertretbar; Abschnitt 5.4/5.5 der S2-Anleitung ist entsprechend festgelegt.
   Hintergrund: Das Frontend liegt auf Cloudflare Pages und war zunächst nur unter
   `<projekt>.pages.dev` erreichbar. `pages.dev` steht auf der Public Suffix List, ist für Browser also
   selbst eine registrierbare Domain – gegenüber `api.<domain>` wäre das cross-site gewesen, mit
   `SameSite=None; Secure`, zwingendem CSRF-Schutz und einem Session-Cookie, das Safari und der
   Chrome-Inkognito-Modus als Third-Party-Cookie blockieren. Gelöst über eine **Custom Domain** in
   Cloudflare Pages (reine Hosting-Konfiguration, kein Code). Offene Folgeaufgaben: Einrichtung der
   Custom Domain sowie der Umgang mit Pages-Preview-Deployments, in denen der Login bauartbedingt nicht
   funktioniert (`S2_UMSETZUNG.md`, Abschnitt 0.1 und offene Punkte 8/9).
7. **Offene Punkte für S3 und S5:** das Anlegen weiterer Gastplätze, wenn `anz_guests` über vier
   hinaus erhöht werden soll (offener Punkt 18), und die Frage, wie der Teamgenerator Profile ohne
   gepflegte Skillwerte behandelt (offener Punkt 20). **Punkt 20 ist durch S2b kleiner geworden:**
   Über `/admin/user/anlegen` entstandene Profile haben immer vollständige Skillwerte – die
   Vorgaben der Stufe `MITTEL`. Ungepflegt bleiben nur Profile aus einem Datenimport.
8. **Endpunktkontrakt – erledigt (22.08.2026), S2b nachgezogen (23.08.2026), Anmeldename
   nachgezogen (29.08.2026).** Er liegt als `server/fubo-api.json` auf der
   Repo-Wurzel und beschreibt alle 15 Endpunkte aus S2 und S2b vollständig (Abschnitt 4). Ab jetzt gilt:
   **Jede Vertragsänderung zuerst dort abbilden**, dann den Client-Track nachziehen. S3 bis S6
   tragen ihre Endpunkte jeweils bei Fertigstellung nach.
9. **Profildaten** (Vorgehen steht, nichts mehr zu entscheiden): Reale Daten liegen ausserhalb von
   `PRJ_FuBo/`, Pfad in `FUBO_LOCAL_SEED`, Einspielen über `scripts/seed-lokal.sh`. Der anonymisierte
   30er-Satz liegt in `scripts/data/`, der 12er-Demosatz läuft automatisch in Dev und Test.
10. **Deployment (S8):** Entwurf mit Dockerfile, Compose-Ergänzung, nginx-Block, Backup und Rollout liegt
   in `harness/tmp/S8_DEPLOYMENT.md`.

## 8. Weitere Anweisungen
- **Repository-Konventionen:** Repo-Wurzel ist `server/`. Gearbeitet wird auf dem Entwicklungsbranch
  **`dev`**; `main` bleibt der freigegebene Stand. Commit-Nachrichten nach Conventional Commits **ohne**
  Scope `(server)` – die Zuordnung ergibt sich aus dem Repository.
  *Hinweis:* Frühere Fassungen dieses Dokuments nannten Meilenstein-Branches
  (`feature/s1-datenmodell`). Praktiziert wird ein durchgehender `dev`-Branch; die Konvention ist hiermit
  daran angepasst.
- **Getrennte Repositories:** Änderungen an Server und Client können nicht in einem gemeinsamen Commit
  erfolgen. Vertragsänderungen daher immer zuerst in `server/fubo-api.json` abbilden und den
  Client-Track separat nachziehen.
- Ohne ausdrückliche Anweisung des Entwicklers nichts in `main` mergen/pushen; Feature-Branch erlaubt.
- `.env`-Dateien nie einchecken. Dokumentation in deutscher Sprache. **Keine realen Personennamen** in
  Code, Testdaten oder Dokumentation.
- Nach Abschluss eines Arbeitspakets kurze Verifikation durchführen und diesen Handoff aktualisieren
  (veraltete Fassung zuvor unter `server/harness/archive/` ablegen). 
  Zudem soll auch das zentrale Handoff in `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) entsprechend aktualisiert werden.
  *Hinweis:* `/PRJ_FuBo/harness/` liegt **außerhalb** dieses Repositories und wird nicht mit committet.
