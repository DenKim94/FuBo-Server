# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) um den
> serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`. Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.

> Projektordner: `<Projektordner>/PRJ_FuBo`, Backend unter `server/`
> Git-Repository: **eigenständiges Repository mit Wurzel in `server/`** (GitHub, privat, `FuBo-Server`).
> **Kein Monorepo.** Das Frontend liegt in einem getrennten Repository (`FuBo-Client`, Ordner `client/`).
> Der übergeordnete Ordner `PRJ_FuBo/` sowie `PRJ_FuBo/harness/` sind bewusst **nicht** versioniert.
> Repository ist angelegt: `https://github.com/DenKim94/FuBo-Server.git` (privat).
> Stand: 02.08.2026, **S0 abgeschlossen**, Datenmodell-Review vor S1 durchgeführt
> (Vorfassung archiviert unter `harness/archive/CONTEXT_HANDOFF_SERVER_2026-08-01_v1_Aufteilung.md`)

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
| S1 | Datenmodell: 3 Schemas, alle Tabellen/Constraints, Flyway-Migrationen, Seed (Kategorien, `gast_vorlage`, anonymisierte Beispielprofile), lokale Datenversorgung, Testcontainers-Grundgerüst | 15 |
| S2 | Auth & Session: Security-Filterchain, PIN-Login + Brute-Force-Schutz, `stage`-Erzwingung, opaker Token/HttpOnly, Zwei-Timer-Modell, Namensliste/-belegung, Online-Status | 18 |
| S2b | Admin-Passwort-Reset: `spring-boot-starter-mail`, 5-stellige PIN, Rate-Limit, Sitzungswiderruf | 6 |
| S3 | Profile & Skills API: Admin-CRUD, Rollen/Autorisierung, `configs` (Import der Referenzdaten entfällt – siehe Abschnitt 7.3) | 11 |
| S4 | Termine & Teilnahme API: Einzel/Serie, Teilnahme, `teilnehmer_version`, Min/Max + Warteschlange, Gast-Flow/`gast_slot` | 16 |
| S5 | Teamgenerator: `EXHAUSTIV` + `HEURISTIK`, Zielfunktion inkl. Torwart-Gewicht, Kontingent/Seed/Snapshot, Auswechselspieler, Tests | 18 |
| S6 | Ergebnis & Audit API: „erster Eintrag gilt", Admin-Korrektur, `audit_log` | 8 |
| S7 | Hallenmodus: E-Mail-Absage, 48-Stunden-Regel, serverseitige Deaktivierung | 6 |
| S8 | Härtung, Integrationstests, Deployment (Docker/nginx/Cloudflared), API-Doku/OpenAPI | 14 |

**Summe Server ≈ 120 h → ca. 18–19 Kalenderwochen** bei 6,5 h/Woche (Spanne ±15 %). Kritischer Pfad: S2
und S5. Abhängigkeit: S2b setzt einen SMTP-Zugang voraus (Anbieter/Absenderadresse festlegen).

## 6. Aktueller Code-Zustand (Stand 02.08.2026)

### 6.1 Ergebnis aus S0
Das Repository ist angelegt und ein lauffähiges Spring-Boot-Grundgerüst vorhanden:

```
server/                       Repository-Wurzel (remote: FuBo-Server, privat)
  pom.xml                     Spring Boot 4.1.0, Java 25, Artefakt de.fubo:app-server:0.0.1-SNAPSHOT
  mvnw, mvnw.cmd, .mvn/       Maven Wrapper
  compose.dev.yml             postgres:17 für die lokale Entwicklung (Service fubo-db-dev)
  .env / .env.example         DB-Zugangsdaten; .env ist per .gitignore ausgeschlossen
  .gitignore, .gitattributes  inkl. Ausschluss von .env, target/, IDE-Ordnern
  src/main/java/de/fubo/app_server/AppServerApplication.java
  src/main/resources/application.yml
  src/test/java/de/fubo/app_server/  AppServerApplicationTests, TestcontainersConfiguration,
                                     TestAppServerApplication
  harness/                    AGENT_SERVER.md, CONTEXT_HANDOFF_SERVER.md, archive/
```

**Eingebundene Abhängigkeiten:** `actuator`, `data-jpa`, `flyway` (+ `flyway-database-postgresql`),
`security`, `validation`, `webmvc`, `postgresql` (runtime); im Testumfang die zugehörigen
`*-test`-Starter sowie `spring-boot-testcontainers` und `testcontainers-postgresql`.

**Bereits gesetzte Konfiguration (`application.yml`):** Datasource über Umgebungsvariablen,
`jpa.hibernate.ddl-auto=validate` (Schema kommt ausschliesslich von Flyway), `open-in-view=false`,
`flyway.schemas=profil, spieltag, configs`, `server.forward-headers-strategy=NATIVE`,
Actuator-Exposure auf `health` beschränkt.

### 6.2 Offene Restpunkte aus S0
1. `TestcontainersConfiguration` startet `postgres:latest`. Das muss auf **`postgres:17`** festgelegt
   werden, sonst testet man gegen eine andere Hauptversion als in Produktion (`NULLS NOT DISTINCT`,
   Verhalten von `MERGE` und Planänderungen sind versionsabhängig).
2. Das Basispaket heisst `de.fubo.app_server` (Unterstrich, aus dem Artefaktnamen abgeleitet). Java-
   Paketnamen sind konventionell durchgehend klein ohne Trennzeichen. Empfehlung: Umbenennung nach
   `de.fubo.appserver`, solange nur drei Klassen betroffen sind.
3. `pom.xml` enthält leere Metadaten-Elemente (`<name/>`, `<description/>`, `<licenses><license/></licenses>`,
   `<scm>`), die Maven-Warnungen erzeugen. Ausfüllen oder entfernen.
4. `spring-boot-starter-mail` fehlt noch – wird erst in S2b gebraucht, dann nachziehen.
5. `compose.dev.yml` enthält bisher nur die Datenbank. Dockerfile und Anwendungs-Service gehören zu S8.
6. `.gitignore` um zwei Regeln ergänzen, die unabhängig vom Ablageort greifen und lokale Datenimporte
   mit realen Daten ausschliessen:

   ```gitignore
   # Lokale Datenimporte mit realen Daten – niemals einchecken
   *.local.sql
   ```

   Bisher schützt nur `/harness/tmp/*`, also ein einzelnes Verzeichnis.

### 6.3 Datenmodell-Review vor S1 (02.08.2026)
Vor Beginn von S1 wurde das Datenmodell in `/PRJ_FuBo/harness/AGENT.md` gegen die Anforderungen geprüft.
Elf Widersprüche wurden korrigiert und dort im Abschnitt „Änderungsprotokoll Datenmodell" mit Begründung
dokumentiert. Die wichtigsten für S1:
- `configs` liegt jetzt als `configs.app_config` in einem eigenen Schema und enthält `min_teilnehmer`,
  `max_teilnehmer`, die Session-Timer sowie die Hallenmodus-Parameter.
- `configs.central_pin` und `admin_konto.pin_2fa` sind entfallen (Klartext-PIN bzw. zu kleiner Datentyp).
- Der zirkuläre Fremdschlüssel zwischen `session` und `gast_slot` ist aufgelöst
  (`session.gast_slot_id` entfernt).
- `skill_kategorie` erhält `reihenfolge` und `aktiv`; der Trigger für den kategorie-spezifischen
  Wertebereich ist verbindlich.

**Massgeblich für die Migrationen ist ab sofort die korrigierte Fassung in `AGENT.md`.**

## 7. Nächste Schritte
1. Restpunkte aus Abschnitt 6.2 abarbeiten (mindestens Punkt 1 und 2 vor S1, da beide später teurer
   werden).
2. **S1** umsetzen: Flyway-Migrationen `V001`–`V007` für die drei Schemas, alle Tabellen und Constraints
   sowie den Seed für `skill_kategorie`, `gast_vorlage`, `gast_slot` und `configs.app_config`, dazu das
   Testcontainers-Grundgerüst. Schrittweise Anleitung: `harness/tmp/S1_UMSETZUNG.md`.
3. **Profildaten – zwei getrennte Wege** (Entscheidung vom 02.08.2026, Details in `S1_UMSETZUNG.md`,
   Abschnitte 8.4 bis 8.6):
   - **Real:** `import_spielerprofile_real.sql` (30 Profile) wird **nicht** eingecheckt und **nicht** von
     Flyway ausgeführt. Ablage ausserhalb von `PRJ_FuBo/` (Konvention: `~/fubo-lokal/`), Pfad über
     `FUBO_LOCAL_SEED` in der `.env`. Der Maintainer überträgt die Datei manuell auf das Zielsystem und
     spielt sie einmalig per `psql` ein; sie ist idempotent. **Erledigt:** Datei aus `harness/tmp/`
     herausbewegt, `FUBO_LOCAL_SEED` in `.env` und `.env.example` gesetzt.
   - **Anonymisiert, auf Abruf:** `harness/tmp/spielerprofile_anonym.sql` (30 Profile mit den realen
     Skillwerten, Namen `Spieler 01`–`30`, gemischte Reihenfolge) kommt nach `scripts/data/`. Enthält
     keine personenbezogenen Daten. Regelfall für die Arbeit am Teamgenerator; keine Flyway-Migration,
     damit er nicht in jedem Testlauf geladen wird.
   - **Einspielhilfe (erledigt):** `scripts/seed-lokal.sh` liegt bereits im Repository und ist ausführbar.
     Nimmt einen Pfad als Argument oder greift auf `FUBO_LOCAL_SEED` aus der `.env` zurück. Enthält keine
     Daten.
4. Danach S2/S2b (Auth/Session, Admin-Reset). Voraussetzung für S2b: SMTP-Zugang festlegen.
5. Parallel den Endpunktkontrakt als OpenAPI unter
   `src/main/resources/openapi/fubo-api.yaml` beginnen (Quelle der Wahrheit für den Client-Track).

## 8. Weitere Anweisungen
- **Repository-Konventionen:** Repo-Wurzel ist `server/`. Branch-Namen mit Meilenstein-Präfix
  (`feature/s0-backend-setup`, `fix/...`, `chore/...`, `docs/...`). Commit-Nachrichten nach Conventional
  Commits **ohne** Scope `(server)` – die Zuordnung ergibt sich aus dem Repository.
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
