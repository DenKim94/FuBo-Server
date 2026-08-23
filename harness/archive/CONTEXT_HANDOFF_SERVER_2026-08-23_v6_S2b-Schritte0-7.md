# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) um den
> serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`. Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.

> Projektordner: `<Projektordner>/PRJ_FuBo`, Backend unter `server/`
> Git-Repository: **eigenständiges Repository mit Wurzel in `server/`** (GitHub, privat, `FuBo-Server`).
> **Kein Monorepo.** Das Frontend liegt in einem getrennten Repository (`FuBo-Client`, Ordner `client/`).
> Der übergeordnete Ordner `PRJ_FuBo/` sowie `PRJ_FuBo/harness/` sind bewusst **nicht** versioniert.
> Repository ist angelegt: `https://github.com/DenKim94/FuBo-Server.git` (privat).
> Stand: 23.08.2026, **S0, S1 und S2 abgeschlossen und verifiziert; S2b in den Schritten 0 bis 7
> umgesetzt, aber noch nicht verifiziert** (siehe Abschnitte 6.12 und 6.13)
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
> Als Nächstes: **`./mvnw clean verify` für S2b** (Abschnitt 6.13) – dafür fehlt noch
> `SMTP_ABSENDER` in der `.env`. Danach das zweite S2b-Paket: Spielerverwaltung durch den Admin
> (Abschnitt 8 der Anleitung), Aufräumjob (Abschnitt 9) und die Tests (Abschnitt 11).
>
> (Vorfassung archiviert unter `harness/archive/CONTEXT_HANDOFF_SERVER_2026-08-22_v5_S2-vollstaendig.md`)

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

**Umfang:** ausschliesslich das, was tatsächlich umgesetzt ist – die acht Auth- und
Sitzungsendpunkte aus S2 sowie seit dem 23.08.2026 die vier Endpunkte der Zugangsdatenpflege aus
S2b (Abschnitt 6.12). Spekulative Endpunkte wären ein Vertrag über etwas, das es nicht gibt;
S3 bis S6 tragen ihre Endpunkte jeweils bei Fertigstellung nach.

Inhaltliche Kernpunkte (unverändert, Herleitung in `AGENT_SERVER.md`, Abschnitt „Schnittstelle zum
Frontend"): REST/JSON, getrennte Origins mit CORS-Allowlist (`allowCredentials`),
HttpOnly-Session-Cookie, `401`/`403`-Semantik, DTOs ohne Skillwerte für USER/GAST,
Belegtstatus-Endpunkt zum Pollen, einheitliches Fehler-JSON nach RFC 9457.

Neu im Vertrag seit dem 22.08.2026:
- **`X-FuBo-Kein-Refresh: true`** als Anfrageheader für Hintergrundaufrufe (Abschnitt 6.10).
- **`Retry-After`** und das Feld `wartesekunden` beim `429` des PIN-Endpunkts.
- **`absolutGueltigBis`** in der Sitzungsauskunft, zusätzlich zu `gueltigBis`.

## 5. Meilensteine & Aufwandsschätzung (Server)
Mid-Level-Entwickler, KI-gestützt, ca. 6,5 h/Woche.

| MS | Inhalt                                                                                                                                                                                                                                                                                                                          | Aufwand (h) |
|---|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---|
| S0 | Backend-Setup: Spring Boot, Maven, Modulstruktur, `.gitignore`, Docker/Compose-Eintrag – **abgeschlossen**                                                                                                                                                                                                                      | 8 |
| S1 | Datenmodell: 3 Schemas, alle Tabellen/Constraints, Flyway-Migrationen, Seed (Kategorien, `gast_vorlage`, anonymisierte Beispielprofile), lokale Datenversorgung, Testcontainers-Grundgerüst – **abgeschlossen**                                                                                                                 | 15 |
| S2 | Auth & Session: Security-Filterchain, PIN-Login + Brute-Force-Schutz, `stage`-Erzwingung, opaker Token/HttpOnly, Zwei-Timer-Modell, Namensliste/-belegung, Gast-Login, Admin-Login, Bootstrap, Sitzungsendpunkte, Online-Status, API-Vertrag – **abgeschlossen und verifiziert (148 Tests)**, Anleitung `harness/tmp/S2_UMSETZUNG.md` | 23 |
| S2b | Zugangsdatenpflege: Passwort-Reset per E-Mail (5-stellige PIN, Rate-Limit, Sitzungswiderruf), Passwortänderung im angemeldeten Zustand, Änderung der zentralen PIN – **Schritte 0 bis 7 umgesetzt, Verifikation offen**; offen bleiben Spielerverwaltung (Abschnitt 8), Aufräumjob (9) und Tests (11). Anleitung `harness/tmp/S2b_UMSETZUNG.md` | 10 |
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

### 6.4 S2, Abschnitte 1–5 umgesetzt (09.08.2026)

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

### 6.5 S2, Abschnitte 6 und 7 umgesetzt (16.08.2026)

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

**Verifikation:** grün, siehe Abschnitt 6.8.

### 6.6 Versionierung der Schnittstelle (16.08.2026)

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

### 6.7 Audit-Log: Löschfrist und Transaktionskopplung (16.08.2026)

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

### 6.8 Verifikation (16.08.2026)

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

### 6.9 S2, Abschnitte 8 bis 10 umgesetzt (22.08.2026)

Gast-Login, Admin-Konto im Bootstrap, die drei fehlenden Sitzungsendpunkte sowie zwei
Vertragsentscheidungen (offene Punkte 7 und 13). Damit ist S2 inhaltlich vollständig; es fehlt nur
noch die Verifikation (Abschnitt 6.11).

```
src/main/java/de/fubo/appserver/
  domain/auth/        AdminKonto (@Entity, neu)
                      AktiveSitzung  + gueltigBis, absolutGueltigBis
  repository/auth/    GastSlotRepository (JdbcClient, neu)
                      AdminKontoRepository (neu)
                      SessionRepository    + aufGastSetzen, existiertAktiveGastSitzungMit
                      SessionRepositoryCustom/Impl + pruefen (rein lesend)
  repository/profil/  SpielerRepository    + existsByNameIgnoreCase, findByNameIgnoreCase, findByRolle
  service/auth/       GastService (neu), AdminBootstrap (neu)
                      SessionService + pruefen, erneuern, abmelden, auskunft
                      PinBootstrap   + @Order
  dto/auth/           GastAnmeldungRequest, SitzungInfo (neu)
  controller/auth/    GastController, SessionController (neu)
  common/security/    SessionAuthFilter + Header-Auswertung
  common/config/      CorsConfig + allowedHeaders/exposedHeaders
  common/error/       FachlicherFehler + Wartesekunden
                      GlobalExceptionHandler + Retry-After, handleHttpMessageNotReadable
src/main/resources/   -
fubo-api.json         Endpunktkontrakt (OpenAPI 3.1), Repo-Wurzel (neu)
.env.example          ADMIN_*-Block kommentiert
README.md             Verweis auf den Kontrakt, Abschnitt "Erste Anmeldung"
src/test/resources/application.yml   ADMIN_NAME/ADMIN_EMAIL/ADMIN_PASSWORD ergaenzt
src/test/java/de/fubo/appserver/
  controller/auth/    GastControllerTests (neu), SessionControllerTests (neu)
  service/auth/       GastServiceTransaktionTests (neu), AdminBootstrapTests (neu)
```

**Keine Schemaänderung.** `V008` bleibt die letzte Migration; alle benötigten Tabellen
(`gast_slot`, `admin_konto`) stammen aus `V003` und sind seit `V007` mit vier Plätzen geseedet.

**Endpunkte (vollständiger Stand S2):**

| Methode | Pfad | Erlaubte Stufe | Antwort |
|---|---|---|---|
| `POST` | `/api/v1/auth/pin/pruefen` | offen | `204` + Cookie (`PIN_VERIFIED`) |
| `GET` | `/api/v1/auth/users/lesen` | `PIN_VERIFIED` und höher | `200` Namensliste mit Belegtstatus, **ohne Skillwerte** |
| `POST` | `/api/v1/auth/user/waehlen` | nur `PIN_VERIFIED` | `204` + **neues** Cookie |
| `POST` | `/api/v1/auth/gast/anmelden` | nur `PIN_VERIFIED` | `204` + **neues** Cookie |
| `GET` | `/api/v1/auth/session/lesen` | `PIN_VERIFIED` und höher | `200` Sitzungsauskunft |
| `POST` | `/api/v1/auth/session/erneuern` | `PIN_VERIFIED` und höher | `204` + **neues** Cookie |
| `POST` | `/api/v1/auth/session/beenden` | `PIN_VERIFIED` und höher | `204`, Cookie gelöscht |

**Neun Entscheidungen, die von der Anleitung abweichen oder sie ergänzen:**

1. **Die drei Sitzungsendpunkte sind ab `PIN_VERIFIED` erlaubt**, nicht erst ab
   `PROFILE_AUTHENTICATED`. Abschnitt 10.4 nannte pauschal „angemeldet"; das ist zu eng. Lädt jemand
   die Seite zwischen PIN-Eingabe und Namenswahl neu, bekäme er sonst `403` und müsste die PIN erneut
   eingeben, obwohl seine Sitzung gültig ist – genau der Fall, für den `/session/lesen` überhaupt
   existiert. Dasselbe gilt für das Abmelden: Einen angefangenen Login abzubrechen muss möglich sein.
2. **`AktiveSitzung` trägt jetzt `gueltigBis` und `absolutGueltigBis`.** Beide kommen aus der
   Abfrage, welche die Sitzung bei jedem Request ohnehin prüft; ein zweiter Lesezugriff nur für die
   Anzeige wäre Verschwendung, und die Werte kämen von derselben Uhr, aber zu einem anderen Zeitpunkt.
   `absolutGueltigBis` steht zusätzlich zum Entwurf in 10.5 im DTO: Ohne den Wert böte das Frontend
   kurz vor der Obergrenze ein „Verlängern" an, das nichts mehr bewirkt.
3. **Der Anzeigename wird nur im Sitzungsendpunkt nachgeschlagen**, nicht in der Sitzungsprüfung.
   Ein `JOIN` auf `profil.spieler` in der Prüfung liefe bei *jedem* Request – für einen Wert, den
   genau ein Endpunkt braucht.
4. **`GastSlotRepository` nutzt `JdbcClient` statt einer Entity**, wie zuvor schon
   `AuditLogRepository`. Auf `gast_slot` finden ausschliesslich bedingte Massen-Updates statt; ein
   einzelner Datensatz wird nie geladen, geändert und zurückgeschrieben. Eine Entity mit `@Version`
   wäre hier sogar nachteilig: Optimistic Locking meldet den Konflikt erst beim Schreiben und
   verlangt eine Wiederholung, während das bedingte `UPDATE` den Wettlauf ohne Wiederholung
   entscheidet. Die `version`-Spalte wird von Hand fortgeschrieben.
5. **Die Obergrenze der Gäste wirkt über `id <= anz_guests`**, nicht über die Zahl der Zeilen. Damit
   ist `configs.app_config.anz_guests` sofort wirksam, ohne Datensätze anzulegen oder zu löschen.
   Bewusste Grenze: Eine Erhöhung über die vier vorhandenen Zeilen hinaus bleibt wirkungslos – das
   Anlegen weiterer Plätze gehört zum Admin-Bereich in S3 (neuer offener Punkt 18).
6. **Der Gastname wird gegen aktive Gastsitzungen *und* gegen alle Profile geprüft** (`409
   NAME_BELEGT`), unabhängig von Gross- und Kleinschreibung. Ein Gast, der sich wie ein angelegtes
   Profil nennt, wäre in Teilnehmerliste und Teameinteilung nicht mehr davon zu unterscheiden.
   Geprüft wird auch gegen inaktive Profile – sie können jederzeit wieder aktiviert werden. Es bleibt
   dieselbe schmale Restlücke wie bei der Namensauswahl; die Folge wäre zwei gleiche Namen in der
   Liste, kein Datenverlust.
7. **Das Abmelden liegt im `SessionService`, nicht im `GastService`.** Freigabe des Platzes und
   Widerruf der Sitzung gehören in *eine* Transaktion – fielen sie auseinander, wäre der Platz frei
   und die Sitzung weiter gültig. Die Reihenfolge ist festgelegt: erst freigeben, dann widerrufen,
   denn `gast_slot.session_id` zeigt auf die Sitzung.
8. **Der PIN-Endpunkt meldet eine bestehende Sitzung jetzt ab, statt sie nur zu widerrufen.** War
   sie eine Gastsitzung, hielt sie einen der vier Plätze – ein blosser Widerruf liess ihn bis zum
   nächtlichen Aufräumlauf besetzt. Bei vier Plätzen fällt das sofort auf.
9. **`handleHttpMessageNotReadable` ist überschrieben.** Unlesbares JSON oder ein unbekannter
   Aufzählungswert lieferten zuvor zwar `400`, aber ohne das Feld `code`. Das Frontend hätte damit
   zwei Fehlerformate zu unterscheiden – genau das, was der einheitliche Vertrag vermeiden soll. Die
   Meldung von Jackson nennt Klassennamen und Feldpfade und geht deshalb ins Log, nicht in die Antwort.

**Admin-Bootstrap (Abschnitt 9, Rest erledigt).** Auswahl über `ADMIN_NAME`, `ADMIN_EMAIL` und
`ADMIN_PASSWORD`. Fehlt eine der drei Angaben und existiert noch kein Konto, **bricht der Start ab**
und die Meldung nennt alle fehlenden Werte auf einmal – sonst startet der Betreiber dreimal, um
dreimal einen weiteren fehlenden Wert zu erfahren. Entscheidung des Haupt-Entwicklers vom 22.08.2026
gegen die Alternative „Zufallspasswort erzeugen und einmalig loggen": Ein willkürlich gewählter Admin
wäre ein stilles Sicherheitsproblem, und anders als bei der zentralen PIN gibt es für das
Adminpasswort mit dem Reset per E-Mail (S2b) einen zweiten Weg, der genau die Adresse braucht, die
dann ebenfalls fehlen könnte.

Der Runner ist idempotent und setzt ein geändertes Passwort **nicht** zurück; die drei Variablen
werden nur beim ersten Start gebraucht. Zusätzlich prüft er, ob bereits ein *anderes* Profil die
Rolle `ADMIN` trägt, und meldet das verständlich – sonst scheiterte das `UPDATE` am partiellen
Unique-Index `uq_spieler_genau_ein_admin`, dessen Meldung den Index nennt und nicht die Ursache.

**Nachtrag vom selben Tag: Der Bootstrap legt das Profil an, wenn es fehlt.** Die erste Fassung
brach ab, wenn kein Profil mit `ADMIN_NAME` existierte – auf einer frischen Datenbank ist
`profil.spieler` aber leer, der Name konnte dort nie passen, und der Erststart war ein Zweischritt
aus „Profile einspielen" und „starten". Der Abbruch gilt jetzt der fehlenden *Angabe*, nicht der
fehlenden *Zeile*.

Die naheliegende Alternative – das Adminprofil über eine Flyway-Migration mit dem Platzhalter
`${ADMIN_NAME}` – wurde **verworfen**, aus vier Gründen:

1. **Eine Migration muss auf jeder Installation dasselbe Ergebnis erzeugen.** Mit einem Platzhalter
   erzeugt sie bei *gleicher Prüfsumme* unterschiedliche Daten; Flyway kann die Abweichung nicht
   bemerken, weil die Prüfsumme nur den Dateiinhalt abdeckt.
2. **Migrationen sind unveränderlich.** Der Name bliebe dauerhaft in der Git-Historie – die Regel
   „keine realen Personennamen" gilt für Migrationen ausdrücklich ohne Ausnahme.
3. **Ein Platzhalter ohne Vorgabewert koppelt die Migration an Anwendungsgeheimnisse.** Sobald
   `ADMIN_PASSWORD` oder `FUBO_INITIAL_PIN` nach dem Erststart aus der Umgebung entfernt werden –
   was ausdrücklich vorgesehen ist –, scheitert schon die Auflösung, und die Anwendung startet gar
   nicht mehr.
4. **Der Testlauf bräche.** V009 legte in der Testumgebung ein zweites `Beispielspieler 12` an,
   bevor `R__seed_beispielprofile.sql` läuft; dessen `ON CONFLICT DO NOTHING` überspränge die Zeile,
   und `beispielprofileSindGeladen` fände statt 60 nur 55 Skillzeilen.

**Ein Fallstrick, der dabei aufgefallen ist:** Die Datei hiess zunächst `V009_admin_profil.sql` mit
**einem** Unterstrich. Flyway verlangt `V<nnn>__<beschreibung>.sql` mit doppeltem Unterstrich als
Trenner und **ignoriert nicht passende Namen in der Voreinstellung stillschweigend** – kein Fehler,
kein INFO-Log. Die Migration lief nie, und der Startabbruch blieb unverändert stehen. Deshalb ist
jetzt `spring.flyway.validate-migration-naming: true` gesetzt; damit bricht der Start stattdessen
mit einer benennenden Meldung ab. Der Block `spring.flyway.placeholders` ist wieder entfernt.

**Was das neu angelegte Profil noch nicht hat: Skillwerte.** Sie entstehen über das Admin-CRUD in S3
oder über `scripts/seed-lokal.sh`. Für den Teamgenerator ist damit vor S5 zu klären, wie er Spieler
ohne vollständige Skillwerte behandelt – neuer offener Punkt 20. Der Fall ist nicht auf den Admin
beschränkt: Jedes über S3 neu angelegte Profil hat zunächst keine Skillzeilen.

**Folge für den Testlauf:** Ohne die drei Werte käme kein einziger `@SpringBootTest` mehr hoch. Sie
stehen deshalb in `src/test/resources/application.yml`; als Adminprofil dient bewusst
`Beispielspieler 12`, weil die Testklassen über `ORDER BY name LIMIT 1` beziehungsweise `OFFSET 1`
auf die ersten beiden Profile zugreifen und dort die Rolle `USER` erwarten. `MigrationTests`
entzieht die Rolle im Test `zweiterAdminWirdAbgelehnt` zuvor – sonst scheiterte bereits das erste
`INSERT`, und der Test wäre zwar grün, prüfte aber die falsche Aussage.

**Offener Punkt 7 entschieden (22.08.2026): Das Polling verlängert die Sitzung nicht mehr.**
Ein Aufruf mit dem Header `X-FuBo-Kein-Refresh: true` läuft über einen rein lesenden Prüfpfad
(`SessionRepositoryCustom#pruefen`): Weder wandert `gueltig_bis` nach hinten noch wird
`letzte_aktivitaet_am` fortgeschrieben. Damit misst „15 Minuten Inaktivität" den Nutzer und nicht den
offenen Browser-Tab. Drei Punkte dazu:

- **Der Header ist eine Bitte, kein Sicherheitsmerkmal.** Er kommt vom Client und lässt sich nicht
  prüfen. Missbrauch schadet nur dem Absender: Wer ihn an jeden Aufruf hängt, lässt seine eigene
  Sitzung früher ablaufen. Die umgekehrte Richtung ist nicht erreichbar, weil `absolut_gueltig_bis`
  unabhängig davon gilt.
- **Nur der Wert `true` zählt**, alles andere gilt als normaler Aufruf. Ein Tippfehler führt damit
  zum bisherigen Verhalten und nicht zu Sitzungen, die unerwartet ablaufen.
- **Der Header musste in die CORS-Allowlist.** `allowedHeaders` stand auf `Content-Type`; ohne die
  Ergänzung hätte der Browser bereits den Preflight abgelehnt – und zwar bevor die Anwendung den
  Aufruf überhaupt sieht. Derselbe Fallstrick wie bei `allowCredentials`: Beide Seiten müssen
  zusammenpassen, sonst sucht man den Fehler lange auf der falschen Seite.

Die Bedingungen der beiden Prüfpfade sind wortgleich, stehen aber zweimal im SQL. Das ist Absicht:
Ein `UPDATE` wertet seine WHERE-Klausel unter einer Zeilensperre aus, ein `SELECT` nicht – eine
geteilte Konstante würde suggerieren, die beiden Anweisungen seien dasselbe.

**Offener Punkt 13 entschieden (22.08.2026): Die Restwartezeit ist maschinenlesbar.**
`FachlicherFehler` trägt ein optionales Feld `wartesekunden`; der `GlobalExceptionHandler` übersetzt
es in den Header `Retry-After` (RFC 9110) **und** in ein gleichnamiges Feld des Problem-Details.
Doppelt geführt mit Absicht: Der Header ist die genormte Form, die auch ein Zwischenspeicher
auswertet; das Feld im Körper erspart dem Frontend den Zugriff auf die Header, der bei einer
Cross-Origin-Antwort ohne `Access-Control-Expose-Headers` gar nicht möglich wäre. `Retry-After` ist
zusätzlich in `exposedHeaders` freigegeben. Der Rückgabetyp des Handlers musste dafür von
`ProblemDetail` auf `ResponseEntity<ProblemDetail>` wechseln – Header lassen sich nur darüber setzen.

**Offener Punkt 11 erledigt: Gast-Slots im Aufräumjob.** `alteSitzungenEntfernen` gibt jetzt zuerst
die Plätze abgelaufener oder widerrufener Sitzungen frei. Das ist nicht nur Kosmetik gegen volllaufende
Plätze: `fk_gast_slot_session` hat kein `ON DELETE`, ein `DELETE` auf einer noch referenzierten
Sitzung scheiterte mit einer Fremdschlüsselverletzung – und der gesamte Aufräumlauf bräche ab, nicht
nur dieser eine Datensatz.

### 6.10 Adminprofil als technisches Konto, Admin-Login (22.08.2026, nach dem Erststart)

Beim ersten echten Start fiel auf, dass das Adminprofil begrifflich unklar war: `admin_konto`
verlangt ein Profil, aber der Admin ist nicht zwangsläufig ein Mitspieler. **Entscheidung des
Haupt-Entwicklers:** Das Adminprofil ist ein *technisches Konto*. Es wird aus Teamgenerierung und
Spielerübersicht ausgeschlossen; seine Skillwerte stehen deshalb auf 0.

```
domain/audit/       AuditAktion          + ADMIN_LOGIN_FEHLVERSUCH, ADMIN_ANGEMELDET
common/error/       Fehlercode           + ADMIN_PASSWORT_FALSCH
repository/profil/  SpielerRepository    + nullwerteAnlegen
                    SpielerRepositoryImpl  Namensliste filtert rolle <> 'ADMIN'
service/auth/       AdminService (neu)   passwortStimmt, sitzungAufAdminHeben
                    AdminBootstrap       + Skillwerte 0
                    NamenService         lehnt das Adminprofil ab
dto/auth/           AdminLoginRequest (neu)
controller/auth/    AdminController (neu)  POST /api/{version}/auth/admin/anmelden
common/config/      SecurityConfig       + Regel fuer den neuen Pfad
fubo-api.json                            + Endpunkt, Schema, Fehlercode
```

**Der Ausschluss zog einen neuen Login-Weg nach sich.** Zur Laufzeit entstand `ROLE_ADMIN` bis dahin
an **genau einer** Stelle: `NamenService#waehleName` übernimmt die Rolle aus dem gewählten Profil.
Nimmt man das Adminprofil aus der Namensliste, käme niemand mehr an Adminrechte — das Admin-Dashboard
und das Profil-CRUD aus S3 wären blockiert. Der Adminzugang braucht deshalb eine eigene zweite Stufe.

Das Datenmodell sah das ohnehin vor: `admin_konto.passwort_hash` ist `NOT NULL`, und A22 spricht von
einem *vergessenen Passwort* — ein Passwort, das nie zum Anmelden dient, wäre sinnlos.
`admin_konto.spieler_id` liefert genau die Id, die in die Sitzung eingetragen wird; laut `AGENT.md`
existiert die Spalte, damit sich „nach dem Login ermitteln lässt, welches Profil zum Konto gehört".

**Sechs Festlegungen mit Begründung:**

1. **Die zentrale PIN bleibt auch für den Admin Pflicht.** Der Endpunkt ist ausschliesslich in der
   Stufe `PIN_VERIFIED` erreichbar, wie Namensauswahl und Gast-Login. Die PIN grenzt den Kreis der
   Zugreifenden ein (A1), das Passwort die Rechte innerhalb dieses Kreises — zwei verschiedene
   Fragen, die beide beantwortet werden müssen.
2. **Übertragen wird nur das Passwort, keine Kennung.** Es gibt genau einen Admin
   (`ck_admin_konto_singleton`); ein Benutzername wäre ein Feld ohne Auswahl. Weniger übertragen
   heisst auch: weniger, das in einem Protokoll landen kann.
3. **Derselbe Brute-Force-Zähler wie am PIN-Endpunkt.** Es ist derselbe Absender, der dieselbe
   Anwendung angreift; wer fünf Admin-Passwörter rät, soll anschliessend auch keine PINs mehr
   durchprobieren können. Sichtbare Folge: Die Ablehnung trägt den Code `PIN_GESPERRT`, obwohl sie
   auf ein Passwort antwortet. Das ist die ehrliche Bezeichnung derselben Sperre — ein zweiter Code
   für dieselbe Sperre wäre die schlechtere Auskunft. Preis: Fünf Vertipper beim Adminpasswort
   kosten auch beim PIN-Login eine Minute Wartezeit.
4. **Keine Belegtprüfung.** Bei der Namensauswahl verhindert sie, dass zwei Personen denselben Namen
   belegen. Hier gibt es nur eine Person; eine zweite Anmeldung abzulehnen, solange die erste
   Sitzung läuft, sperrte den Admin nach einem Browserabsturz bis zum Sitzungsablauf aus — Schaden
   ohne Nutzen.
5. **Der Erfolg wird protokolliert, nicht nur der Fehlversuch** (`ADMIN_ANGEMELDET` mit
   `akteur_spieler_id`). Der Adminzugang ist der einzige mit erhöhten Rechten, und spätere
   Adminaktionen (S3, S6) lassen sich nur dann einer konkreten Sitzung zuordnen, wenn deren Beginn
   im Protokoll steht.
6. **Der Ausschluss wird an beiden Grenzen geprüft.** Die Namensliste filtert `rolle <> 'ADMIN'`,
   *und* `NamenService#waehleName` lehnt das Adminprofil erneut ab — mit `404`, wie ein fehlendes
   oder inaktives Profil. Der Endpunkt nimmt eine Id entgegen, nicht einen Eintrag der Liste; ohne
   die zweite Prüfung bliebe der Ausschluss reine Anzeige, und wer die Id kennt, bekäme
   `ROLE_ADMIN` ohne das Admin-Passwort. `404` statt `403`, damit der Statuscode nicht verrät, dass
   es die Id gibt.

**Zu den Skillwerten 0.** Der Bootstrap legt je *aktiver* Kategorie eine Zeile mit dem Wert 0 an —
die Liste kommt aus `profil.skill_kategorie` und nicht aus dem Code, sonst liefe sie auseinander,
sobald eine Kategorie hinzukommt. `ON CONFLICT DO NOTHING` macht den Aufruf wiederholbar und lässt
vorhandene Werte unangetastet. Der Wert 0 passt in jede Kategorie, auch in Torwart mit dem Bereich
0 bis 3.

**Wichtig für S5:** Die 0 ist **kein Ersatz für den Ausschluss**. Geriete das Adminprofil doch in
eine Generierung, bekäme sein Team einen Spieler ohne jede Stärke — die Teams wären schlechter
ausgeglichen, ohne dass jemand den Grund sähe. Der Ausschluss steht deshalb als verbindliche Regel
in `AGENT_SERVER.md`: Jede Abfrage, die Mitspieler aufzählt, filtert `rolle <> 'ADMIN'`.

**Offener Punkt 20 bleibt bestehen**, jetzt enger gefasst: Er betrifft nicht mehr das Adminprofil,
sondern Profile, die das Admin-CRUD in S3 anlegt und deren Skillwerte noch nicht gepflegt sind.

```bash
docker compose -f compose.dev.yml --env-file .env up -d
./mvnw clean verify
```

**Erwartet: 148 Tests in 16 Klassen** (bisher 99, also 49 zusätzliche Fälle). Die Zahl ist gezählt, nicht geschätzt; frühere Schätzungen
lagen je Klasse um ein bis zwei Fälle daneben. Nach dem Lauf sind die **tatsächlichen** Zahlen aus
`server/target/surefire-reports/*.txt` hier einzutragen.

| Testklasse | Fälle | Änderung |
|---|---:|---|
| `MigrationTests` | 7 | Fall `zweiterAdminWirdAbgelehnt` angepasst |
| `SessionServiceTests` | 18 | +4 (Aufräumjob/Gastplätze, lesender Pfad, Ablaufzeitpunkte) |
| `ConfigServiceTests` | 2 | – |
| `SessionAuthFilterTests` | 14 | +3 (Header-Auswertung) |
| `SessionCookieFactoryTests` | 9 | – |
| `SecurityConfigTests` | 24 | +5 (Sitzungsendpunkte, Adminzugang), Platzhalter `gast/anmelden` entfernt |
| `BruteForceServiceTests` | 10 | – |
| `AuthControllerTests` | 10 | +1 (`Retry-After`) |
| `NamenControllerTests` | 10 | +2 (Adminprofil weder in der Liste noch über seine Id wählbar) |
| `ApiVersionConfigTests` | 5 | – |
| `AuditServiceTests` | 5 | – |
| `GastControllerTests` | 8 | neu |
| `GastServiceTransaktionTests` | 2 | neu, ohne `@Transactional` |
| `SessionControllerTests` | 10 | neu |
| `AdminBootstrapTests` | 8 | neu |
| `AdminControllerTests` | 6 | neu |
| **Summe** | **148** | |

**Behobener Defekt im Testcode (erster Lauf, 22.08.2026).** Zwei Fälle scheiterten mit
`EmptyResultDataAccessException`: `GastControllerTests.ohneSelbsteinschaetzungGiltMittel` und
`SessionControllerTests.erneuernVerschiebtDieHarteObergrenzeNicht`. Beide riefen das Hilfsmittel
`sitzungsIdZu(token)` **nach** einem Aufruf auf, der den Token rotiert – der alte Hash findet dann
keine Zeile mehr. Kein Anwendungsfehler, im Gegenteil: Genau das belegt, dass die Rotation greift.
Behoben, indem die Id vor dem Aufruf einmal aufgelöst und behalten wird; die `session.id` überdauert
die Rotation. Das Hilfsmittel trägt den Hinweis jetzt im JavaDoc, weil es harmlos aussieht.

Zusätzlich von Hand zu prüfen (die Liste steht vollständig in `S2_UMSETZUNG.md`, Abschnitt 12):

| Prüfpunkt | Erwartung |
|---|---|
| Erststart ohne `ADMIN_*` in der `.env` | Startabbruch mit einer Meldung, die alle fehlenden Variablen nennt |
| Erststart auf **leerer** Datenbank mit vollständigen `ADMIN_*` | Startmeldung „Adminprofil neu angelegt", `profil.spieler` enthält die Zeile mit `rolle = 'ADMIN'` |
| Erststart mit bereits vorhandenem Profil | Startmeldung „Vorhandenes Profil … uebernommen", keine zusätzliche Zeile |
| Erststart mit vollständigen `ADMIN_*` | `profil.admin_konto` hat genau eine Zeile, das genannte Profil trägt `rolle = 'ADMIN'` |
| Zweiter Start | keine Änderung am Passwort-Hash, keine Meldung |
| Fünf Gäste anmelden | der fünfte erhält `409` mit `"code":"KEIN_GAST_SLOT_FREI"` |
| `SELECT * FROM profil.gast_slot` | genau vier Zeilen, `belegt` passend zu den offenen Gastsitzungen |
| `POST /api/v1/auth/session/beenden` als Gast | Platz wieder frei, `widerrufen_am` gesetzt |
| `GET /api/v1/auth/session/lesen` mit und ohne `X-FuBo-Kein-Refresh: true` | `gueltig_bis` wandert nur ohne den Header |
| Sechsmal falsche PIN | `429` mit Header `Retry-After` und Feld `wartesekunden` |
| Preflight mit `Access-Control-Request-Headers: X-FuBo-Kein-Refresh` | `200` und der Header in `Access-Control-Allow-Headers` |
| `GET /api/v1/auth/users/lesen` | Das Adminprofil steht **nicht** in der Liste |
| `POST /api/v1/auth/user/waehlen` mit der Id des Adminprofils | `404` mit `"code":"INHALT_NICHT_GEFUNDEN"` |
| `POST /api/v1/auth/admin/anmelden` mit richtigem Passwort | `204`, neues Cookie, Sitzung trägt `rolle = 'ADMIN'` und die `spieler_id` des Adminprofils |
| `SELECT wert FROM profil.spieler_skill` für das Adminprofil | fünf Zeilen, alle `0` |


### 6.11 Verifikation: erfolgreich am 22.08.2026

**`./mvnw clean verify` ist grün durchgelaufen: 148 Tests in 16 Klassen**, keine Fehler, keine
Abbrüche, keine übersprungenen Tests. Die Zahlen stammen aus `server/target/surefire-reports/*.txt`
und sind nicht geschätzt.

| Testklasse | Fälle | Änderung gegenüber dem Stand vom 16.08. |
|---|---:|---|
| `MigrationTests` | 7 | Fall `zweiterAdminWirdAbgelehnt` angepasst |
| `SessionServiceTests` | 18 | +4 (Aufräumjob/Gastplätze, lesender Pfad, Ablaufzeitpunkte) |
| `ConfigServiceTests` | 2 | – |
| `SessionAuthFilterTests` | 14 | +3 (Header-Auswertung) |
| `SessionCookieFactoryTests` | 9 | – |
| `SecurityConfigTests` | 24 | +5 (Sitzungsendpunkte, Adminzugang), Platzhalter `gast/anmelden` entfernt |
| `BruteForceServiceTests` | 10 | – |
| `AuthControllerTests` | 10 | +1 (`Retry-After`) |
| `NamenControllerTests` | 10 | +2 (Adminprofil weder in der Liste noch über seine Id wählbar) |
| `ApiVersionConfigTests` | 5 | – |
| `AuditServiceTests` | 5 | – |
| `GastControllerTests` | 8 | neu |
| `GastServiceTransaktionTests` | 2 | neu, ohne `@Transactional` |
| `SessionControllerTests` | 10 | neu |
| `AdminBootstrapTests` | 8 | neu |
| `AdminControllerTests` | 6 | neu |
| **Summe** | **148** | 99 vorher, also 49 zusätzliche Fälle |

Zusätzlich bestätigt: Die Anwendung startet auf einer frischen Datenbank durch – zentrale PIN,
Adminprofil und Admin-Konto entstehen im Bootstrap, ohne dass vorher Profildaten eingespielt werden
müssen.

**Drei Defekte, die der Lauf zutage gefördert hat** – alle drei nicht in der Anwendung, sondern im
Testcode beziehungsweise in der Werkzeugkette. Sie stehen hier, weil jeder von ihnen wie ein
Anwendungsfehler aussieht und keiner ist:

1. **`sitzungsIdZu(token)` nach einer Token-Rotation** (`GastControllerTests`,
   `SessionControllerTests`). Das Hilfsmittel sucht über den SHA-256 des Tokens; nach
   `gast/anmelden` beziehungsweise `session/erneuern` findet der alte Hash keine Zeile mehr, und die
   Abfrage endet in einer `EmptyResultDataAccessException`. Das *belegt* die Rotation, statt sie zu
   widerlegen. Behoben, indem die Id vor dem Aufruf einmal aufgelöst und behalten wird – die
   `session.id` überdauert die Rotation, nur der Token nicht. Beide Hilfsmittel tragen den Hinweis
   jetzt im JavaDoc.
2. **Eine Migrationsdatei mit einfachem Unterstrich** (`V009_admin_profil.sql`). Flyway verlangt
   `V<nnn>__<beschreibung>.sql` mit doppeltem Unterstrich und überspringt nicht passende Namen in
   der Voreinstellung **stillschweigend** – kein Fehler, kein INFO-Log. Konsequenz:
   `spring.flyway.validate-migration-naming: true` ist jetzt gesetzt.
3. **Eine veraltete Kopie in `target/classes`.** Das `maven-resources-plugin` *kopiert* nur; beim
   Umbenennen einer Ressource bleibt die alte Kopie im Klassenpfad liegen und wird beim Löschen der
   Quelle erst recht nicht entfernt. Die unter Punkt 2 neu gesetzte Prüfung hat sie sofort sichtbar
   gemacht – die Einstellung hat sich bei ihrem ersten Einsatz bezahlt gemacht. **Merkregel: Nach
   dem Umbenennen oder Löschen einer Ressource immer `./mvnw clean`.**

Die manuelle Prüfliste steht vollständig in `S2_UMSETZUNG.md`, Abschnitt 12.

### 6.12 S2b, Schritte 0 bis 7 umgesetzt (23.08.2026)

Passwort-Reset per E-Mail, Passwortaenderung im angemeldeten Zustand und Wechsel der
zentralen PIN. **Nicht enthalten:** die Spielerverwaltung aus Abschnitt 8, der Aufraeumjob aus
Abschnitt 9 und die Tests aus Abschnitt 11 – sie sind ausdrücklich einem zweiten Paket
vorbehalten. **Verifikation steht aus** (Abschnitt 6.13).

```
pom.xml                                  + spring-boot-starter-mail
fubo-api.json                            + 4 Endpunkte, 4 Fehlercodes, 3 Schemas, Tag "Zugangsdaten"
.env / .env.example                      + SMTP_HOST, SMTP_PORT, SMTP_ABSENDER
src/main/java/de/fubo/appserver/
  common/config/      FuboProperties     + Mail, Reset
                      MailConfig (neu)   JavaMailSender-Bean samt Startpruefung
                      SecurityConfig     + 2 Regeln fuer /auth/passwort/*
  common/error/       Fehlercode         + RESET_PIN_FALSCH, RESET_UNGUELTIG,
                                           RESET_GEDROSSELT, VERSAND_FEHLGESCHLAGEN
  domain/audit/       AuditAktion        + PASSWORT_RESET_ANGEFORDERT, PASSWORT_RESET_FEHLVERSUCH,
                                           PASSWORT_GEAENDERT, PIN_GEAENDERT
  domain/auth/        OffenerReset (neu), AnforderungsFenster (neu)
  repository/auth/    PasswortResetRepository (neu, JdbcClient)
  service/auth/       PasswortResetService (neu), ZugangsdatenService (neu)
                      AdminService       + passwortSetzen, email
                      SessionService     alleWiderrufen gibt jetzt Gastplaetze frei
  service/mail/       MailService (neu)  Versand der Bestaetigungs-PIN
  dto/auth/           PasswortResetBestaetigenRequest (neu)
  dto/admin/          PasswortAendernRequest (neu), PinAendernRequest (neu)
  controller/auth/    PasswortResetController (neu)
  controller/admin/   ZugangsdatenController (neu)
src/main/resources/   application.yml    + fubo.mail.*, fubo.reset.*
src/test/resources/   application.yml    + fubo.mail.*, fubo.reset.* (feste Testwerte)
src/test/java/…       SessionAuthFilterTests, SessionCookieFactoryTests, BruteForceServiceTests
                                         je zwei Argumente mehr beim Bau von FuboProperties
```

**Keine Schemaaenderung.** `V008` bleibt die letzte Migration; `profil.passwort_reset` stammt
unveraendert aus `V003`.

**Endpunkte (neu):**

| Methode | Pfad | Erlaubte Stufe/Rolle | Antwort |
|---|---|---|---|
| `POST` | `/api/v1/auth/passwort/zuruecksetzen` | nur `PIN_VERIFIED` | `204`, PIN per E-Mail versendet |
| `POST` | `/api/v1/auth/passwort/bestaetigen` | nur `PIN_VERIFIED` | `204`, Adminsitzungen widerrufen |
| `POST` | `/api/v1/admin/passwort/aendern` | Rolle `ADMIN` | `204`, Cookie geloescht |
| `POST` | `/api/v1/admin/pin/aendern` | Rolle `ADMIN` | `204`, **alle** Sitzungen widerrufen, Cookie geloescht |

**Sechs Entscheidungen, die von der Anleitung abweichen oder sie ergaenzen:**

1. **Die Reset-Endpunkte liegen unter `/auth/`, nicht unter `/admin/`** (Abschnitt 10 der
   Anleitung nannte `/admin/passwort/zuruecksetzen`). Beides gleichzeitig geht nicht:
   `/api/*/admin/**` verlangt in `SecurityConfig` die Rolle `ADMIN` – und wer sein Passwort
   vergessen hat, traegt sie gerade nicht. Abschnitt 2.2 der Anleitung verlangt zugleich die
   Stufe `PIN_VERIFIED`. Der Reset gehoert damit zur **Anmeldung** und steht neben
   Namensauswahl, Gast-Login und Admin-Login. Entscheidung des Haupt-Entwicklers vom
   23.08.2026; Abschnitt 10 der Anleitung ist nachgezogen.
2. **Der SMTP-Zugang haengt an `fubo.mail.*`, nicht an `spring.mail.*`** – und die
   `JavaMailSender`-Bean entsteht in `MailConfig` von Hand. Grund: Abschnitt 1.1 der Anleitung
   verlangt „fehlt die Variable, bricht der Start ab". Die Autokonfiguration kann das nicht
   einloesen, weil Spring Boots `Binder` einen unaufloesbaren Platzhalter **woertlich**
   durchreicht – die Anwendung liefe mit dem Rechnernamen `"${SMTP_HOST}"` durch und der Fehler
   zeigte sich erst beim ersten Reset. Es ist derselbe Fallstrick wie bei `${DB_USER}` in S1
   (Abschnitt 6.3). `MailConfig` prueft die vier Pflichtwerte und bricht mit einer benennenden
   Meldung ab.
3. **Der Reset widerruft nur die Sitzungen des Admins**, nicht alle (offener Punkt 5,
   entschieden – Abschnitt 4.2 der Anleitung schlug noch `alleWiderrufen()` vor). Das
   Adminpasswort betrifft ausschliesslich den Adminzugang; Spieler und Gaeste ohne Grund
   abzumelden waere ein Schaden ohne Nutzen. Alle Sitzungen widerruft ausschliesslich der
   Wechsel der **zentralen** PIN – dort aendert sich das gemeinsame Geheimnis.
4. **`SessionService#alleWiderrufen` gibt jetzt die Gastplaetze frei.** Ohne diesen Zusatz
   blieben nach einem PIN-Wechsel bis zu vier Plaetze bis zum naechtlichen Aufraeumlauf von
   Sitzungen belegt, die niemand mehr nutzen kann. Die Reihenfolge ist festgelegt und nicht
   umkehrbar: Die Freigabe erkennt ihre Kandidaten an gesetztem `widerrufen_am`.
5. **Der Versuchszaehler laeuft mit `REQUIRES_NEW`** (offener Punkt 3, bestaetigt) – als
   `@Transactional` an `PasswortResetRepository#versuchZaehlen`. Zusaetzlich sitzt die
   Reihenfolge „zaehlen, protokollieren, ablehnen" wie beim PIN- und beim Admin-Login **im
   Controller**: Nur dort laeuft der Audit-Eintrag ausserhalb jeder Transaktion und ueberlebt
   die Ablehnung. Beides zusammen, weil Zaehler und Protokolleintrag verschiedene Wege gehen –
   der Zaehler ueber die eigene Transaktion, der Eintrag ueber die fehlende. Die Regel aus
   `AGENT_SERVER.md` bleibt unberuehrt: Sie gilt dem Audit-Log, nicht Zaehlern.
6. **Die neue zentrale PIN besteht aus genau vier Ziffern** (Festlegung des Haupt-Entwicklers
   vom 23.08.2026; die Anleitung liess den Wertebereich offen). Rein numerisch und kurz, weil die
   PIN muendlich oder ueber einen Aushang weitergegeben und haeufig auf einer Zifferntastatur
   eingegeben wird; die **feste** Laenge statt einer Spanne erlaubt dem Frontend ein Eingabefeld mit
   vier Kaestchen. **Daran haengt eine Bedingung:** 10 000 Moeglichkeiten sind wenig – tragfaehig
   wird die PIN ausschliesslich durch den `BruteForceService` (fuenf Fehlversuche je Adresse, 30
   insgesamt, Sperrdauern 1/5/15 Minuten). Diese Grenzen duerfen nicht gelockert werden, solange die
   PIN vierstellig ist.

   **Folgeaenderung:** `PinBootstrap` erzeugt seine Ersatz-PIN jetzt vierstellig statt sechsstellig.
   Eine laengere Ersatz-PIN waere staerker, liesse sich ueber ein Frontend mit vier Kaestchen aber
   gar nicht eingeben – der Erststart endete in einer Sackgasse. `FUBO_INITIAL_PIN` bleibt
   unberuehrt (Betriebsangabe), und `/auth/pin/pruefen` schreibt der PIN weiterhin **kein** Format
   vor, damit ein abweichender Bestandswert eingebbar bleibt.

**Zum Zusammenspiel der Grenzen.** Fuenf Stellen sind 100 000 Moeglichkeiten – fuer sich zu
wenig. Tragfaehig wird die Bestaetigungs-PIN erst durch die Summe: fuenf Versuche je Vorgang,
15 Minuten Gueltigkeit, drei Anforderungen je Stunde und Adresse, BCrypt statt Klartext, der
Endpunkt hinter der zentralen PIN und der zusaetzliche Brute-Force-Zaehler. Wer eine Stunde lang
alle erlaubten Versuche ausschoepft, kommt auf 15 von 100 000 – etwa 0,015 Prozent. **Keine
dieser Grenzen darf entfallen.**

**Vertrag nachgezogen (`fubo-api.json`).** Vier Endpunkte, vier Fehlercodes
(`RESET_PIN_FALSCH`, `RESET_UNGUELTIG`, `RESET_GEDROSSELT`, `VERSAND_FEHLGESCHLAGEN`), drei
Schemas und der Tag „Zugangsdaten". Der `429` des Reset-Endpunkts traegt `Retry-After` und
`wartesekunden` wie der PIN-Endpunkt; die Restwartezeit ergibt sich aus der aeltesten
Anforderung im Stundenfenster.

### 6.13 Verifikation: steht aus

**Erster Lauf am 23.08.2026: uebersetzt, aber kein Kontext hochgekommen - Ursache lag ausserhalb
der Anwendung.** `./mvnw clean verify` hat die Uebersetzung sauber durchlaufen (alle 148 Tests
wurden gestartet, also sind saemtliche S2b-Klassen fehlerfrei kompiliert), doch jeder
`@SpringBootTest` scheiterte beim Laden des Kontexts. Am Ende der Ursachenkette stand eine
einzige Zeile:

```
java.lang.IllegalStateException: Could not find a valid Docker environment.
```

Testcontainers konnte `postgres:17` nicht starten, weil **Docker nicht lief**. Vor dem Lauf also
Docker Desktop (bzw. Colima oder OrbStack) starten und `./mvnw clean verify` wiederholen.

**Merkregel, weil die Ausgabe bedrohlicher aussieht als die Lage** - dieselbe Sorte Fallstrick wie
die drei in Abschnitt 6.11:

- **115 Fehler bedeuten nicht 115 Ursachen.** Nur der *erste* Bericht je Kontextkonfiguration nennt
  sie; alle uebrigen tragen `ApplicationContext failure threshold (1) exceeded: skipping repeated
  attempt`. Das ist Spring Test, das denselben Kontext nicht 115-mal neu baut - kein zusaetzlicher
  Defekt.
- **Die Ursache steht nicht in der Maven-Zusammenfassung**, sondern in
  `server/target/surefire-reports/*.txt`. Kuerzester Weg:
  `grep -h 'Caused by' server/target/surefire-reports/*.txt | tail -1`
- **Gegenprobe ueber die Klassen ohne Spring-Kontext:** `SessionAuthFilterTests` (14),
  `SessionCookieFactoryTests` (9) und `BruteForceServiceTests` (10) waren gruen - 33 Faelle. Waere
  der Fehler in der Anwendung, traefe er auch sie. Nebenbei ist damit bestaetigt, dass die
  S2b-Erweiterung von `FuboProperties` (zwei zusaetzliche Konstruktorargumente) in allen drei
  Klassen richtig nachgezogen ist.

**Die eigentliche Verifikation steht damit weiterhin aus.** Der Stand ist geprueft, aber nur
statisch: Der Cloud-Container hat Java 21 und keinen Zugriff auf Maven Central. Geprueft wurden
Klammer- und Literalstruktur aller 88 Java-Dateien, die Zuordnung Paket zu Pfad, die Gueltigkeit
von `fubo-api.json` und die Signaturen der aufgerufenen Methoden.

Vor dem Lauf sind **zwei Werte in der `.env` zu ergaenzen** – ohne sie bricht der Start
absichtlich ab:

| Variable | Stand |
|---|---|
| `SMTP_HOST` | vorbelegt mit `smtp.maileroo.com` |
| `SMTP_PORT` | vorbelegt mit `587` |
| `SMTP_ABSENDER` | **leer – auszufuellen**, muss zur bei Maileroo freigegebenen Domain gehoeren |

```bash
docker info > /dev/null      # muss durchlaufen - sonst startet Testcontainers nicht
docker compose -f compose.dev.yml --env-file .env up -d
./mvnw clean verify          # clean ist Pflicht: application.yml hat sich geaendert
./mvnw spring-boot:run
```

Erwartet: weiterhin **148 Tests in 16 Klassen** – S2b bringt noch keine eigenen Testfaelle mit,
und die drei angepassten Testklassen haben nur zusaetzliche Konstruktorargumente bekommen.
Faellt der Kontext beim Start aus, ist die erste Logzeile massgeblich; die wahrscheinlichsten
Ursachen sind eine fehlende SMTP-Variable (Meldung aus `MailConfig`, benennt die Variable) und
geaenderte Maven-Koordinaten von `spring-boot-starter-mail` unter Boot 4.

Zusaetzlich von Hand zu pruefen (vollstaendig in `S2b_UMSETZUNG.md`, Abschnitt 12):

| Pruefpunkt | Erwartung |
|---|---|
| Start ohne `SMTP_ABSENDER` | Abbruch mit einer Meldung, die `fubo.mail.absender` und `SMTP_ABSENDER` nennt |
| `POST /api/v1/auth/passwort/zuruecksetzen` in `PIN_VERIFIED` | `204`, Nachricht im Postfach, `pin_hash` als BCrypt in der Datenbank |
| derselbe Aufruf ohne Cookie | `401`, mit Spielersitzung `403` |
| `SELECT * FROM profil.passwort_reset` | genau ein offener Vorgang; die fuenfstellige PIN steht nirgends |
| viertes Anfordern binnen einer Stunde | `429 RESET_GEDROSSELT` mit `Retry-After` |
| SMTP-Dienst stoppen, dann anfordern | `503 VERSAND_FEHLGESCHLAGEN`, **keine** neue Zeile in `passwort_reset` |
| Bestaetigen mit zu kurzem Passwort | `400`, `versuche` bleibt unveraendert |
| fuenfmal falsche PIN, dann noch einmal | erst `401 RESET_PIN_FALSCH`, dann `409 RESET_UNGUELTIG` – **kein** `500` aus `ck_passwort_reset_versuche` |
| Bestaetigen mit richtiger PIN | `204`; die alte Adminsitzung liefert `401`, Spielersitzungen bleiben gueltig |
| `POST /api/v1/admin/passwort/aendern` mit falschem alten Passwort | `401 ADMIN_PASSWORT_FALSCH`, Zaehler steigt |
| `POST /api/v1/admin/pin/aendern` | `204`, danach liefert **jede** Sitzung `401`, `gast_slot` ist leer |
| `POST /api/v1/admin/pin/aendern` mit dreistelliger oder fuenfstelliger PIN | `400 EINGABE_UNGUELTIG`, Feld `neuePin` |
| Erststart ohne `FUBO_INITIAL_PIN` | Startmeldung nennt eine **vierstellige** Zufalls-PIN |
| `SELECT geaendert_von FROM profil.zugangsdaten` | `1` (vorher leer) |
| `SELECT aktion FROM profil.audit_log` | `PASSWORT_RESET_ANGEFORDERT`, `PASSWORT_GEAENDERT`, `PIN_GEAENDERT` |


## 7. Nächste Schritte

1. **`SMTP_ABSENDER` in der `.env` ergänzen und `./mvnw clean verify` laufen lassen.** Ohne den
   Wert bricht der Start absichtlich ab (`MailConfig`). `SMTP_HOST` und `SMTP_PORT` sind bereits
   vorbelegt. Prüfliste in Abschnitt 6.13, erwartete Testzahl unverändert 148. Danach die
   **tatsächlichen** Zahlen aus `server/target/surefire-reports/*.txt` dort eintragen.
2. **Zweites S2b-Paket:** Spielerverwaltung durch den Admin (Abschnitt 8 der Anleitung –
   `/admin/user/anlegen`, `/entfernen`, `/blockieren`), Aufräumjob für alte Reset-Vorgänge
   (Abschnitt 9, Löschfrist 30 Tage als Property) und die zwölf Testfälle aus Abschnitt 11. Erst
   danach ist S2b abgeschlossen und der Handoff auf die dann noch relevanten Punkte zu reduzieren.
3. Die Bruno-Collection unter
   `~/Documents/bruno/fubo_server/` um die neun neuen Endpunkte ergänzen (`auth/gast/anmelden`,
   `auth/admin/anmelden`, ein neuer Ordner `session/` mit `lesen`, `erneuern`, `beenden`, dazu
   `auth/passwort/zuruecksetzen`, `auth/passwort/bestaetigen`, `admin/passwort/aendern` und
   `admin/pin/aendern`), Vorlage steht in deren `README.md`. Bei Abweichungen ist `fubo-api.json`
   maßgeblich.
4. **Domainentscheidung – erledigt (09.08.2026).** Frontend und API liegen auf Subdomains **derselben
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
5. **Danach S3** (Profile & Skills API). Zwei Punkte aus S2 landen dort: das Anlegen weiterer
   Gastplätze, wenn `anz_guests` über vier hinaus erhöht werden soll (offener Punkt 18), und die
   Frage, wie der Teamgenerator Profile ohne gepflegte Skillwerte behandelt (offener Punkt 20).
6. **Endpunktkontrakt – erledigt (22.08.2026), S2b nachgezogen (23.08.2026).** Er liegt als `server/fubo-api.json` auf der
   Repo-Wurzel und beschreibt die acht Endpunkte aus S2 sowie die vier aus S2b vollständig (Abschnitt 4). Ab jetzt gilt:
   **Jede Vertragsänderung zuerst dort abbilden**, dann den Client-Track nachziehen. S3 bis S6
   tragen ihre Endpunkte jeweils bei Fertigstellung nach.
7. **Profildaten** (Vorgehen steht, nichts mehr zu entscheiden): Reale Daten liegen ausserhalb von
   `PRJ_FuBo/`, Pfad in `FUBO_LOCAL_SEED`, Einspielen über `scripts/seed-lokal.sh`. Der anonymisierte
   30er-Satz liegt in `scripts/data/`, der 12er-Demosatz läuft automatisch in Dev und Test.
8. **Deployment (S8):** Entwurf mit Dockerfile, Compose-Ergänzung, nginx-Block, Backup und Rollout liegt
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
