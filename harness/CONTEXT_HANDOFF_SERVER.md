# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) um den
> serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`. Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.

> Projektordner: `<Projektordner>/PRJ_FuBo`, Backend unter `server/`
> Git-Repository: **eigenständiges Repository mit Wurzel in `server/`** (GitHub, privat, `FuBo-Server`).
> **Kein Monorepo.** Das Frontend liegt in einem getrennten Repository (`FuBo-Client`, Ordner `client/`).
> Der übergeordnete Ordner `PRJ_FuBo/` sowie `PRJ_FuBo/harness/` sind bewusst **nicht** versioniert.
> Repository ist angelegt: `https://github.com/DenKim94/FuBo-Server.git` (privat).
> Stand: 08.08.2026, **S0 und S1 abgeschlossen**, Beginn von S2
> (Vorfassung archiviert unter `harness/archive/CONTEXT_HANDOFF_SERVER_2026-08-02_v2_S0-abgeschlossen.md`)

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
| S2 | Auth & Session: Security-Filterchain, PIN-Login + Brute-Force-Schutz, `stage`-Erzwingung, opaker Token/HttpOnly, Zwei-Timer-Modell, Namensliste/-belegung, Online-Status – **in Arbeit**, Anleitung `harness/tmp/S2_UMSETZUNG.md` | 22 |
| S2b | Admin-Passwort-Reset: `spring-boot-starter-mail`, 5-stellige PIN, Rate-Limit, Sitzungswiderruf | 6 |
| S3 | Profile & Skills API: Admin-CRUD, Rollen/Autorisierung, `configs` (Import der Referenzdaten entfällt – siehe Abschnitt 7.3) | 11 |
| S4 | Termine & Teilnahme API: Einzel/Serie, Teilnahme, `teilnehmer_version`, Min/Max + Warteschlange, Gast-Flow/`gast_slot` | 16 |
| S5 | Teamgenerator: `EXHAUSTIV` + `HEURISTIK`, Zielfunktion inkl. Torwart-Gewicht, Kontingent/Seed/Snapshot, Auswechselspieler, Tests | 18 |
| S6 | Ergebnis & Audit API: „erster Eintrag gilt", Admin-Korrektur, `audit_log` | 8 |
| S7 | Hallenmodus: E-Mail-Absage, 48-Stunden-Regel, serverseitige Deaktivierung | 6 |
| S8 | Härtung, Integrationstests, Deployment (Docker/nginx/Cloudflared), API-Doku/OpenAPI – Entwurf liegt vor: `harness/tmp/S8_DEPLOYMENT.md` | 14 |

**Korrektur der S2-Schätzung (08.08.2026):** Die Aufschlüsselung in `S2_UMSETZUNG.md` summiert sich auf
**22 h** statt der ursprünglich veranschlagten 18 h. Die Abweichung entsteht vor allem in der
Filterchain (4 h) und beim Brute-Force-Schutz (3 h) – beides war in der Top-down-Schätzung zu grob
angesetzt. Die Summe der Einzelschritte ist verlässlicher als die Gesamtschätzung, deshalb steht hier
der höhere Wert. S1 wurde entsprechend von 14 auf 15 h angehoben, S3 von 12 auf 11 h gesenkt, da der
Import der Referenzdaten dort entfällt.

**Summe Server ≈ 124 h → ca. 19–20 Kalenderwochen** bei 6,5 h/Woche (Spanne ±15 %). Kritischer Pfad: S2
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

### 6.2 Offene Punkte vor S2

1. **Paketstruktur ist unvollständig.** Angelegt (und leer) sind `common/{config,error}`,
   `controller/{auth,config,ergebnis,profil,team,termin}`, `service/{…}`, `repository`, `utils`.
   Es fehlen Pakete für **JPA-Entities** und **DTOs**. Vorschlag in `S2_UMSETZUNG.md`, Abschnitt 1.1.
2. **Es gibt noch keine `SecurityFilterChain`.** Solange keine existiert, konfiguriert Spring Security
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

## 7. Nächste Schritte

1. **S2 umsetzen** – Auth und Session. Schrittweise Anleitung: `harness/tmp/S2_UMSETZUNG.md`.
   Reihenfolge dort: Paketstruktur → Entities/Repositories → Token und Hashing → Session-Service mit
   Zwei-Timer-Modell → Security-Filterchain (inkl. `permitAll` für `/actuator/health`) →
   Stage-Erzwingung → PIN-Login mit Brute-Force-Schutz → Namensliste/-belegung → Gast-Login →
   Bootstrap → Fehlerformat → Tests.
2. **Vor dem Cookie-Code klären:** Die Domainentscheidung `app.<domain>` / `api.<domain>`. Nur bei
   derselben registrierbaren Domain trägt `SameSite=Lax`; sonst ist `SameSite=None; Secure` nötig – und
   dann wird CSRF-Schutz zwingend, weil `Lax` dann keine fremden POST-Anfragen mehr blockt. Diese
   Entscheidung verändert Abschnitt 5 und 6 der S2-Anleitung.
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
