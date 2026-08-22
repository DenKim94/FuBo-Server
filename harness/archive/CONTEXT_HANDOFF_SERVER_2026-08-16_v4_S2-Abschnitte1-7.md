# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) um den
> serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`. Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.

> Projektordner: `<Projektordner>/PRJ_FuBo`, Backend unter `server/`
> Git-Repository: **eigenständiges Repository mit Wurzel in `server/`** (GitHub, privat, `FuBo-Server`).
> **Kein Monorepo.** Das Frontend liegt in einem getrennten Repository (`FuBo-Client`, Ordner `client/`).
> Der übergeordnete Ordner `PRJ_FuBo/` sowie `PRJ_FuBo/harness/` sind bewusst **nicht** versioniert.
> Repository ist angelegt: `https://github.com/DenKim94/FuBo-Server.git` (privat).
> Stand: 16.08.2026, **S0 und S1 abgeschlossen**, **S2 in Arbeit (Abschnitte 1–7 sowie der PIN-Teil
> von 9 umgesetzt)**
> (16.08.2026: PIN-Login mit Brute-Force-Schutz, Audit-Log, Namensliste/-belegung/-auswahl und
> PIN-Bootstrap implementiert, siehe Abschnitt 6.6; anschliessend Versionierung der Schnittstelle
> über ein Pfadsegment, siehe Abschnitt 6.7, sowie Löschfrist und Transaktionskopplung des
> Audit-Logs, siehe Abschnitt 6.8; `./mvnw clean verify` am 16.08.2026 grün, 99 Tests)
> (Vorfassung archiviert unter `harness/archive/CONTEXT_HANDOFF_SERVER_2026-08-09_v3_S2-Abschnitte1-5.md`)

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
Siehe `AGENT_SERVER.md`, Abschnitt „Schnittstelle zum Frontend". Kernpunkte: REST/JSON, getrennte Origins
mit CORS-Allowlist (`allowCredentials`), HttpOnly-Session-Cookie, `401`/`403`-Semantik, DTOs ohne
Skillwerte für USER/GAST, Belegtstatus-Endpunkt zum Pollen, einheitliches Fehler-JSON. Der konkrete
Endpunktkontrakt (OpenAPI empfohlen) ist mit dem Client-Agenten abzustimmen und hier zu ergänzen.

## 5. Meilensteine & Aufwandsschätzung (Server)
Mid-Level-Entwickler, KI-gestützt, ca. 6,5 h/Woche.

| MS | Inhalt | Aufwand (h) |
|---|---|---|
| S0 | Backend-Setup: Spring Boot, Maven, Modulstruktur, `.gitignore`, Docker/Compose-Eintrag – **abgeschlossen** | 8 |
| S1 | Datenmodell: 3 Schemas, alle Tabellen/Constraints, Flyway-Migrationen, Seed (Kategorien, `gast_vorlage`, anonymisierte Beispielprofile), lokale Datenversorgung, Testcontainers-Grundgerüst – **abgeschlossen** | 15 |
| S2 | Auth & Session: Security-Filterchain, PIN-Login + Brute-Force-Schutz, `stage`-Erzwingung, opaker Token/HttpOnly, Zwei-Timer-Modell, Namensliste/-belegung, Online-Status, API-Vertrag der Auth-Endpunkte – **in Arbeit, Abschnitte 1–7 fertig (≈ 15 h)**, Anleitung `harness/tmp/S2_UMSETZUNG.md` | 23 |
| S2b | Admin-Passwort-Reset: `spring-boot-starter-mail`, 5-stellige PIN, Rate-Limit, Sitzungswiderruf | 6 |
| S3 | Profile & Skills API: Admin-CRUD, Rollen/Autorisierung, `configs` (Import der Referenzdaten entfällt – siehe Abschnitt 7.3) | 11 |
| S4 | Termine & Teilnahme API: Einzel/Serie, Teilnahme, `teilnehmer_version`, Min/Max + Warteschlange, Gast-Flow/`gast_slot` | 16 |
| S5 | Teamgenerator: `EXHAUSTIV` + `HEURISTIK`, Zielfunktion inkl. Torwart-Gewicht, Kontingent/Seed/Snapshot, Auswechselspieler, Tests | 18 |
| S6 | Ergebnis & Audit API: „erster Eintrag gilt", Admin-Korrektur, `audit_log` | 8 |
| S7 | Hallenmodus: E-Mail-Absage, 48-Stunden-Regel, serverseitige Deaktivierung | 6 |
| S8 | Härtung, Integrationstests, Deployment (Docker/nginx/Cloudflared), API-Doku/OpenAPI – Entwurf liegt vor: `harness/tmp/S8_DEPLOYMENT.md` | 14 |

**Korrektur der S2-Schätzung (08.08.2026):** Die Aufschlüsselung in `S2_UMSETZUNG.md` summiert sich auf
**23 h** statt der ursprünglich veranschlagten 18 h. Die Abweichung entsteht vor allem in der
Filterchain (4 h), beim Brute-Force-Schutz (3 h) und beim API-Vertrag zum Frontend (1 h) – alle drei
waren in der Top-down-Schätzung zu grob angesetzt. Die Summe der Einzelschritte ist verlässlicher als die Gesamtschätzung, deshalb steht hier
der höhere Wert. S1 wurde entsprechend von 14 auf 15 h angehoben, S3 von 12 auf 11 h gesenkt, da der
Import der Referenzdaten dort entfällt.

**Summe Server ≈ 125 h → ca. 19–20 Kalenderwochen** bei 6,5 h/Woche (Spanne ±15 %). Kritischer Pfad: S2
und S5. Abhängigkeit: S2b setzt einen SMTP-Zugang voraus (Anbieter/Absenderadresse festlegen).

## 6. Aktueller Code-Zustand (Stand 08.08.2026, Branch `dev`, Commit `1d90f67`)

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

### 6.2 Offene Punkte vor S2 (Stand nach Abschnitt 3 in Klammern)

1. **Paketstruktur ist unvollständig.** Angelegt (und leer) sind `common/{config,error}`,
   `controller/{auth,config,ergebnis,profil,team,termin}`, `service/{…}`, `repository`, `utils`.
   Es fehlen Pakete für **JPA-Entities** und **DTOs**. Vorschlag in `S2_UMSETZUNG.md`, Abschnitt 1.1.
   *(Erledigt für `domain/{auth,config}`, `repository/{auth,config}`, `service/{auth,config}` und
   `common/config`. `dto/` entsteht mit den Endpunkten ab Abschnitt 6.)*
2. **Es gibt noch keine `SecurityFilterChain`.** *(Erledigt in S2, Abschnitt 5 – siehe 6.5. Zur
   Log-Zeile „Using generated security password" siehe die Korrektur am Ende von 6.5.)*
   Solange keine existiert, konfiguriert Spring Security
   einen Notbehelf: In-Memory-Benutzer `user` mit zufälligem UUID-Passwort, bei jedem Start neu, plus
   Deny-by-default auf allen Endpunkten. Das ist der Grund für die Log-Zeile
   „Using generated security password". Kein Konfigurationswert, gehört **nicht** in die `.env`.
   Die Auto-Konfiguration darf **nicht** über
   `@SpringBootApplication(exclude = SecurityAutoConfiguration.class)` abgeschaltet werden – das
   entfernte die Deny-by-default-Haltung, die die Architekturregeln verlangen.
3. **`/actuator/health` muss in der Filterchain freigegeben werden** (`permitAll`). Der
   Container-Healthcheck aus `harness/tmp/S8_DEPLOYMENT.md` ruft
   `http://localhost:8080/actuator/health` auf. Mit aktiver Filterchain antwortet der Endpunkt sonst mit
   `401`, `curl -fsS` schlägt fehl, Docker markiert den Container dauerhaft als `unhealthy` – und
   `depends_on: condition: service_healthy` wäre nie erfüllt. Der Fehler fällt erst beim Deployment auf
   und wird dann an der falschen Stelle gesucht. Gleichzeitig darf der Endpunkt von aussen nicht
   erreichbar sein; das regelt nginx, nicht die Anwendung.
4. **`.gitignore`:** `*.local.sql` ist ergänzt, `/db-local/` fehlt noch (geringe Priorität, seit die
   Realdaten ausserhalb des Projektordners liegen).
5. **Bootstrap fehlt:** `profil.zugangsdaten` und `profil.admin_konto` sind bewusst leer. Ohne
   Startlogik gibt es keine zentrale PIN und keinen Admin. `FUBO_INITIAL_PIN` steht bereits in der
   `.env`. Gehört zu S2, siehe `S2_UMSETZUNG.md`, Abschnitt 9.
   *(Zentrale PIN erledigt am 16.08.2026 über `PinBootstrap`, siehe 6.6. Das Admin-Konto steht noch
   aus; die Auswahl erfolgt über `ADMIN_NAME`/`ADMIN_EMAIL` aus der `.env`, sonst Startabbruch.)*

### 6.3 Abweichungen, die während S1 bewusst festgelegt wurden

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

### 6.4 Zwei Fallstricke aus S1, die dokumentiert bleiben sollten

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

### 6.5 S2, Abschnitte 1–5 umgesetzt (09.08.2026)

Sitzungsverwaltung mit Zwei-Timer-Modell (A14), einheitliches Fehlerformat und Security-Filterchain.
Entstandene Dateien:

```
src/main/java/de/fubo/appserver/
  domain/auth/        Session (@Entity), Stage, Rolle, GastStufe, AktiveSitzung (record)
  domain/config/      AppConfig (@Entity, alle 14 Spalten), AlgorithmType
  repository/auth/    SessionRepository, SessionRepositoryCustom, SessionRepositoryImpl
  repository/config/  AppConfigRepository
  service/auth/       SessionService
  service/config/     ConfigService
  common/config/      SecurityConfig, CorsConfig, FuboProperties, SchedulingConfig
  common/security/    SessionAuthFilter, SessionCookieFactory, AuthorizationExceptionHandler
  common/error/       Fehlercode, FachlicherFehler, GlobalExceptionHandler
  utils/              TokenGenerator
src/main/resources/
  db/migration/       V008__session_rolle_optional.sql
  application.yml     spring.profiles.default=dev, fubo.session.*, fubo.cors.*
  application-dev.yml lokale Abweichungen (cookie-secure=false, Vite-Origin)
src/test/java/de/fubo/appserver/
  service/auth/       SessionServiceTests        (13 Faelle)
  service/config/     ConfigServiceTests         (2 Faelle)
  common/security/    SessionAuthFilterTests     (11 Faelle, ohne Spring-Kontext)
                      SessionCookieFactoryTests  (9 Faelle, ohne Spring-Kontext)
  common/config/      SecurityConfigTests        (18 Faelle, MockMvc)
```

**Datenmodell geändert:** `V008` macht `profil.session.rolle` optional und ergänzt
`ck_session_rolle_stage` (ab `PROFILE_AUTHENTICATED` ist die Rolle Pflicht). `V003` hatte die Spalte
als `NOT NULL` angelegt; eine Sitzung in `PIN_VERIFIED` hat aber noch keine Rolle, das Anlegen wäre an
der Spalte gescheitert. `ck_session_identitaet` sah den Fall für `spieler_id`/`gast_name` bereits vor,
die Rolle wurde dort übersehen. Im Änderungsprotokoll von `/PRJ_FuBo/harness/AGENT.md` als **Punkt 14**
nachgetragen.

**Behobene Defekte:**

1. Eine erste Fassung der `Session`-Entity führte `stage` als `String` und behielt
   `@Enumerated(EnumType.STRING)`. Hibernate lehnt die Annotation auf einem Nicht-Enum-Typ ab und bricht
   den Kontextstart ab. Behoben durch echte Aufzählungstypen.
2. `token_hash` ist in `V003` als `CHAR(64)` angelegt, ein `String` wird von Hibernate aber auf
   `VARCHAR` abgebildet. `ddl-auto=validate` vergleicht den JDBC-Typcode und brach den Start ab
   („found [bpchar (Types#CHAR)], but expecting [varchar(64)]"). Behoben durch
   `@JdbcTypeCode(SqlTypes.CHAR)` an der Entity – das Schema bleibt unverändert, denn falsch war die
   Abbildung, nicht die Spalte.
3. `GlobalExceptionHandler` importierte `java.nio.file.AccessDeniedException` statt
   `org.springframework.security.access.AccessDeniedException`. Der Handler wäre nie ausgelöst worden,
   und Spring Securitys `AccessDeniedException` aus `@PreAuthorize` (ab S3) wäre im
   `Exception`-Auffangzweig gelandet – aus einem `403` wäre ein `500` geworden.

Beide Mapping-Fehler äussern sich als Kaskade von `UnsatisfiedDependencyException` beim Start,
beziehungsweise als fehlschlagender `MigrationTests`-Lauf. Nur die **erste** Logzeile benennt jeweils
die Ursache. Die daraus abgeleiteten Regeln stehen jetzt verbindlich in `AGENT_SERVER.md`, Abschnitt
„JPA-Mapping-Regeln (verbindlich ab S2)".

**Paketstruktur präzisiert:** `S2_UMSETZUNG.md` nannte in Abschnitt 1.1 `entity/`, der Beispielcode
darunter `domain/`. Massgeblich ist `AGENT_SERVER.md`: das Paket heisst **`domain`** und enthält neben
Entities auch schlanke Wertobjekte wie `AktiveSitzung`. Zusätzlich neu ist **`common/security`** – der
Filter, die Cookie-Fabrik und der Fehler-Writer sind Laufzeitverhalten und kein Konfigurationscode;
`common/config` enthält nur noch Beans und Property-Bindung. Beides ist in `AGENT_SERVER.md`
nachgezogen.

**Neu in `AGENT_SERVER.md`: Abschnitt „JPA-Mapping-Regeln (verbindlich ab S2)".** Er hält fest, was
`ddl-auto=validate` tatsächlich prüft (Spaltenexistenz und JDBC-Typcode, **nicht** die Zuordnung) und
enthält eine Tabelle Spaltentyp → Feldtyp. Damit ist auch vorgemerkt, dass die beiden `CHAR(1)`-Spalten
aus `V006` (`teameinteilung.team`, `ergebnis.sieger`) beim Anlegen der Entities in S5 und S6 als
`Character` abzubilden sind – dann wählt Hibernate von sich aus `CHAR` und es braucht keine
Zusatzannotation.

**Drei Umsetzungsentscheidungen, die von der Anleitung abweichen:**

- `anlegen()` nutzt `saveAndFlush` statt `save`. Die Prüfung läuft über nativen JDBC-Zugriff und sieht
  nur, was in der Datenbank steht. Bei `IDENTITY` setzt Hibernate das `INSERT` ohnehin sofort ab –
  sich darauf zu verlassen wäre eine unsichtbare Kopplung an die Generierungsstrategie.
- `ConfigService` liest ohne Zwischenspeicher. Ein Cache bräuchte Invalidierung ab S3; der Zugriff ist
  ein Primärschlüssel-Lookup auf eine einzeilige Tabelle in derselben Transaktion.
- Die Drosselung des Aktivitäts-`UPDATE` aus Abschnitt 3.2 ist **nicht** umgesetzt. Sie bräuchte
  zwingend einen zweiten, rein lesenden Pfad, sonst hielte der Filter gültige Sitzungen für ungültig.
  Offener Punkt 4 bleibt damit offen.

**Korrektur zu „Using generated security password" (09.08.2026):** Diese Logzeile erscheint auch bei
korrekt greifender Filterchain. Sie stammt von `UserDetailsServiceAutoConfiguration`, die nur bei
einer `AuthenticationManager`-, `AuthenticationProvider`-, `UserDetailsService`- oder
`AuthenticationManagerResolver`-Bean zurückweicht – eine eigene `SecurityFilterChain` genügt ihr
nicht. Frühere Fassungen dieses Handoffs und der S2-Anleitung führten das Verschwinden der Zeile als
Prüfkriterium auf; das war falsch und ist in beiden Dokumenten korrigiert.

Der angelegte Benutzer ist funktionslos (`httpBasic` und `formLogin` sind aus). Damit die Zeile nicht
dauerhaft in die Irre führt, ist in `AppServerApplication` ausschliesslich diese eine
Autokonfiguration ausgenommen:
`@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)`. Das ist etwas anderes
als der weiterhin untersagte Ausschluss von `SecurityAutoConfiguration`.

**Ob die Filterchain greift, wird am Verhalten geprüft:** Ein Aufruf ohne Cookie muss `401` mit
`application/problem+json` und dem Feld `"code":"SESSION_UNGUELTIG"` liefern – dieses Feld kann nur
aus dem eigenen `AuthorizationExceptionHandler` stammen. Zusätzlich: kein `WWW-Authenticate`-Header
(sonst wäre `httpBasic` aktiv) und kein `Location`-Header (sonst `formLogin`).

**Verifikation (nachgetragen 16.08.2026):** Der Testlauf ist Teil des Gesamtergebnisses in
Abschnitt 6.9 und grün. Die beiden dort vorab genannten Unsicherheiten haben sich **nicht**
bestätigt und sind damit erledigt: Der von Hand über `SecurityMockMvcConfigurers.springSecurity()`
aufgebaute MockMvc funktioniert unter Boot 4, und der handgeschriebene Ersatz für den
`SessionService` in `SessionAuthFilterTests` läuft ohne Mockito. Beide Bauweisen bleiben, weil sie
sich bewährt haben und keine zusätzliche Abhängigkeit brauchen.

### 6.6 S2, Abschnitte 6 und 7 umgesetzt (16.08.2026)

PIN-Login mit Brute-Force-Schutz, Audit-Log, Namensliste mit Belegtstatus und Namensauswahl mit
Token-Rotation. Zusätzlich der PIN-Teil des Bootstraps aus Abschnitt 9 – ohne ihn ist
`profil.zugangsdaten` leer und der PIN-Endpunkt nur über Tests erreichbar.

```
src/main/java/de/fubo/appserver/
  domain/auth/        Zugangsdaten (@Entity)
  domain/profil/      Spieler (@Entity), NamensEintrag (record)
  domain/audit/       AuditAktion
  repository/auth/    ZugangsdatenRepository
  repository/profil/  SpielerRepository, SpielerRepositoryCustom, SpielerRepositoryImpl
  repository/audit/   AuditLogRepository        (JdbcClient, kein Spring-Data-Repository)
  service/auth/       PinService, BruteForceService, NamenService, PinBootstrap
  service/audit/      AuditService
  common/config/      ZeitConfig (Clock-Bean); FuboProperties um BruteForce erweitert
  utils/              ClientIpErmittler
  dto/auth/           PinLoginRequest, NameAuswahlRequest
  dto/profil/         NameOption
  controller/auth/    AuthController, NamenController
src/main/resources/
  application.yml     fubo.brute-force.* ergaenzt
src/test/java/de/fubo/appserver/
  controller/auth/    AuthControllerTests   (8 Faelle)
                      NamenControllerTests  (7 Faelle)
  service/auth/       BruteForceServiceTests (11 Faelle, ohne Spring-Kontext)
```

**Keine Schemaänderung.** `V008` bleibt die letzte Migration; alle benötigten Tabellen stammen aus
`V001`–`V007`.

**Endpunkte (Stand jetzt):**

| Methode | Pfad | Erlaubte Stufe | Antwort |
|---|---|---|---|
| `POST` | `/api/v1/auth/pin/pruefen` | offen | `204` + Cookie (`PIN_VERIFIED`) |
| `GET` | `/api/v1/auth/users/lesen` | `PIN_VERIFIED` und höher | `200` Namensliste mit Belegtstatus, **ohne Skillwerte** |
| `POST` | `/api/v1/auth/user/waehlen` | nur `PIN_VERIFIED` | `204` + **neues** Cookie (Token-Rotation) |

Die Pfade sind seit Abschnitt 6.7 versioniert; die Tabelle zeigt bereits die endgültige Form.

**Pfadentscheidung (16.08.2026):** Die Anleitung nannte in Abschnitt 10.4 `/api/auth/namen` und
`/api/auth/name`, `SecurityConfig` dagegen `/users` und `/user`. Maßgeblich sind die Pfade aus
`SecurityConfig` – dort hängt die Autorisierung, und die Regeln waren samt Tests bereits abgenommen.
Abschnitt 10.4 der Anleitung ist entsprechend korrigiert.

**Sieben Umsetzungsentscheidungen, die von der Anleitung abweichen:**

1. **`BruteForceService` kennt weder Datenbank noch Audit-Log.** `fehlversuchZaehlen` liefert einen
   `boolean` („dieser Versuch hat gesperrt"); der Controller entscheidet, was protokolliert wird.
   Dadurch ist der Dienst ohne Spring-Kontext testbar.
2. **Neue `Clock`-Bean (`common/config/ZeitConfig`).** Sperrdauern von 1 bis 15 Minuten sind mit
   `Thread.sleep` nicht prüfbar. Zeitpunkte, die in der Datenbank entstehen, werden weiterhin gegen
   `now()` der Datenbank geprüft.
3. **Grenzwerte als Konfiguration** (`fubo.brute-force.*`) mit `@DefaultValue`-Vorgaben: 5 Fehlversuche
   je IP, 30 global, 15 Minuten Fenster, Sperrdauern 1/5/15 Minuten. Fehlt der Block, gelten die
   Vorgaben – der Schutz ist nie versehentlich aus.
4. **Der globale Zähler wird bei erfolgreicher Anmeldung nicht geleert**, der IP-Zähler schon. Sonst
   könnte ein verteilter Angriff sich hinter der normalen Nutzung verstecken.
5. **`AuditLogRepository` nutzt `JdbcClient` statt einer Entity** – `details` ist `jsonb`, und ein
   Audit-Log wird nur angehängt. Die Umwandlungen im `INSERT` sind ausdrücklich notiert
   (`CAST(... AS jsonb)`), weil PostgreSQL den Typ eines `NULL`-fähigen Parameters sonst nicht
   bestimmen kann. *(Zum Transaktionsverhalten siehe 6.8 – die dort getroffene Entscheidung hat den
   ursprünglichen `try/catch` abgelöst.)*
6. **Unbekanntes oder inaktives Profil → `404 INHALT_NICHT_GEFUNDEN`** statt `409`. Das ist neu im
   Vertrag (Abschnitt 10.2 der Anleitung). Die Unterscheidung ist unkritisch: Die gültigen Ids stehen
   jeder Sitzung in `PIN_VERIFIED` ohnehin in der Namensliste.
7. **`PinBootstrap` schreibt die PIN nur dann ins Log, wenn er sie selbst erzeugt hat.** Stammt sie aus
   `FUBO_INITIAL_PIN`, kennt der Betreiber sie bereits – sie gehört dann nicht zusätzlich ins Log.

**Anpassung bestehender Tests, die beim nächsten Lauf auffällt:** Die Platzhalter-Endpunkte in
`SecurityConfigTests` mussten für `/api/auth/pin`, `/api/auth/users` und `/api/auth/user` entfallen.
Es gibt jetzt echte Controller für diese Pfade; wären beide vorhanden, meldete Spring
„Ambiguous mapping" und der Kontext startete gar nicht erst. Die betroffenen Fälle erwarten nun `400`
statt `200` – der Aufruf hat die Filterchain passiert und ist erst an der Bean Validation *im*
Controller hängen geblieben. Zusätzlich brauchten `SessionAuthFilterTests` und
`SessionCookieFactoryTests` ein drittes Argument beim Bau von `FuboProperties`.

**Verifikation:** grün, siehe Abschnitt 6.9.

**Was in S2 noch fehlt:** Gast-Login (Abschnitt 8), Admin-Konto im Bootstrap (Abschnitt 9),
`GET /api/auth/session`, `POST /api/auth/renew` und `POST /api/auth/logout` (Abschnitt 10.4) sowie der
OpenAPI-Kontrakt (Abschnitt 10). Offene Punkte 11 bis 15 der Anleitung.

### 6.7 Versionierung der Schnittstelle (16.08.2026)

Alle fachlichen Endpunkte liegen jetzt unter `/api/{version}/<bereich>/<ressource>/<aktion>`.
Umgesetzt mit der Bordausstattung von **Spring Framework 7** (`ApiVersionConfigurer`,
`@RequestMapping(version = …)`) – kein eigener Mechanismus.

```
common/config/      ApiVersionConfig (neu)   Pfadsegment-Strategie, Konstanten API_PRAEFIX und V1
controller/auth/    AuthController, NamenController   auf versionierte Pfade umgestellt
common/config/      SecurityConfig            Matcher auf /api/*/… umgestellt
src/test/…/common/config/  ApiVersionConfigTests (5 Faelle, neu)
```

**Drei Festlegungen mit Begründung:**

1. **Version als Präfix an Segment-Index 1**, nicht als Suffix. Der Index gilt global für die ganze
   Anwendung. Bei einem Suffix (`/api/auth/users/lesen/v1`) läge er hier bei 4, beim späteren
   `/api/admin/spieler/12/lesen/v1` aber bei 5 – ein fester Index kann beides nicht treffen. Ein
   Suffix bräuchte einen eigenen `ApiVersionResolver`.
2. **Ein `Predicate<RequestPath>` grenzt die Versionierung auf `/api/` ein.** Ohne das erwartet der
   Resolver bei *jeder* Anfrage ein Versionssegment und beantwortet `/actuator/health` mit `400` –
   der Container wäre dauerhaft `unhealthy`. Derselbe Fallstrick wie bei der Filterchain in 6.5, nur
   an anderer Stelle. `ApiVersionConfigTests` sichert das ab.
3. **Aktionssegment im Pfad** (`/lesen`, `/waehlen`, `/pruefen`, `/anmelden`), obwohl die HTTP-Methode
   dieselbe Auskunft gibt. Gewinn: ein eigener Pfad je Operation, damit unabhängig versionierbar und
   in Log und nginx-Regeln eindeutig. Preis: Die Filterchain kann `GET` und `POST` nicht mehr über
   denselben Pfad trennen; die Methodenangabe bleibt in den Regeln trotzdem stehen.

**Verhalten bei Abweichungen:** `/api/1/…` wird wie `/api/v1/…` gelesen (der voreingestellte
`SemanticApiVersionParser` überspringt führende Nicht-Ziffern), `/api/v99/…` liefert `400`, ein
fehlendes Versionssegment liefert `404` – nicht `400`, weil der Pfad ohne das Segment schlicht auf kein
Mapping mehr passt. Ein `MissingApiVersionException` kann bei der Pfadstrategie also nie auftreten.

**Regeln der Filterchain verwenden ein Sternchen für das Versionssegment**
(`/api/*/auth/users/lesen`, `/api/*/admin/**`). Welche Versionen es gibt, entscheidet
`ApiVersionConfig`, nicht die Autorisierung; eine unbekannte Version wird erst danach abgelehnt, weil
das `HandlerMapping` hinter der Filterchain läuft.

**Vor dem Sprung auf v2 zu klären** (offener Punkt 16 der Anleitung): Die Mappings tragen feste
Versionen (`version = "1"`). Ein solches Mapping bedient keine v2-Anfrage – jeder unveränderte
Endpunkt müsste für v2 erneut deklariert oder auf die Basislinie `"1+"` umgestellt werden. Die
Basislinien-Semantik war in Spring 7 noch strittig, deshalb heute die feste Version.

**Verbindlich nachgetragen in `AGENT_SERVER.md`**, Abschnitt „Schnittstelle zum Frontend": die drei
Regeln zur Versionierung.

### 6.8 Audit-Log: Löschfrist und Transaktionskopplung (16.08.2026)

Zwei Entscheidungen des Haupt-Entwicklers, beide umgesetzt und in `AGENT_SERVER.md` als verbindliche
Regeln nachgetragen.

```
common/config/      FuboProperties        um den Block Audit erweitert (aufbewahrung-tage, Vorgabe 90)
repository/audit/   AuditLogRepository    + loescheAelterAls(OffsetDateTime)
service/audit/      AuditService          + geplanter Auftrag alteEintraegeEntfernen (taeglich 03:45)
                                          - try/catch entfernt
src/main/resources/ application.yml       fubo.audit.aufbewahrung-tage: 90
src/test/…/service/audit/  AuditServiceTests (5 Faelle, neu)
```

**1. Löschfrist 90 Tage, konfigurierbar über `fubo.audit.aufbewahrung-tage`.**

Grund ist der Personenbezug: Bei einem PIN-Fehlversuch steht die Client-IP im Eintrag, und nach der
DSGVO gilt Speicherbegrenzung. Nebeneffekt: Die Tabelle bleibt klein – sie ist die einzige, die sonst
dauerhaft wächst, weil Einträge ausschließlich angehängt werden.

Die Frist liegt bewusst **als Property und nicht in `configs.app_config`**. Sie ist eine Betriebs- und
Rechtsgröße, keine fachliche Einstellung, und hat im Admin-Bereich (S3) nichts zu suchen: Ein Admin
soll die Nachvollziehbarkeit seiner eigenen Änderungen nicht per Formular verkürzen können. Zum
Vergleich – die Aufbewahrung abgelaufener Sitzungen steht als Konstante im `SessionService`, weil dort
gar kein Anlass zum Verstellen besteht.

**Bewusst in Kauf genommen:** Die Frist gilt einheitlich für alle Aktionen. Eine Ergebniskorrektur aus
S6 ist damit nach 90 Tagen ebenfalls nicht mehr belegbar. Eine Staffelung je `aktion` wäre eine
Änderung an genau einer Stelle – offener Punkt 17 der Anleitung, vor S6 zu entscheiden.

**2. Der Audit-Eintrag wird mit einer fehlgeschlagenen Änderung zurückgerollt.**

Ausbreitung bleibt `REQUIRED`; **`REQUIRES_NEW` ist damit untersagt.** Ein Protokolleintrag belegt eine
*vollzogene* Änderung. Bleibt er nach einem Rollback stehen, behauptet das Protokoll etwas, das nie
passiert ist – schlechter als eine Lücke.

Folge: Das frühere `try/catch` um den Schreibvorgang ist **entfernt**. Es sollte verhindern, dass ein
Protokollfehler die Fachlogik abbricht, war innerhalb einer gemeinsamen Transaktion aber wirkungslos –
ein fehlgeschlagenes `INSERT` markiert die Transaktion bereits als „rollback-only". Das Abfangen
verschob den Fehler nur bis zum Commit und ersetzte die Ursache durch eine
`UnexpectedRollbackException`.

Beim PIN-Endpunkt ändert sich nichts: Dort existiert keine umgebende Transaktion, der Fehlversuch
*ist* das Ereignis und wird sofort festgeschrieben.

**`AuditServiceTests` trägt bewusst kein `@Transactional`** – zwei der fünf Fälle prüfen gerade das
Transaktionsverhalten, eine umgebende Test-Transaktion machte das Ergebnis vorbestimmt. Die Zeilen
werden über ein Präfix in `akteur_bezeichnung` von Hand aufgeräumt. Der Rollback-Fall schlägt fehl,
sobald jemand auf `REQUIRES_NEW` umstellt – das ist seine eigentliche Aufgabe.

### 6.9 Verifikation (16.08.2026)

**Verifikation: `./mvnw clean verify` ist am 16.08.2026 grün durchgelaufen.**

| Testklasse | Fälle |
|---|---:|
| `MigrationTests` | 7 |
| `SessionServiceTests` | 14 |
| `ConfigServiceTests` | 2 |
| `SessionAuthFilterTests` | 11 |
| `SessionCookieFactoryTests` | 9 |
| `SecurityConfigTests` | 19 |
| `BruteForceServiceTests` | 10 |
| `AuthControllerTests` | 9 |
| `NamenControllerTests` | 8 |
| `ApiVersionConfigTests` | 5 |
| `AuditServiceTests` | 5 |
| **Summe** | **99** |

Alle Fälle ohne Fehler, Abbrüche oder übersprungene Tests. Damit sind auch die drei Punkte
bestätigt, die sich vorab nur aus der Dokumentation ableiten liessen: Das `Predicate` an
`usePathSegment` hält `/actuator/health` unversioniert erreichbar, ein fehlendes Versionssegment
endet in `404` statt in einem `500` aus dem Auffangzweig des `GlobalExceptionHandler`, und der
voreingestellte `SemanticApiVersionParser` liest `/api/1/…` wie `/api/v1/…`.

Die vorab geschätzten Testzahlen in den Abschnitten 6.5 bis 6.8 lagen bei einigen Klassen um ein bis
zwei Fälle daneben; massgeblich ist die Tabelle oben.

## 7. Nächste Schritte

1. **S2 fortsetzen** – Auth und Session. Schrittweise Anleitung: `harness/tmp/S2_UMSETZUNG.md`.
   Erledigt: Paketstruktur → Entities/Repositories → Token und Hashing → Session-Service mit
   Zwei-Timer-Modell → Fehlerformat → Security-Filterchain (1–5, siehe 6.5) → PIN-Login mit
   Brute-Force-Schutz (6) → Namensliste/-belegung/-auswahl (7) → PIN-Teil des Bootstraps (9, siehe 6.6).
   Als Nächstes: **Gast-Login (Abschnitt 8)** → Admin-Konto im Bootstrap (9) →
   `GET /api/auth/session`, `/renew`, `/logout` (10.4) → API-Vertrag als OpenAPI (10).
   *Der Stand ist verifiziert* (Abschnitt 6.9, 99 grüne Tests). Für einen Testlauf muss Docker
   laufen (Testcontainers, `postgres:17`).
2. **Domainentscheidung – erledigt (09.08.2026).** Frontend und API liegen auf Subdomains **derselben
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
3. **S2b** (Admin-Passwort-Reset) im Anschluss. Voraussetzung: SMTP-Zugang festlegen und
   `spring-boot-starter-mail` ergänzen.
4. **Parallel:** Endpunktkontrakt als OpenAPI unter `src/main/resources/openapi/fubo-api.yaml`
   beginnen – Quelle der Wahrheit für den Client-Track. Die in S2 entstehenden Auth-Endpunkte sind der
   erste Inhalt.
5. **Profildaten** (Vorgehen steht, nichts mehr zu entscheiden): Reale Daten liegen ausserhalb von
   `PRJ_FuBo/`, Pfad in `FUBO_LOCAL_SEED`, Einspielen über `scripts/seed-lokal.sh`. Der anonymisierte
   30er-Satz liegt in `scripts/data/`, der 12er-Demosatz läuft automatisch in Dev und Test.
6. **Deployment (S8):** Entwurf mit Dockerfile, Compose-Ergänzung, nginx-Block, Backup und Rollout liegt
   in `harness/tmp/S8_DEPLOYMENT.md`.
## 8. Weitere Anweisungen
- **Repository-Konventionen:** Repo-Wurzel ist `server/`. Gearbeitet wird auf dem Entwicklungsbranch
  **`dev`**; `main` bleibt der freigegebene Stand. Commit-Nachrichten nach Conventional Commits **ohne**
  Scope `(server)` – die Zuordnung ergibt sich aus dem Repository.
  *Hinweis:* Frühere Fassungen dieses Dokuments nannten Meilenstein-Branches
  (`feature/s1-datenmodell`). Praktiziert wird ein durchgehender `dev`-Branch; die Konvention ist hiermit
  daran angepasst.
- **Getrennte Repositories:** Änderungen an Server und Client können nicht in einem gemeinsamen Commit
  erfolgen. Vertragsänderungen daher immer zuerst in der OpenAPI-Datei im Server-Repo abbilden und den
  Client-Track separat nachziehen.
- Ohne ausdrückliche Anweisung des Entwicklers nichts in `main` mergen/pushen; Feature-Branch erlaubt.
- `.env`-Dateien nie einchecken. Dokumentation in deutscher Sprache. **Keine realen Personennamen** in
  Code, Testdaten oder Dokumentation.
- Nach Abschluss eines Arbeitspakets kurze Verifikation durchführen und diesen Handoff aktualisieren
  (veraltete Fassung zuvor unter `server/harness/archive/` ablegen). 
  Zudem soll auch das zentrale Handoff in `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) entsprechend aktualisiert werden.
  *Hinweis:* `/PRJ_FuBo/harness/` liegt **außerhalb** dieses Repositories und wird nicht mit committet.
