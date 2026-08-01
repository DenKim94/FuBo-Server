# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) um den
> serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`. Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.

> Projektordner: `<Projektordner>/PRJ_FuBo`, Backend unter `server/`
> Git-Repository: noch nicht angelegt (siehe Meilenstein S0)
> Stand: 01.08.2026, Aufteilung in Client-/Server-Verantwortung

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
| S0 | Backend-Setup: Spring Boot, Maven, Modulstruktur, `.gitignore`, Docker/Compose-Eintrag, CI-Backend | 8 |
| S1 | Datenmodell: 3 Schemas, alle Tabellen/Constraints, Flyway-Migrationen, Seed (Kategorien, `gast_vorlage`), Testcontainers-Grundgerüst | 14 |
| S2 | Auth & Session: Security-Filterchain, PIN-Login + Brute-Force-Schutz, `stage`-Erzwingung, opaker Token/HttpOnly, Zwei-Timer-Modell, Namensliste/-belegung, Online-Status | 18 |
| S2b | Admin-Passwort-Reset: `spring-boot-starter-mail`, 5-stellige PIN, Rate-Limit, Sitzungswiderruf | 6 |
| S3 | Profile & Skills API: Admin-CRUD, Rollen/Autorisierung, `configs`, Import der Referenzdaten (ohne reale Namen) | 12 |
| S4 | Termine & Teilnahme API: Einzel/Serie, Teilnahme, `teilnehmer_version`, Min/Max + Warteschlange, Gast-Flow/`gast_slot` | 16 |
| S5 | Teamgenerator: `EXHAUSTIV` + `HEURISTIK`, Zielfunktion inkl. Torwart-Gewicht, Kontingent/Seed/Snapshot, Auswechselspieler, Tests | 18 |
| S6 | Ergebnis & Audit API: „erster Eintrag gilt", Admin-Korrektur, `audit_log` | 8 |
| S7 | Hallenmodus: E-Mail-Absage, 48-Stunden-Regel, serverseitige Deaktivierung | 6 |
| S8 | Härtung, Integrationstests, Deployment (Docker/nginx/Cloudflared), API-Doku/OpenAPI | 14 |

**Summe Server ≈ 120 h → ca. 18–19 Kalenderwochen** bei 6,5 h/Woche (Spanne ±15 %). Kritischer Pfad: S2
und S5. Abhängigkeit: S2b setzt einen SMTP-Zugang voraus (Anbieter/Absenderadresse festlegen).

## 6. Aktueller Code-Zustand
Kein Code vorhanden (reine Konzeptionsphase). Nächster Schritt ist S0.

## 7. Nächste Schritte
1. **S0** starten: Backend-Struktur `server/` im Monorepo anlegen, Branch-Strategie und `.gitignore`
   (inklusive `.env`) festlegen, Compose-Eintrag ergänzen.
2. Endpunktkontrakt mit dem Client-Agenten abstimmen (OpenAPI) und hier dokumentieren.
3. Danach S1 (Datenmodell/Flyway) und S2/S2b (Auth/Session, Admin-Reset).

## 8. Weitere Anweisungen
- Ohne ausdrückliche Anweisung des Entwicklers nichts in `main` mergen/pushen; Feature-Branch erlaubt.
- `.env`-Dateien nie einchecken. Dokumentation in deutscher Sprache. **Keine realen Personennamen** in
  Code, Testdaten oder Dokumentation.
- Nach Abschluss eines Arbeitspakets kurze Verifikation durchführen und diesen Handoff aktualisieren
  (veraltete Fassung zuvor unter `server/harness/archive/` ablegen). 
  Zudem soll auch das zentrale Handoff in `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` (Gesamtstand) entsprechend aktualisiert werden.
