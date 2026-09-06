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
> - **Historie** → Git und `harness/archive/`; die letzte Langfassung ist
>   `CONTEXT_HANDOFF_SERVER_2026-08-31_v14_S4-abgeschlossen.md`
>
> Was hier steht, steht **nur** hier. Wird eine Festlegung zur Architekturregel, wandert sie
> nach `AGENT_SERVER.md` und **verschwindet hier** – sonst laufen beide auseinander.

## Stand: 06.09.2026

**S0 bis S4 sind abgeschlossen, S5 ist bis Paket 5 gebaut – alles verifiziert.**
`./mvnw clean verify` grün am 06.09.2026 mit **340 Tests in 27 Klassen**, keine Fehler, keine
Abbrüche, keine übersprungenen Tests; die Anwendung startet auf einer frischen Datenbank durch.
Der Lauf braucht Docker (Testcontainers, `postgres:17`) und läuft ausschliesslich lokal.

**Der Vertrag steht bei 32 Endpunkten**, das Datenmodell bei 18 Tabellen in drei Schemas
(`V001`–`V011`). S5 hat daran bis hierher nichts geändert und wird es erst mit Paket 9 tun –
dann sind es 34; **eine Migration braucht S5 nicht.**

**Drei Handprüfungen sind aufgeschoben, nicht vergessen** – zwei aus S4 (automatischer
Terminabschluss im Betrieb, Gast-Stufe über den Sitzungsablauf hinweg) und die Liste zu S5
(13.1 der Anleitung). Alle brauchen eine laufende Anwendung und werden **nach Abschluss von S5**
in einem Zug abgearbeitet – so entschieden am 06.09.2026, weil eine halbe Generierung nichts
zeigt, was sich prüfen liesse.

### Die Anforderung A24 (05.09.2026)

**(A24) Der Admin kann die Teamgenerierung auch manuell und unabhängig vom Termin ausführen und
die Teilnehmer frei wählen** – vorhandene Spielerprofile und Gäste mit Stufe. Sie ist **kein
zweiter Generator, sondern eine zweite Eingangstür zu demselben** und deshalb Teil von S5, nicht
S5b: Zielfunktion, beide Verfahren und der Auswechselspieler bleiben unberührt, verzweigt wird
allein die Herkunft der Aufstellung. Sie hebt die Schrittsumme von 20,5 auf **24,0 h** und den
Vertrag am Ende auf **34 Endpunkte**.

**Der manuelle Lauf wird nicht gespeichert** – `team_generierung.termin_id` ist `NOT NULL`, und
`team_zuteilung.teilnahme_id` hängt am Fremdschlüssel auf `spieltag.teilnahme`. Genau das hält
S5 migrationsfrei; Herleitung in `S5_UMSETZUNG.md`, 0.6, Festlegung in 6.2.

**A24 steht weiterhin nur in `AGENT_SERVER.md`.** Weder `/PRJ_FuBo/harness/AGENT.md` noch der
Client-Track kennen sie; dort ist es ein neuer Adminbildschirm. **Nachziehen ist jetzt
überfällig – der Code steht** (Abschnitt 7).

### S5, Pakete 1 bis 5 (06.09.2026)

Gebaut sind die Abschnitte 1 bis 5 der Anleitung – Bestandsaufnahme, **Aufstellung mit beiden
Quellen**, **Zielfunktion**, **`EXHAUSTIV`**, **`HEURISTIK`**; rund 11,5 der 24,0 h. Damit steht
die gesamte Rechnung. Es fehlt alles, was sie an Termin, Sitzung und Datenbank anbindet:
Kontingent (6), Generierungslauf und Snapshot (7), Auswechselspieler (8), Endpunkte (9), die
beiden S4-Nachträge (10) und der Vertrag (11). Dateien und Paketschnitt stehen in 6.1.

**Drei neue Fehlercodes**, bislang nur vom `AufstellungService` geworfen: `ZU_WENIG_TEILNEHMER`,
`ZU_VIELE_TEILNEHMER` (neu wegen A24 – dort wird nicht abgeschnitten) und
`SKILLWERTE_UNVOLLSTAENDIG`. Sie erreichen mit Paket 9 einen Endpunkt und gehören dann im selben
Zug in den Vertrag.

**Eine Abweichung von der Anleitung wiegt mehr als die anderen:** `HEURISTIK` ist mit acht
Neustarts gebaut und nicht als ein langer Lauf mit festem Abkühlfaktor – so wie vorgeschlagen
verfehlte sie das Optimum des Vergleichstests bei 13 von 100 Seeds. Messung in 6.2 und in
`S5_ALGORITHMUS.md`, 5.2; die übrigen sieben stehen in `S5_UMSETZUNG.md`, „Stand 06.09.2026".
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

**Umfang: 32 Endpunkte** (Tabelle in 6.1). Aufgenommen wird nur, was umgesetzt ist; S5 bis S7
tragen ihre bei Fertigstellung nach – **S5 bringt zwei** (`teams/generieren` und
`admin/teams/generieren`, siehe 4.3). Kernpunkte: REST/JSON, getrennte Origins mit CORS-Allowlist
(`allowCredentials`), HttpOnly-Session-Cookie, `401`/`403`-Semantik, DTOs ohne Skillwerte für
USER und GAST, Belegtstatus zum Pollen, einheitliches Fehler-JSON nach RFC 9457.

### 4.1 Was der Client-Track wissen muss

Jede Zeile ist eine Stelle, an der eine naheliegende Annahme falsch ist.

| Punkt | Bedeutung für den Client |
|---|---|
| `X-FuBo-Kein-Refresh: true` | Anfragheader für Hintergrundaufrufe, die die Sitzung nicht verlängern sollen |
| `Retry-After` und `wartesekunden` | Restwartezeit beim `429` des PIN-Endpunkts; doppelt geführt, weil der Header cross-origin nicht lesbar wäre |
| `absolutGueltigBis` | zweiter Zeitpunkt in der Sitzungsauskunft. Nähert sich der Countdown ihm, hilft „Verlängern" nicht mehr |
| `DATEN_VERALTET` (`409`) | heisst „neu laden und erneut speichern", nicht „Eingabe falsch". Gilt für Konfiguration, Termine und ab S6 für Ergebnisse |
| **Drei brechende Änderungen** | `anmeldename` in `AdminLoginRequest`, vollständige `skills` in `SpielerAnlegenRequest`, `auswechselModus` im Konfigurations-Voll-Update. Alle drei betreffen Formulare, alle drei liefern sonst `400` |
| `/admin/config/aendern` | **Voll-Update**: vorher `lesen`, dann alle elf Felder samt `version` zurückschicken |
| `/admin/termin/aendern` | **feldweise**, anders als die Konfiguration. Weglassen heisst „nicht ändern"; `ort: ""` leert den Ort. Ein Körper ohne jedes zu ändernde Feld liefert `400` |
| `sitzungGueltig` in `GastPlatzInfo` | **dreiwertig**: `true` lebende Sitzung, `false` verwaister Platz, `null` freier Platz. Ein `if (sitzungGueltig)` behandelt den freien wie den verwaisten – der Unterschied ist gerade der Punkt |
| `eigeneRueckmeldung` | **dreiwertig**: `true` zugesagt, `false` abgesagt, `null` noch nicht gemeldet |
| `/admin/gast/freigeben` | genau eines von `slotIds` und `alle`. Leerer Körper `400`, nicht „alle". Der Aufruf **meldet aktive Gäste ab**; vorher nachfragen |
| `TerminStatus` | `GEPLANT`, `ABGESAGT`, `ABGESCHLOSSEN`. **Eine Absage ist endgültig** – kein Weg zurück nach `GEPLANT`. Das gehört in die Bestätigungsabfrage |
| `/admin/termin/entfernen` | löscht endgültig, aber nur ohne Verweise (`409 TERMIN_IN_VERWENDUNG`). **Der einzige Weg zurück aus einer versehentlichen Absage** – ein abgesagter Termin belegt seinen Zeitpunkt weiter |
| `SerieAngelegt` | nennt die erzeugten **und** die übersprungenen Zeitpunkte. Kollisionen lassen die Serie nicht scheitern; die zweite Liste muss angezeigt werden |
| `TerminDetails.version` | vor jedem `/admin/termin/aendern` einmal lesen – sonst `409 DATEN_VERALTET` |
| Termine für Gäste | `/termine/lesen`, `/termine/{terminId}/lesen` und `/termine/rueckmeldung` sind ab `PROFILE_AUTHENTICATED` erreichbar, **auch für `GAST`**. Bewertungen tragen sie nicht |
| `/termine/rueckmeldung` | **ein Endpunkt für beide Richtungen und beide Rollen.** Ein Gast schickt keinen Namen mit – der Körper kennt kein Namensfeld. Antwort ist `204` |
| Warteschlange | **Eine erneute Zusage stellt hinten an.** Verhindert, dass sich jemand über eine Absage-Zusage-Schleife einen vorderen Platz freihält. Eine Absage lässt die Meldezeit unberührt |
| `teilnehmerliste` | Feld von `TerminDetails`, **kein eigener Endpunkt**. Bereits sortiert – **im Frontend nicht umsortieren**, sonst passt `position` nicht zur Anzeige |
| `ABGESCHLOSSEN` | **setzt der Server 30 Minuten nach Terminbeginn selbst** (A18). Torwächter für Rückmeldungen ist aber die Uhrzeit, nicht der Status |

### 4.2 Offene Übergabe: das Admin-Anmeldeformular

Es braucht ein zweites Eingabefeld. Drei Punkte gehören dabei ins Frontend:

1. **Feld „Anmeldename", Pflicht, höchstens 60 Zeichen.** Der Server trimmt Randleerzeichen
   selbst, prüft die Schreibweise aber **zeichengenau** – also kein `toLowerCase()` beim
   Absenden und kein Hinweis, die Schreibweise sei egal.
2. **Keine getrennte Meldung für „Name unbekannt".** Falscher Name und falsches Passwort
   liefern denselben Code; eine Unterscheidung im Frontend unterliefe die Absicht.
3. **Keine Vorbelegung, kein Autovervollständigen.** Der Anmeldename ist über keinen Endpunkt
   abrufbar; ein Auswahlfeld gäbe es nur, wenn ihn jemand ins Frontend schriebe.

### 4.3 Was mit S5 dazukommt – und wo die naheliegende Annahme falsch ist

Noch nicht im Vertrag, aber beim Planen der Adminoberfläche schon zu wissen:

| Punkt | Bedeutung für den Client |
|---|---|
| `POST /api/v1/teams/generieren` | **nicht** unter `/admin/` – jeder Angemeldete darf generieren, auch `GAST` (A15). Kontingent je Nutzer und Teilnehmerstand |
| `POST /api/v1/admin/teams/generieren` | **nur `ADMIN`** (A24). Unterscheidet sich vom offenen Endpunkt **nur im `/admin/`-Präfix**. Auswahl aus `/admin/user/lesen` plus frei angelegte Gäste mit Stufe. **Kein neuer Listenendpunkt** – die Auswahlliste gibt es bereits |
| Antwort des manuellen Laufs | **`200`, nicht `201`**, und **ohne `seed`** – es entsteht nichts. Das Ergebnis wird **nirgends gespeichert**, erscheint bei keinem anderen Nutzer und ist nach dem Verlassen der Seite weg. **Das gehört sichtbar auf den Bildschirm**, sonst hält der Admin es für die Einteilung des Termins |
| `algorithmType` / `auswechselModus` in der Antwort | können von der Konfiguration **abweichen** (Rückfall bei zu vielen Teilnehmern bzw. fehlender Meldezeit). Anzeigen, nicht ignorieren |
| `differenzTeamstaerke` | die **Kosten der Zielfunktion** (gewichtete Summe der Kategoriedifferenzen), nicht die Differenz der Gesamtstärken. `0` = perfekt ausgeglichen, grösser = schlechter. Nur im Adminbildschirm |
| Genannte, aber ungültige Auswahl | wird **abgelehnt**, nicht still gefiltert: unbekannte oder gesperrte Id `400`, Adminprofil `409 PROFIL_GESCHUETZT` |
| `maxTeilnehmer` im manuellen Lauf | **begrenzt, schneidet nicht ab** (`409 ZU_VIELE_TEILNEHMER`). Am Termin ist es die Grenze zur Warteschlange – hier gibt es keine |
| `teams` in `TerminDetails` | nullbares Feld, `null` heisst „noch nicht generiert" und ist kein Fehler. `veraltet: true` heisst „noch anzeigen, aber nicht mehr aktuell" |

## 5. Meilensteine (Server)

Mid-Level-Entwickler, KI-gestützt, ca. 6,5 h/Woche. **Die Summe der Einzelschritte war jedes Mal
verlässlicher als die Top-down-Schätzung** (S2: 18 → 23, S2b: 6 → 10, S4: 16 → 17 plus 3,
S5: 18 → 20,5 vorgeschlagen).

| MS | Inhalt | Stand | h |
|---|---|---|---|
| S0 | Setup: Spring Boot, Maven, Modulstruktur, Compose | **abgeschlossen** | 8 |
| S1 | Datenmodell: 3 Schemas, Flyway, Seed, Testcontainers | **abgeschlossen** | 15 |
| S2 | Auth & Session: Filterchain, PIN-Login, Brute-Force, Zwei-Timer, Gast-/Admin-Login, Vertrag | **verifiziert (148 Tests)** | 23 |
| S2b | Zugangsdatenpflege und Spielerverwaltung, Aufräumjob | **verifiziert (29.08.2026)** | 10 |
| S3 | Profile & Skills API, Rollen, `configs` | **verifiziert (29.08.2026, 244 Tests)** | 11 |
| S4 | Termine & Teilnahme: Einzel/Serie, Teilnahme, `teilnehmer_version`, Min/Max + Warteschlange, Gast-Flow; dazu A7, A18, A19 | **verifiziert (31.08.2026, 331 Tests in 26 Klassen)**; zwei Handprüfungen offen, siehe 7 | 16 (17 + 3) |
| S5 | Teamgenerator: `EXHAUSTIV` + `HEURISTIK`, Zielfunktion inkl. Torwart-Gewicht, Kontingent/Seed/Snapshot, Auswechselspieler; **dazu A24 (manueller Lauf des Admins)** | **Pakete 1–5 verifiziert (06.09.2026, 340 Tests in 27 Klassen)**; Schrittsumme **24,0 h** (20,5 + 3,5 für A24) | 18 |
| S6 | Ergebnis & Audit API: „erster Eintrag gilt", Admin-Korrektur, Bilanz-Zähler | offen | 8 |
| S7 | Hallenmodus: E-Mail-Absage, 48-Stunden-Regel | offen | 6 |
| S8 | Härtung, Deployment (Docker/nginx/Cloudflared), API-Doku – Entwurf: `harness/tmp/S8_DEPLOYMENT.md` | offen | 14 |

Anleitungen: `harness/tmp/S<n>_UMSETZUNG.md`. **Ausnahme S5:** Der Algorithmusteil (Zielfunktion,
`EXHAUSTIV`, `HEURISTIK` – Abschnitte 3 bis 5, 6,0 h) steht seit dem 05.09.2026 in
`harness/tmp/S5_ALGORITHMUS.md`. Er berührt als Einziger weder Datenbank noch HTTP noch Sitzung;
**die Abschnittsnummern sind beibehalten**, ein Verweis „3.1" meint dieselbe Stelle wie zuvor.
Beide Dateien zusammen sind die Anleitung für S5.

## 6. Code-Zustand (06.09.2026, Branch `dev`)

### 6.1 Was steht

```
server/                        Repo-Wurzel (remote: FuBo-Server, oeffentlich)
  fubo-api.json                Endpunktkontrakt, 32 Endpunkte
  compose.dev.yml              postgres:17
  .env / .env.example          DB-Zugang, FUBO_INITIAL_PIN, ADMIN_*, SMTP_*
  scripts/                     seed-lokal.sh + anonymisierter 30er-Datensatz
  src/main/resources/db/       migration/ V001-V011, demodata/ (nur dev und test)
  src/main/java/de/fubo/appserver/
    common/  config error security
    controller/ auth admin spieltag   service/ auth profil audit mail config spieltag team
    repository/ auth profil audit spieltag
    domain/ auth profil audit config spieltag team
    dto/ auth profil admin spieltag   utils
```

**Fachbereich `spieltag` (S4):**

```
domain/spieltag/      Termin, Terminserie (Entities); TerminStatus;
                      TerminEintrag, Teilnehmereintrag, Teilnehmeruebersicht,
                      TerminMitTeilnehmern (Wertobjekte)
repository/spieltag/  TerminRepository      JPA + JdbcClient-Fragment
                      TerminserieRepository JPA, ohne eigene Abfragen
                      TeilnahmeRepository   nur JdbcClient, ohne Entity
service/spieltag/     TerminService    lesen, anlegen, aendern, absagen, entfernen,
                                       Auto-Abschluss, Zaehler-Nachtrag
                      SerienService    anlegen samt Materialisierung (max. 52 Termine)
                      TeilnahmeService rueckmeldung, gastStufeAendern, uebersicht
controller/spieltag/  TerminController           lesen und rueckmelden
controller/admin/     TerminVerwaltungController anlegen, aendern, absagen, entfernen,
                                                 Serie, Gast-Stufe
```

**`TeilnahmeRepository` hat bewusst keine Entity.** Die Tabelle wird angehängt, bedingt
aktualisiert und aggregiert gelesen – die Fälle, für die `AGENT_SERVER.md` das erlaubt. Eine
Entity mit `@Version` wäre hier nachteilig: Optimistic Locking meldet den Konflikt erst beim
Schreiben, während `ON CONFLICT` den Wettlauf zweier gleichzeitiger Meldungen ohne
Wiederholung entscheidet.

**Fachbereich `team` (S5, Pakete 1 bis 5, 06.09.2026):**

```
domain/spieltag/      Aufstellungsspieler   ein Teilnehmer, wie der Generator ihn sieht
                      ManuelleAuswahl, Gastauswahl   die Eingabe des manuellen Laufs (A24)
domain/team/          Aufstellung    Teilnehmer + aktive Kategorien, kennt ihre Herkunft nicht
                      Teamaufteilung Indizes beider Teams, Kosten, verwendetes Verfahren
repository/spieltag/  AufstellungRepository  drei Abfragen ohne Entity: Zusagen eines Termins,
                                             benannte Profile, Gast-Vorlagen je Stufe
service/spieltag/     AufstellungService     fuerTermin(...) und manuell(...) - hier endet die
                                             Verzweigung zwischen den beiden Quellen
service/team/         Zielfunktion           flache Matrix, ganzzahlig in Hundertsteln
                      Teamverfahren          die einzige Schnittstelle zum Rest von S5
                      ExhaustivVerfahren     Enumeration, Reservoir-Sampling, MAX_EXHAUSTIV = 24
                      HeuristikVerfahren     Snake-Draft + Simulated Annealing
                      TeamverfahrenAuswahl   Map<AlgorithmType, Teamverfahren>, kein switch
```

**`AufstellungRepository` bündelt drei Tabellen** (`spieltag.teilnahme`, `profil.spieler`,
`profil.gast_vorlage`), und das ist Absicht: Aufgeteilt auf drei Repositories stünden der
`aktiv`-Filter und der Ausschluss des Adminprofils an drei Stellen. Es ist **eine** fachliche
Frage – wer wird eingeteilt.

**Der Schnitt zwischen `spieltag` und `team` ist die Zeile, an der A24 billig wird.** Alles, was
den Spieltag kennt – Termin, Zusage, Sperre, Konfiguration –, liegt in `spieltag`; ab
`Aufstellung` weiß niemand mehr, woher die Liste kommt. Deshalb berührt A24 die Abschnitte 3 bis
5 nicht, und deshalb bleibt `TeamverfahrenTests` ohne Spring-Kontext lauffähig.

**Zum Paketschnitt:** Der Verwaltungscontroller liegt in `controller/admin`, seine DTOs in
`dto/spieltag`. Kein Widerspruch – `admin` ist ein Zugriffs-, kein Datenbereich. Die Regel, nach
der Skill-DTOs unter `dto/admin` bleiben, greift hier nicht: Termine tragen keine Bewertungen.

**Datenmodell: 18 Tabellen, `V001`–`V011`.** S2b und S3 kamen ohne Migration aus. Die drei
letzten ergänzen nur Spalten (alle 30.08.2026): `V009` `auswechsel_modus` (A20b), `V010` den
Vorgabetext für `halle_absage_vorlage` (A23), `V011` die drei Bilanz-Zähler in `profil.spieler`
(A21). **S5 braucht keine Migration** – `V006` legt `team_generierung`, `team_zuteilung` und
`generierung_kontingent` bereits vollständig an. **Das gilt seit dem 05.09.2026 aus zwei Gründen
zugleich:** die Entscheidungen aus `S5_UMSETZUNG.md`, 0.4, **und** der Verzicht darauf, den
manuellen Lauf nach A24 zu speichern (0.6). Fällt eines von beiden, fällt die Aussage.

**Die 32 Endpunkte, nach Bereichen.** Zweck, Körper und Antworten stehen in
`fubo-api.json` – hier nur die Landkarte, damit eine Änderung nicht an zwei Stellen gepflegt
werden muss:

| Bereich | Pfade unter `/api/v1` | Anzahl |
|---|---|---:|
| Anmeldung und Sitzung (S2) | `auth/pin/pruefen`, `auth/users/lesen`, `auth/user/waehlen`, `auth/gast/anmelden`, `auth/admin/anmelden`, `auth/session/{lesen,erneuern,beenden}` | 8 |
| Zugangsdaten (S2b) | `auth/passwort/{zuruecksetzen,bestaetigen}`, `admin/{passwort,pin,name}/aendern` | 5 |
| Spielerverwaltung (S2b, S3) | `admin/user/{anlegen,bearbeiten,entfernen,blockieren,lesen}`, `admin/skills/lesen` | 6 |
| Konfiguration (S3) | `admin/config/{lesen,aendern}` | 2 |
| Gastverwaltung (30.08.) | `admin/gast/{lesen,freigeben}` | 2 |
| Termine lesen und melden (S4) | `termine/lesen`, `termine/{terminId}/lesen`, `termine/rueckmeldung` | 3 |
| Terminverwaltung (S4) | `admin/termin/{anlegen,aendern,absagen,entfernen}`, `admin/serie/anlegen`, `admin/teilnahme/gast-stufe` | 6 |
| *geplant S5* | *`teams/generieren`, `admin/teams/generieren` (A24)* | *+2* |

**Der Ort eines Endpunkts ist die Autorisierungsentscheidung.** Alles unter `/api/*/admin/**`
verlangt `ROLE_ADMIN`; die Reset-Endpunkte und die drei Login-Wege sind ausschliesslich in
`PIN_VERIFIED` erreichbar; alles Übrige fällt unter
`anyRequest().hasAnyRole("USER", "ADMIN", "GAST")`. **S4 hat der Filterchain nichts
hinzugefügt** – die Leseendpunkte für Termine liegen bewusst *nicht* unter `/admin/`, und genau
das lässt Gäste Termine sehen. Wer einen Terminendpunkt unter `/admin/` anlegte, sperrte Gäste
aus, ohne eine Regel zu ändern. **Die Pfade stehen trotzdem namentlich in
`SecurityConfigTests`:** Die Platzhalterprüfung bliebe grün, wenn jemand für einen echten
Endpunkt eine offenere Regel **davor** setzte – Spring Security wertet die Matcher der Reihe
nach aus, die erste passende gewinnt.

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
- **Wo JPA schreibt und natives SQL liest, muss geflusht werden** (`saveAndFlush`).
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
  200 Tage/17:30, `SpielerControllerTests` 300 Tage/16:05, mit S5 `TeamGeneratorTests`
  500 Tage/20:15. Wer eine weitere anlegt, vergibt den nächsten. **Beide Achsen zählen** – ein
  bereits vergebener Tag mit anderer Uhrzeit hielte zwar am Constraint, kollidiert aber mit dem
  Nächsten, der nur die Tage vergleicht.
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

**Zuletzt grün am 06.09.2026 – 340 Tests in 27 Klassen.** Verlauf: 148 in 16 Klassen (22.08.),
184 (23.08.), 227 in 21 und 244 in 22 (beide 29.08.), 247/260/264/265 in 23 (30.08.), 300 in 25
(S4, Pakete 1–4, 30.08.), 331 in 26 (S4, 31.08.), 340 in 27 (S5, Pakete 1–5, 06.09.).

**`TeamverfahrenTests` ist die vierte Klasse ohne Spring-Kontext** – neben
`SessionAuthFilterTests`, `SessionCookieFactoryTests` und `BruteForceServiceTests`. Sie läuft in
0,3 Sekunden und macht die Gegenprobe belastbarer: Sind alle vier grün und der Rest rot, liegt
ein Kontextfehler vor und kein Anwendungsfehler.

**Der vorab gezählte Erwartungswert traf jedes Mal exakt** – `grep -c '^\s*@Test\s*$'` je
Klasse. **Die Klassenzahl war einmal falsch, weil sie fortgeschrieben statt gezählt wurde.**
Beide Zahlen deshalb immer gleich ermitteln, nach dem Lauf aus den Berichten:

```bash
awk -F'[:,]' '/^Tests run:/ {t+=$2; k++} END {print k" Klassen, "t" Faelle"}' \
    target/surefire-reports/*.txt
```

**`SecurityConfigTests` bleibt bei 26**, obwohl mit jedem Meilenstein Pfade dazukommen: Sie
stehen als Zusicherungen *innerhalb* der bestehenden Bündelfälle. Wer die Fallzahl als Mass für
die Abdeckung liest, unterschätzt diese Klasse systematisch.

**Scheitert ein Lauf, zuerst die Surefire-Berichte lesen, nicht die Maven-Zusammenfassung.** Bei
einem Kontextfehler meldet Spring Test jeden betroffenen Fall einzeln, aber nur der *erste*
Bericht je Kontextkonfiguration nennt die Ursache – alle anderen tragen
`ApplicationContext failure threshold (1) exceeded`. 115 Fehler bedeuten dann **eine** Ursache.
Kürzester Weg: `grep -h 'Caused by' target/surefire-reports/*.txt | tail -1`.

**Was der Testlauf nicht abdecken kann, decken die manuellen Prüflisten ab** – jeder Fall läuft
in einer zurückgerollten Transaktion und kann keine Sitzung wirklich ablaufen lassen. Die Listen
stehen in `S2b_UMSETZUNG.md` (12.1), `S3_UMSETZUNG.md` (10.1) und `S4_UMSETZUNG.md` (11.1); die
zu S2b und S3 sind am 30.08.2026 abgearbeitet. Sie bleiben stehen – nicht als offene Aufgabe,
sondern als Vorlage nach jeder Änderung an Sitzungen, Gastplätzen oder Konfiguration.

Drei Punkte zur Gastverwaltung stehen in keiner Anleitung, die Bruno-Requests unter
`admin/gast/`:

| Prüfpunkt | Erwartung |
|---|---|
| Als Gast anmelden, `sessionLeerlaufMinuten` auf 1, warten, `/admin/gast/lesen` | `belegt: true` mit `sitzungGueltig: false` – der Zustand, der den nächsten Gast aussperrt |
| Zweiten Gast über `baseUrlOhneCookie` anmelden, Platz freigeben, mit seinem Cookie `/auth/session/lesen` | `401`, nicht `200` – die Freigabe widerruft die Sitzung |
| `anzGuests` von 4 auf 2 senken bei vier belegten Plätzen | Plätze 3 und 4 mit `wirksam: false` und weiter `belegt: true`; gelöscht wird nichts |

## 7. Nächste Schritte

1. **S5 weiterbauen** nach `harness/tmp/S5_UMSETZUNG.md` **und `harness/tmp/S5_ALGORITHMUS.md`**
   (zusammen 24,0 h inkl. A24; Abschnitte 1 bis 5 sind gebaut, offen bleiben rund 12,5 h).
   Als Nächstes **Abschnitt 6, Kontingent und Seed** – und damit sofort der Punkt, der drei
   Testklassen anfasst: **`AktiveSitzung` bekommt `gastSlotId`** (15, Punkt 5), sonst hat ein
   Gast kein Kontingent. **Zwei Nachträge aus S4 gehören zu S5** und sind leicht zu übersehen:
   Sperren nimmt die Zusagen zurück (Reihenfolge beachten – erst die Version erhöhen, dann die
   Zusage zurücknehmen), und `teams_fixiert` wird vom bestehenden A18-Auftrag bei Terminbeginn
   gesetzt.
2. **A24 in die Gesamtspezifikation nachziehen – überfällig, der Code steht.** Die Anforderung
   steht nur in `AGENT_SERVER.md`. `/PRJ_FuBo/harness/AGENT.md` ist die maßgebliche Quelle und
   kennt sie nicht; solange das so bleibt, widersprechen sich die beiden Dokumente. `A20b` ist
   bei der Gelegenheit zu präzisieren, falls Weggabelung A im Sinne der Alternative entschieden
   wird.
3. **Nach Abschluss von S5: alle Handprüfungen in einem Zug** (entschieden am 06.09.2026 – eine
   halbe Generierung zeigt nichts, was sich prüfen liesse). Es sind drei Listen:
   - **S4, `S4_UMSETZUNG.md` 11.1** – die beiden Punkte, die eine laufende Anwendung brauchen:
     der automatische Terminabschluss im Betrieb (Termin per SQL in die Vergangenheit setzen,
     fünf Minuten warten) und die Gast-Stufe über den Sitzungsablauf hinweg. Alles Übrige dort
     ist über die Bruno-Ordner `termine` und `admin/termin` gangbar.
   - **S5, `S5_UMSETZUNG.md` 13.1** – darunter die sechs Punkte zu A24, allen voran: fünfmal
     derselbe Aufruf muss fünfmal `200` liefern, und `spieltag.team_generierung` muss danach
     unverändert sein.
   - **Gastverwaltung, 6.4** – die drei Punkte, die in keiner Anleitung stehen.
4. **Client-Track informieren:** die drei brechenden Vertragsänderungen (4.1) – `anmeldename` im
   Admin-Login, vollständige `skills` beim Anlegen eines Profils, `auswechselModus` im
   Voll-Update der Konfiguration – **und der neue Adminbildschirm für A24** samt der Punkte aus
   4.3, allen voran: Das Ergebnis des manuellen Laufs wird nicht gespeichert.

**Offene Punkte, die keine Aufgabe für heute sind:**

- **Die Zeitzone der Datenbanksitzung ist nicht gesetzt.** Ohne Folge, solange alle
  Zeitvergleiche über die `Clock`-Bean laufen. Sobald eine Abfrage `current_date` oder
  `current_time` benutzt, gehört `TimeZone` in die Datenbankkonfiguration oder der Wert als
  Parameter in die Abfrage.
- **`termin.teams_fixiert`** bekommt mit S5 seine erste Bedeutung – und bleibt dabei
  grösstenteils redundant zu den bestehenden Statusprüfungen. Begründung in `S5_UMSETZUNG.md`,
  10.2.
- **Der manuelle Lauf hinterlässt nur den Audit-Eintrag**, und der wird nach 90 Tagen gelöscht
  (`fubo.audit.aufbewahrung-tage`). Bewusst so entschieden; wird es zum Problem, ist die Antwort
  die Migration `V012` aus `S5_UMSETZUNG.md`, 0.6 – **nicht** eine längere Löschfrist, denn die
  Frist gilt dem Personenbezug und nicht der Nachvollziehbarkeit von Rechnungen.
- **`configs.app_config.anz_guests` gilt im manuellen Lauf nicht.** Der Wert begrenzt
  gleichzeitige Gastsitzungen, nicht Mitspieler auf dem Platz; die Summe begrenzt dort
  `max_teilnehmer`. Wer das ändern will, ändert eine Bedeutung, keine Zahl.
- **Betriebsaufgabe ohne Code:** Custom Domain `app.<domain>` in Cloudflare Pages einrichten.
  Ohne sie funktioniert die Anmeldung produktiv nicht – `pages.dev` steht auf der Public Suffix
  List und wäre gegenüber `api.<domain>` cross-site, mit `SameSite=None; Secure`, zwingendem
  CSRF-Schutz und einem Cookie, das Safari und der Chrome-Inkognito-Modus blockieren. Ebenfalls
  offen: Pages-Preview-Deployments, in denen der Login bauartbedingt nicht funktioniert.
- **Deployment (S8):** Entwurf mit Dockerfile, Compose-Ergänzung, nginx-Block, Backup und
  Rollout liegt in `harness/tmp/S8_DEPLOYMENT.md`.

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
