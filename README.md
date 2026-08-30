# FuBo-Server

Backend der FuBo-Anwendung (Team-Balancer für Fussballspieltage).
Das zugehörige Frontend liegt in einem eigenen Repository: `FuBo-Client`.

- Entwickler: Denis Kim
- Techstack: Java 25, Spring Boot 4.1, PostgreSQL 17, Maven
- KI-gestützte Entwicklung mit Claude-Code

### Start der Umgebung mit Testdaten
```
cd ~/Projects/PRJ_FuBo/server
// .env anpassen/ergänzen: DB_USER=..., DB_PASSWORD=...

// Docker Container löschen
docker compose -f compose.dev.yml down -v
ODER
docker compose -f compose.dev.yml down

// Docker starten
docker compose -f compose.dev.yml --env-file .env up -d

// Server starten
./mvnw spring-boot:run   // Flyway legt alle Migrationen neu an

// Testdaten laden
./scripts/seed-lokal.sh scripts/data/spielerprofile_anonym.sql
```

## Dokumentation
- Serverseitige Vorgaben und Meilensteine: `harness/`
- **Schnittstellenvertrag zum Frontend: [`fubo-api.json`](fubo-api.json)** (OpenAPI 3.1).
  Massgebliche Quelle fuer den Client-Track. Server und Client liegen in getrennten
  Repositories, ein gemeinsamer Commit ist deshalb nicht moeglich - jede Vertragsaenderung
  wird zuerst hier abgebildet und der Client anschliessend nachgezogen.
  Beschrieben ist ausschliesslich, was tatsaechlich umgesetzt ist (derzeit S2, Auth und
  Session); spekulative Endpunkte waeren ein Vertrag ueber etwas, das es nicht gibt.

### Erste Anmeldung
Beim allerersten Start legt die Anwendung die zentrale PIN und das Admin-Konto an. Dafuer
werden `FUBO_INITIAL_PIN` sowie `ADMIN_NAME`, `ADMIN_EMAIL` und `ADMIN_PASSWORD` aus der
`.env` gelesen (siehe `.env.example`). Fehlt eine der drei Admin-Angaben, bricht der Start
mit einer benennenden Meldung ab - ein willkuerlich gewaehlter Admin waere ein stilles
Sicherheitsproblem.

`ADMIN_NAME` bestimmt das Adminprofil. Existiert ein Profil dieses Namens, erhaelt es die
Rolle `ADMIN`; existiert keines, wird es angelegt. Eine leere Datenbank ist damit kein
Hindernis mehr. Die Startmeldung sagt ausdruecklich, ob ein vorhandenes Profil uebernommen
oder ein neues angelegt wurde; bei einem Tippfehler in `ADMIN_NAME` steht dort also ein
unerwarteter Name.

**Das Adminprofil ist ein technisches Konto.** Es steht nicht in der Namensliste, nimmt an
keinem Termin teil und wird nie in ein Team eingeteilt; seine Skillwerte werden je Kategorie
auf 0 gesetzt. Der Admin meldet sich deshalb nicht ueber die Namenswahl an, sondern ueber
`POST /api/v1/auth/admin/anmelden` mit `ADMIN_NAME` und `ADMIN_PASSWORD` - und auch dafuer
zuerst ueber die zentrale PIN. Wer selbst mitspielt, braucht dafuer ein eigenes, normales
Spielerprofil.

**`ADMIN_NAME` ist zugleich der Anmeldename (seit 29.08.2026).** Der Endpunkt verlangt Name
und Passwort; der Name ist der Profilname des Adminprofils. Er ist ueber keinen Endpunkt
abrufbar, weil das Adminprofil aus der Namensliste ausgeschlossen ist - genau das macht ihn als
zweite Angabe brauchbar, und genau deshalb gehoert er behandelt wie ein Geheimnis. Falscher
Name und falsches Passwort liefern dieselbe Antwort (`401 ADMIN_PASSWORT_FALSCH`); der Server
rechnet den BCrypt-Vergleich auch bei falschem Namen zu Ende, damit die Laufzeit nichts
verraet.

**Die Schreibweise zaehlt.** Der Name wird zeichengenau geprueft, Gross- und Kleinschreibung
eingeschlossen; nur Randleerzeichen entfernt der Server. Damit sich dabei niemand aussperrt,
legt der Bootstrap den Profilnamen genau so ab, wie `ADMIN_NAME` ihn nennt: Er sucht
zeichengenau (`findByName`) und **bricht den Start ab**, wenn ein vorhandenes Profil allein in
der Schreibweise abweicht. Die Meldung nennt beide Formen. Der Abbruch ist das mildere Mittel -
er kostet einen Neustart mit korrigierter `.env`, waehrend ein ausgesperrter Admin keinen
Selbstbedienungsweg hat: Der Passwort-Reset holt das Passwort zurueck, nie den Namen.

Nach dem ersten Start sind die Werte entbehrlich: Beide Bootstraps sind idempotent und
setzen nichts zurueck - insbesondere wird ein geaendertes Passwort nicht ueberschrieben.

> Das Adminprofil entsteht bewusst **nicht** ueber eine Flyway-Migration. Der Name ist eine
> Eigenschaft der Installation, keine des Schemas: Eine Migration mit Platzhalter erzeugte
> bei gleicher Pruefsumme je Installation andere Daten, und der Name bliebe unveraenderlich
> in der Git-Historie stehen.