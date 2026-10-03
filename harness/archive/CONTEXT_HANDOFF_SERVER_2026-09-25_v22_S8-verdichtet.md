# Context Handoff – FuBo Backend (Server)

> Übergabedokument für den Server-Agenten. Ergänzt `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md`
> (Gesamtstand) um den serverseitigen Anteil. Systemprompt: `AGENT_SERVER.md`.
> Gesamtspezifikation: `/PRJ_FuBo/harness/AGENT.md`.
>
> **Repository:** eigenständig mit Wurzel in `server/` (`FuBo-Server`, GitHub, **öffentlich**).
> **Kein Monorepo** – das Frontend liegt getrennt (`FuBo-Client`). `PRJ_FuBo/` und
> `PRJ_FuBo/harness/` sind bewusst **nicht** versioniert. Gearbeitet wird auf `dev`.
>
> **Dieses Dokument beantwortet drei Fragen:** *Was steht?*, *Was ist entschieden und darf nicht
> versehentlich rückgängig gemacht werden?*, *Was fällt dem Nächsten auf die Füsse?* Alles andere
> ist ausgelagert:
> - **Verbindliche Regeln** → `AGENT_SERVER.md` (Systemprompt, wird ohnehin gelesen)
> - **Herleitung und Schritt für Schritt** → `harness/tmp/S<n>_UMSETZUNG.md`, für den
>   Algorithmusteil von S5 zusätzlich `harness/tmp/S5_ALGORITHMUS.md`
> - **Historie** → Git und `harness/archive/`. Vorfassung:
>   `CONTEXT_HANDOFF_SERVER_2026-09-15_v21_S8-Abschnitte1-11.md` (sie trägt die vollständige
>   Chronik von S8 samt der sieben Abweichungen von der Anleitung und der fünf Weggabelungen);
>   letzte Langfassung mit allen Herleitungen `…_v14_S4-abgeschlossen.md`
>
> **Verdichtet am 31.08.2026 und erneut am 25.09.2026.** Beim zweiten Mal ist die S8-Chronik
> entfallen: Sie beantwortete nach dem grünen Lauf keine der drei Fragen mehr. Was aus ihr
> tragend war, steht jetzt in 6.2 und 6.3.
>
> Was hier steht, steht **nur** hier. Wird eine Festlegung zur Architekturregel, wandert sie
> nach `AGENT_SERVER.md` und **verschwindet hier** – sonst laufen beide auseinander.

## Stand: 25.09.2026

**S0 bis S8 sind abgeschlossen und verifiziert.** `./mvnw clean verify` lief am 15.09.2026 grün:
**483 Fälle in 34 Klassen**, ohne Fehlschlag und ohne übersprungenen Fall. Alles ist auf `dev`
committet (18 Commits für S8), **nicht gepusht**.

| Gegenstand | Stand |
|---|---|
| Vertrag `fubo-api.json` | **44 Endpunkte** (S8 brachte sechs) |
| Datenmodell | **19 Tabellen**, `V001`–`V014` |
| Konfiguration | **14 Pflichtfelder** im Voll-Update |
| `Fehlercode` | **34** Werte |
| `AuditAktion` | **29** Werte |
| Tests | **483 Fälle in 34 Klassen**, davon **fünf Klassen ohne Spring-Kontext** |
| Bruno-Collection | alle 44 Endpunkte, acht Ordner (`~/Documents/bruno/fubo_server`, unversioniert) |

**Was S8 gebracht hat:** Web Push nach RFC 8030/8291/8292 **ohne Fremdbibliothek**, mit
JDK-Bordmitteln. Sechs Endpunkte (fünf unter `/push/`, `POST /admin/push/test` für den
Probeversand), `V014` mit `profil.push_abo` samt Personenschalter
`spieler.push_erwuenscht`, Einmalvermerk `termin.push_erinnerung_am` und den beiden
Konfigurationsfeldern `push_aktiv`/`push_erinnerung_stunden`. Zwei Versandanlässe: der
Erinnerungsauftrag an offene Rückmeldungen (alle fünf Minuten) und die Terminabsage über
**drei** Auslöserpfade. Die Verschlüsselung ist gegen **RFC 8291, Anhang A** geprüft – byteweise,
als Testfall in `PushVerschluesselungTests`.

**Der einzige Prüfpunkt, den nichts anderes ersetzt.** Ein Push-Dienst nimmt auch eine falsch
verschlüsselte Nachricht mit `201` an; der Browser bekommt sie und kann sie nicht öffnen. Es gibt
keinen Fehlerkanal, kein Protokoll und keinen Statuscode dafür. **Wer an `Nutzlastverschluesselung`,
`P256` oder `VapidJwtErzeuger` etwas ändert, prüft gegen Anhang A** – nicht gegen ein Gerät, und
erst recht nicht gegen die Antwort des Dienstes.

**Offen ist nur noch Handarbeit und S9:**

1. **Fünf Handprüflisten** – `S4_UMSETZUNG.md` 11.1, `S5_UMSETZUNG.md` 13.1, `S6_UMSETZUNG.md` 8.1,
   `S7_UMSETZUNG.md` 8.1, `S8_PUSH_UMSETZUNG.md` 13.1, dazu die drei Punkte zur Gastverwaltung aus
   6.4. Alle brauchen eine laufende Anwendung und werden in einem Zug abgearbeitet. **Bei S7 mit
   besonderer Vorsicht:** Dort gehen echte Mails raus – vorher `halle_email` auf eine eigene
   Adresse setzen.
2. **S9: Härtung und Deployment** (14 h), Entwurf in `harness/tmp/S9_DEPLOYMENT.md`. Der Entwurf
   kennt S8 noch nicht: **drei VAPID-Variablen, ausgehendes HTTPS zu den Push-Diensten und
   `TZ=Europe/Berlin` im Compose-Dienst** kommen hinzu, ebenso die **Sicherung des
   VAPID-Schlüsselpaars** – geht es verloren, muss jeder Spieler erneut zustimmen.
3. **Client-Track informieren.** Der Vertrag steht bei 44 Endpunkten; die Push-Zeilen in 4.1 sind
   seit dem Commit in `fubo-api.json` **verbindlich** und nicht mehr Vorausschau.

**Zwei Dinge vor jedem Lauf:** Docker muss laufen (`docker info`), und es ist `./mvnw clean verify`,
nicht nur `verify` – `target/classes` vergisst nichts.

---

## 1. Kontext

Serverseitige Bereitstellung der FuBo-Logik über eine abgesicherte JSON-API: Profile und Skills,
Termine und Teilnahmen, Teamgenerierung, Ergebniserfassung, Auth/Session, Hallenmodus und
Push-Benachrichtigungen. Datenhaltung in PostgreSQL 17 (drei Schemas). Zugang über zentrale PIN,
danach Namensidentität. Rollen ADMIN, USER, GAST.

## 2. Techstack & Architektur (Server)

- Java 25, Spring Boot 4, Maven. PostgreSQL 17 (Schemas `profil`, `spieltag`, `configs`), Flyway.
- Testcontainers + JUnit; `spring-boot-starter-mail` (Bestätigungs-PIN, Hallenabsage).
- Hosting: Raspberry Pi 5, Docker/Compose, Nginx (Reverse-Proxy), Cloudflared-Tunnel
  (`assets/Deployment/`). Ein Zweit-Pi mit anderem Setup muss möglich bleiben.
- **Abhängigkeiten:** `actuator`, `data-jpa`, `flyway` (+ `flyway-database-postgresql`),
  `security`, `validation`, `webmvc`, `mail`, `postgresql`; im Test die `*-test`-Starter,
  `spring-boot-testcontainers`, `testcontainers-postgresql`. **Kein Cache-Starter** – der
  `CacheManager` entsteht von Hand aus `spring-context`. **Keine Push-Bibliothek** – Web Push
  läuft über `java.security`, `javax.crypto` (`KDF`/`HKDFParameterSpec`, final in JDK 25) und
  `java.net.http.HttpClient`.
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
- **Web Push ohne Fremdbibliothek** (14.09.2026): `nl.martijndwars:web-push` 5.1.2 zieht Bouncy
  Castle und drei weitere Abhängigkeiten nach; die drei RFCs sind mit Bordmitteln in rund 300
  Zeilen umsetzbar und gegen Anhang A prüfbar. **Das VAPID-Schlüsselpaar ist ein
  Betriebsgeheimnis** und steht ausschliesslich in Umgebungsvariablen, nie in
  `configs.app_config` – jene Tabelle wird über einen Admin-Endpunkt gelesen und geschrieben.

## 4. Schnittstelle zum Frontend (Vertrag)

**Maßgeblich ist `server/fubo-api.json`** – OpenAPI 3.1 in JSON auf der Repo-Wurzel und damit
mitversioniert. **Bei Abweichungen gilt die Datei, nicht dieses Dokument.**

**Umfang: 44 Endpunkte** (Landkarte in 6.1). Aufgenommen wird nur, was umgesetzt ist. **S9 bringt
keine** – es ist Härtung und Deployment. Kernpunkte: REST/JSON, getrennte Origins mit
CORS-Allowlist (`allowCredentials`), HttpOnly-Session-Cookie, `401`/`403`-Semantik, DTOs ohne
Skillwerte für USER und GAST, Belegtstatus zum Pollen, einheitliches Fehler-JSON nach RFC 9457.

**Nullbarkeit steht als Typunion, nie als `nullable: true`.** Die Datei ist 3.1, dort ist
`nullable` kein Schlüsselwort – ein Generator ignoriert es kommentarlos, und der Client bekäme
einen nicht-nullbaren Typ für ein Feld, das `null` sein kann. Am 12.09.2026 an vier Stellen aus
S5 gefunden und behoben; die Regel steht seither in `AGENT_SERVER.md`. **Wer vor dem 12.09.
generiert hat, generiert neu.**

### 4.1 Was der Client-Track wissen muss

**Eine Tabelle statt einer Meilensteinchronik.** Jede Zeile ist eine Stelle, an der eine
naheliegende Annahme falsch ist. Wann sie dazukam, steht in Git.

**Formulare und Schreibpfade**

| Punkt | Bedeutung für den Client |
|---|---|
| **Sechs brechende Änderungen** | `anmeldename` in `AdminLoginRequest`, vollständige `skills` in `SpielerAnlegenRequest`, `auswechselModus`, `hallenModusAktiv` (13.09.) und – seit S8 – `pushAktiv` und `pushErinnerungStunden` im Konfigurations-Voll-Update. Alle sechs betreffen Formulare, alle sechs liefern sonst `400` |
| `hallenModusAktiv`, `pushAktiv` | **Pflichtfelder, kein stillschweigendes `false`.** Wer sie weglässt, bekommt `400` mit dem Schlüssel im Block `felder` – sonst schaltete ein Client, der das Feld nicht kennt, die Funktion bei jedem Speichern ab. Beide sind deshalb `Boolean` und nicht `boolean`; bei Zahlenfeldern übernimmt das `@Min` dieselbe Aufgabe |
| `/admin/config/aendern` | **Voll-Update**: vorher `lesen`, dann alle **vierzehn** änderbaren Felder samt `version` zurückschicken. `halleAbsageVorlageEffektiv` gehört **nicht** dazu – nur lesbar |
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
| `PUSH_NICHT_KONFIGURIERT` (`503`) | „auf diesem Server nicht eingerichtet", **kein Fehler**. Betrifft `GET /push/schluessel/lesen` und `POST /admin/push/test`; die übrigen vier Push-Endpunkte antworten trotzdem `200`. Wiederholen hilft nie – den Bereich ausblenden |

**Endgültiges – gehört in die Bestätigungsabfrage**

| Punkt | Bedeutung für den Client |
|---|---|
| `TerminStatus` | `GEPLANT`, `ABGESAGT`, `ABGESCHLOSSEN`. **Eine Absage ist endgültig** – kein Weg zurück nach `GEPLANT` |
| `/admin/termin/entfernen` | löscht endgültig, aber nur ohne Verweise (`409 TERMIN_IN_VERWENDUNG`). **Der einzige Weg zurück aus einer versehentlichen Absage** – ein abgesagter Termin belegt seinen Zeitpunkt weiter |
| **Ein Ergebnis lässt sich nicht löschen** | A21 sieht nur die Korrektur vor. Es gibt keinen Endpunkt dafür, und es wird keinen geben, ohne dass jemand ihn anfordert |
| **Eine versandte Hallenabsage lässt sich nicht zurücknehmen** | Es gibt keinen Endpunkt und keinen Weg, die Mail ungeschehen zu machen. Die Bestätigungsabfrage muss sagen, dass die Nachricht **sofort** hinausgeht |
| **Eine zugestellte Push-Nachricht ebenso nicht** | Deshalb gibt es für beide Versandanlässe **keinen** Endpunkt: Die Erinnerung läuft als Auftrag, die Absagenachricht hängt am Statuswechsel. Ein „jetzt erinnern"-Knopf wäre ein Knopf, der sich nicht zurücknehmen lässt |

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
| Erinnerung an offene Rückmeldungen | läuft **alle fünf Minuten** von selbst, für Termine innerhalb von `pushErinnerungStunden`. **Je Termin genau einmal** – der Vermerk steht vor dem Versand |
| Verschieben eines Termins | setzt `teams_fixiert` **und** den Erinnerungsvermerk zurück. Der Termin kann also eine zweite Erinnerung bekommen – an dieselben Nichtantworter, und das ist gewollt |
| Erloschene Abonnements | verschwinden nach **30 Tagen** im nächtlichen Aufräumlauf. Bis dahin belebt ein erneutes `abo/anlegen` dieselbe Zeile wieder |

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
| `/push/**` ist die **Ausnahme** von „jeder Angemeldete darf" | Ein `GAST` bekommt an allen fünf Pfaden `403` (A25d) – nicht `401` und keine leere Antwort. Die Push-Einstellungen gehören in der Gastansicht **ausgeblendet**, nicht deaktiviert angezeigt |

**Fristen und Vorlagen (S7)**

| Punkt | Bedeutung für den Client |
|---|---|
| `halleAbsageMoeglichBis` | Spätester Zeitpunkt der Absage, **immer gefüllt** – auch für vergangene, abgesagte und nicht konfigurierte Hallen. Ortszeit **ohne Zone**, Format `YYYY-MM-DDTHH:MM:SS`. Liegt er in der Vergangenheit, ist das Fenster zu |
| **Es gibt kein `halleAbsageMoeglich`** | Eine Berechtigungsaussage gehört nicht in ein rollenneutrales Antwortobjekt. Der Adminbildschirm rechnet sie selbst – **das blendet einen Knopf aus, es ist keine Sicherung.** Der Server lehnt unabhängig davon mit `409` ab |
| `halleAbsageVorlageEffektiv` | **Nur lesbar.** Zeigt, welchen Fliesstext der Server verwenden würde – die gepflegte Vorlage oder seinen Ersatz. **Gehört nicht in das Voll-Update**; dort wird er ignoriert |
| `hallenModusAktiv` | Hauptschalter, Vorgabe `false`. Steht er aus, ist der Absageknopf auszublenden – und der Server lehnt mit `409 HALLE_MODUS_INAKTIV` ab. **Bei ausgeschaltetem Modus kommt dieser Code auch für eine unbekannte Termin-Id**, nicht `404` |
| `HALLE_BEREITS_ABGESAGT` (`409`) | **kein Bedienfehler**, sondern die Antwort auf den Doppelklick. Oberfläche: „ist schon raus" plus Zeitpunkt |
| `VERSAND_FEHLGESCHLAGEN` (`503`) | Es bleibt **kein** Zustand zurück: kein Vermerk, ein geplanter Termin bleibt geplant, **und es geht auch keine Push-Nachricht hinaus**. Der Aufruf lässt sich unverändert wiederholen |
| **`TERMIN_NICHT_ABGESAGT` gibt es nicht** | Die Anleitung sah ihn vor; mit der Kopplung von Hallen- und Terminabsage gibt es den Zustand nicht mehr. Wer ihn im Client vorgesehen hat, nimmt ihn heraus |

**Zahlen, die anders heissen, als sie sind**

| Punkt | Bedeutung für den Client |
|---|---|
| Antwort des manuellen Laufs (A24) | **`200`, nicht `201`**, und **ohne `seed`** – es entsteht nichts. Das Ergebnis wird **nirgends gespeichert** und ist nach dem Verlassen der Seite weg. **Das gehört sichtbar auf den Bildschirm** |
| `algorithmType` / `auswechselModus` in der Antwort | können von der Konfiguration **abweichen** (Rückfall bei zu vielen Teilnehmern bzw. fehlender Meldezeit). Anzeigen, nicht ignorieren |
| `differenzTeamstaerke` | die **Kosten der Zielfunktion**, nicht die Differenz der Gesamtstärken. `0` = perfekt ausgeglichen. Nur im Adminbildschirm |
| `maxTeilnehmer` im manuellen Lauf | **begrenzt, schneidet nicht ab** (`409 ZU_VIELE_TEILNEHMER`) |
| `deutlich` im `Ergebnis` | beschreibt die **Höhe, nicht den Ausgang** – ohne jeden Einfluss auf die Bilanz. Bei `sieger: "U"` unzulässig (`400`, Schlüssel `deutlichNurBeiSieg`); der Haken gehört dort ausgeblendet |
| `auswechselModus` ändern | ändert die **Einteilung** nicht, wohl aber den angezeigten Auswechselspieler bereits gespeicherter Läufe (A20b) |
| `empfaenger` im `Probeversand` | zählt **Geräte, nicht Personen** – es sind ja alle dieselbe. `0` heisst „auf diesem Konto ist kein Gerät angemeldet" |
| `zugestellt` im `Probeversand` | **keine Zustellbestätigung.** Web Push kennt keine; die Zahl sagt nur, wie viele Push-Dienste die Nachricht angenommen haben |

**Push-Benachrichtigungen (S8) – seit dem Commit in `fubo-api.json` verbindlich**

| Punkt | Bedeutung für den Client |
|---|---|
| **Zwei Schalter, nicht einer** | Der Personenschalter (`/push/einstellung/aendern`) gilt für **alle** Geräte des Spielers, der Widerruf (`/push/abo/entfernen`) nur für das aufrufende. Die Oberfläche bietet beide an und benennt den Unterschied – sonst entzieht der Nutzer die Browserberechtigung, und das Wiedereinschalten verlangt einen neuen Dialog |
| `GET /push/status/lesen` | liefert **nur die beiden serverseitigen Ebenen** (`anlageAktiv`, `pushErwuenscht`). Die Geräteebene liest der Client lokal über `pushManager.getSubscription()` – ein `GET` könnte das aufrufende Gerät nicht identifizieren, ohne die Endpoint-Adresse in die URL zu schreiben |
| `POST /push/abo/anlegen` | **bei jedem Anwendungsstart erneut aufrufen.** Der Aufruf ist über `endpoint_hash` idempotent und heilt genau den Fall, in dem der Server das Abonnement nach einem `410` deaktiviert hat, der Browser es aber noch führt. Er **setzt `fehlversuche` zurück** |
| Dieselbe Adresse, anderer Spieler | Die Zeile **wechselt den Eigentümer**. Der Unique-Constraint gilt global: Die Adresse identifiziert eine Browserinstallation, keine Person – auf einem geteilten Gerät bekäme sonst der Vorige weiter Nachrichten |
| Kein Anzeigename fürs Gerät | `geraet_bezeichnung` bestimmt der Server aus dem `User-Agent`, als Heuristik („Firefox auf Android"). Ein Eingabefeld dafür gibt es nicht und wird es nicht geben |
| `abo/entfernen` antwortet **immer** `200` | Eigene Adresse, unbekannte Adresse, fremde Adresse – dreimal `200`, und bei der fremden bleibt die Zeile stehen. Die Antwort beschreibt den erreichten **Zustand** des aufrufenden Geräts, nicht den Ausgang des Vorgangs |
| `vapidPublicKey` **nicht** ins Frontend einbauen | Er kommt aus `GET /push/schluessel/lesen`. Ein Wechsel des Paares entwertet **sämtliche** bestehenden Abonnements; dass der Client den neuen sofort bekommt, ist die Bedingung dafür, dass die Spieler erneut zustimmen können |
| Nichts kommt an, obwohl alles grün aussieht | **Drei Bedingungen gelten gleichzeitig.** Zwei stehen in `/push/status/lesen`, die dritte im Browser. Eine Meldung „keine Benachrichtigungen" ohne Angabe der Ebene schickt den Nutzer an die falsche Stelle |
| Abschalten wirkt auf **beide** Anlässe | Wer abschaltet, erfährt auch eine Terminabsage erst beim Öffnen der Anwendung. Darauf ist beim Abschalten **einmal** hinzuweisen; einen Schalter je Anlass gibt es bewusst nicht |
| Abschalten **löscht keine Abonnements** | Sonst verlangte das Wiedereinschalten einen neuen Browserdialog, auf jedem Gerät einzeln |
| iOS | Web Push erst ab 16.4 und **nur nach „Zum Home-Bildschirm"**. Auf dem iPhone ist A25(a) die technische Voraussetzung für A25(b); die Berechtigung ist aus einer Nutzergeste heraus zu erfragen |

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
S5: 18 → 24, S6: 8 → 13, S7: 6 → 12, S8: 12 → 22,5). **Acht von acht** – die Top-down-Zahl lag bei
S6 um 62 % daneben, die Schrittsumme um 18 %, und deren Abweichung bestand zur Hälfte aus Arbeit,
die es bei der Schätzung noch nicht gab.

| MS | Inhalt | Stand | h |
|---|---|---|---|
| S0 | Setup: Spring Boot, Maven, Modulstruktur, Compose | **abgeschlossen** | 8 |
| S1 | Datenmodell: 3 Schemas, Flyway, Seed, Testcontainers | **abgeschlossen** | 15 |
| S2 | Auth & Session: Filterchain, PIN-Login, Brute-Force, Zwei-Timer, Gast-/Admin-Login, Vertrag | **verifiziert (148/16)** | 23 |
| S2b | Zugangsdatenpflege und Spielerverwaltung, Aufräumjob | **verifiziert (29.08.2026)** | 10 |
| S3 | Profile & Skills API, Rollen, `configs` | **verifiziert (244/22)** | 11 |
| S4 | Termine & Teilnahme: Einzel/Serie, Teilnahme, `teilnehmer_version`, Min/Max + Warteschlange, Gast-Flow; A7, A18, A19 | **verifiziert (331/26)**; Handprüfliste 11.1 offen | 16 (17 + 3) |
| S5 | Teamgenerator: `EXHAUSTIV` + `HEURISTIK`, Zielfunktion inkl. Torwart-Gewicht, Kontingent/Seed/Snapshot, Auswechselspieler; **A24** | **verifiziert (385/29)**; Handprüfliste 13.1 offen | 18 → 24,0 |
| S6 | **Ergebnis und Bilanz**: „erster Eintrag gilt", Admin-Korrektur, Bilanz-Zähler, eigene Bilanz | **verifiziert (411/30)**; Handprüfliste 8.1 offen | 8 → 13,0 |
| S7 | **Hallenmodus**: E-Mail-Absage an den Betreiber, Vorlauffrist, Hauptschalter (A23); `V012`, `V013` | **verifiziert (431/31, nach Nachtrag 434/31)**; Handprüfliste 8.1 offen | 6 → 12,0 |
| S8 | **A25 serverseitig (Push)**: `V014`, sechs Endpunkte, Erinnerungsauftrag, Absage-Ereignis, Versandadapter ohne Fremdbibliothek – `harness/tmp/S8_PUSH_UMSETZUNG.md` | **verifiziert (483/34, 15.09.2026)**; Handprüfliste 13.1 offen | 12 → 22,5 |
| S9 | Härtung, Deployment (Docker/nginx/Cloudflared), API-Doku – Entwurf: `harness/tmp/S9_DEPLOYMENT.md` | offen | 14 |

**A25 wurde am 14.09.2026 zu S8**, die Härtung rückte auf S9. Grund: A25 bringt eine Migration,
sechs Endpunkte, einen `@Scheduled`-Auftrag und einen Adapter zu einem fremden Dienst mit – in ein
Härtungspaket geschoben, verwässerte es beide. Und die Härtung gehört ans Ende: Sie soll den Stand
absichern, der ausgeliefert wird, und der enthält Push. `harness/tmp/S8_DEPLOYMENT.md` heisst
seither `S9_DEPLOYMENT.md`.

Anleitungen: `harness/tmp/S<n>_UMSETZUNG.md`. **Ausnahme S5:** Der Algorithmusteil (Zielfunktion,
`EXHAUSTIV`, `HEURISTIK` – Abschnitte 3 bis 5) steht in `harness/tmp/S5_ALGORITHMUS.md`; die
Abschnittsnummern sind beibehalten, ein Verweis „3.1" meint dieselbe Stelle wie zuvor.

## 6. Code-Zustand (15.09.2026, Branch `dev`)

### 6.1 Was steht

Die datei- und klassenweisen Listen je Fachbereich sind am 12.09.2026 entfallen – sie standen
ohnehin in `src/` und veralteten mit jedem Commit. Die Langfassung liegt in
`archive/…_v18_S6-Pakete1-4.md`.

```
server/                        Repo-Wurzel (remote: FuBo-Server, oeffentlich)
  fubo-api.json                Endpunktkontrakt, 44 Endpunkte
  compose.dev.yml              postgres:17
  .env / .env.example          DB-Zugang, FUBO_INITIAL_PIN, ADMIN_*, SMTP_*, FUBO_VAPID_*
  scripts/                     seed-lokal.sh + anonymisierter 30er-Datensatz
  src/main/resources/db/       migration/ V001-V014, demodata/ (nur dev und test)
  src/main/java/de/fubo/appserver/
    common/      config error security
    controller/  auth admin spieltag ergebnis profil push
    service/     auth profil audit mail config spieltag team ergebnis push
    repository/  auth profil audit spieltag push
    domain/      auth profil audit config spieltag team push
    dto/         auth profil admin spieltag push
    utils/
```

**Die sieben Fachbereiche und wofür sie zuständig sind:**

| Bereich | Kern | Kam mit |
|---|---|---|
| `auth` | Filterchain, Zwei-Timer-Sitzung, drei Login-Wege, Passwort-Reset, Gastplätze | S2, S2b |
| `profil` | Spielerprofile, Skillwerte, Stammdaten-Zwischenspeicher, Bilanz | S2b, S3, S6 |
| `config` | die eine Zeile `configs.app_config`, Voll-Update mit `version` | S3 |
| `spieltag` | Termine, Serien, Teilnahmen, Warteschlange, Generierungslauf am Termin | S4, S5 |
| `team` | die Rechnung selbst: Aufstellung, Zielfunktion, beide Verfahren, Bankwahl | S5 |
| `ergebnis` | Ausgang erfassen und korrigieren, Bilanz neu berechnen | S6 |
| `push` | Abonnements, Verschlüsselung, VAPID, Versandlauf, zwei Anlässe | S8 |
| `audit` | Protokoll aller Adminaktionen und Läufe, Aufräumlauf | S2 |

**Vier Schnitte, die man kennen muss, weil sie beim Lesen nicht auffallen:**

- **`spieltag` gegen `team`:** Alles, was Termin, Sitzung, Kontingent und Protokoll kennt, liegt
  in `spieltag`; was mit einer Aufstellung *rechnet*, in `team` und kennt nichts davon. **Das ist
  die Zeile, an der A24 billig wurde.**
- **`admin` ist ein Zugriffs-, kein Datenbereich.** Der Verwaltungscontroller liegt in
  `controller/admin`, seine DTOs aber in `dto/spieltag`, weil Termine keine Bewertungen tragen.
  Nur DTOs *mit* Bewertung (`SpielerDetails`, `ManuelleEinteilung`) gehören nach `dto/admin` –
  dort trennt A12, nicht der Zugriffsweg. **Der Probeversand folgt derselben Regel:**
  `controller/admin/PushVerwaltungController`, DTO in `dto/push`.
- **Der Lesepfad von Ergebnis und Einteilung hat keinen eigenen Controller.** Beide erscheinen
  als Felder von `TerminDetails`. **Ein fünftes Feld wäre ein Anlass, über einen eigenen Endpunkt
  nachzudenken, nicht über ein weiteres.**
- **`push` kennt den Spieltag, aber nicht umgekehrt.** `PushBenachrichtigungService` liest Termine
  und Teilnahmen; `TerminService` und `HallenService` veröffentlichen nur ein Ereignis und wissen
  nichts von Abonnements. **Wer diese Richtung umdreht, koppelt den Absagepfad an einen fremden
  Dienst** – und eine gescheiterte Push-Nachricht liesse dann eine gespeicherte Absage scheitern.

**Datenmodell: 19 Tabellen, `V001`–`V014`.** `V014` ist die erste seit S3, die eine *Tabelle*
anlegt (`profil.push_abo`); `V009` bis `V013` ergänzen nur Spalten. **S2b, S3, S5 und S6 kamen
ohne Migration aus** – das hängt an Voraussetzungen, die in `S5_UMSETZUNG.md` 0.4/0.6 und
`S6_UMSETZUNG.md` 0.2/1.3 stehen; fällt eine davon, fällt die Aussage.

**Die 44 Endpunkte, nach Bereichen.** Zweck, Körper und Antworten stehen in `fubo-api.json` –
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
| Push (S8) | `push/schluessel/lesen`, `push/abo/{anlegen,entfernen}`, `push/einstellung/aendern`, `push/status/lesen`, `admin/push/test` | 6 |

**Der Ort eines Endpunkts ist die Autorisierungsentscheidung.** Alles unter `/api/*/admin/**`
verlangt `ROLE_ADMIN`; die Reset-Endpunkte und die drei Login-Wege sind ausschliesslich in
`PIN_VERIFIED` erreichbar; `/api/*/push/**` verlangt `USER` **oder** `ADMIN`; alles Übrige fällt
unter `anyRequest().hasAnyRole("USER", "ADMIN", "GAST")`.

**S4, S5 und S6 haben der Filterchain nichts hinzugefügt** – Termine, Generierung, Erfassen und
die eigene Bilanz liegen bewusst *nicht* unter `/admin/`, und genau das lässt Gäste mitmachen.
**S8 hat als Erstes seit S3 eine Regel gebraucht, und dort kehrt sich das Fehlerbild um:** Ein
fehlender Eintrag sperrt bei Push nicht, er **öffnet** – der neue Pfad fiele unter `anyRequest()`
und wäre für Gäste erreichbar, was A25d verbietet. `AGENT.md` schrieb das Gegenteil und ist am
14.09.2026 berichtigt.

**Die Pfade stehen trotzdem namentlich in `SecurityConfigTests`:** Die Platzhalterprüfung bliebe
grün, wenn jemand für einen echten Endpunkt eine offenere Regel **davor** setzte – Spring Security
wertet die Matcher der Reihe nach aus, die erste passende gewinnt. **Drei Paare unterscheiden sich
nur im Präfix** (`teams/generieren`, `ergebnis/erfassen` und, seit S8, `push/` gegen
`admin/push/`); bei ihnen fällt ein Tippfehler besonders schlecht auf.

### 6.2 Festlegungen, die nur hier stehen

Verbindliche Architekturregeln sind in `AGENT_SERVER.md` und werden hier nicht wiederholt. Was
bleibt, sind Weggabelungen: Entscheidungen, die auch anders hätten ausfallen können und die sich
nachträglich nur mit einer Vertragsänderung korrigieren liessen. Die vollständige Liste mit Datum
und Herleitung steht in `…_v14_S4-abgeschlossen.md`.

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
| **Höchstens 52 Termine je Serie**, als Konstante im `SerienService` | fängt den Tippfehler „2036" statt „2026" ab. Nicht in der Konfiguration: dort wäre es ein weiteres Pflichtfeld im Voll-Update und damit brechend |
| **Zeitzone der Anwendung in `fubo.zeitzone`** (`Europe/Berlin`), `Clock`-Bean läuft darin statt in UTC | `termin.datum`/`.uhrzeit` sind `DATE`/`TIME` **ohne** Zone, also Ortszeit. „Liegt das in der Vergangenheit" ist eine Frage nach der Wanduhr; mit UTC wäre die Antwort im Sommer zwei Stunden falsch. Die Rechnerzone genügt nicht – im Container ist sie UTC |
| Beim Anlegen entscheidet **`ON CONFLICT DO NOTHING RETURNING id`**, nicht eine vorgelagerte Abfrage | „prüfen und Constraint behalten" lässt ein Fenster offen, und dann bricht der `INSERT` doch am Constraint – mit genau dem `500`, den die Prüfung verhindern sollte. Beim **Ändern** bleibt es bei der Vorabprüfung: ein `UPDATE` kennt kein `ON CONFLICT` |
| Geprüft wird der **Zeitpunkt**, nicht nur der Tag | ein Termin heute um 8 Uhr, angelegt um 20 Uhr, nähme nie eine Rückmeldung entgegen |
| Beim Ändern greift die Vergangenheitsprüfung nur, wenn Datum oder Uhrzeit sich **wirklich** ändern | sonst liesse sich der Ort eines vergangenen Termins nicht mehr berichtigen. Verschieben *in* die Vergangenheit bleibt gesperrt |
| **Entfernt wird nur ohne Verweise** (`409 TERMIN_IN_VERWENDUNG`), und die **Absage bleibt endgültig** | Fünf Tabellen hängen mit `ON DELETE CASCADE` am Termin, ein ungeprüftes Löschen räumte den halben Spieltag ab – und das Entfernen ist zugleich der einzige Weg zurück aus einer versehentlichen Absage, weil ein abgesagter Termin seinen Zeitpunkt weiter belegt |
| Die A18-Frist ist eine **Konstante im Dienst**, der Auftrag läuft alle fünf Minuten | ein weiteres Pflichtfeld wäre brechend; der Takt genügt, weil keine fachliche Regel an der Pünktlichkeit hängt – ob gemeldet werden darf, entscheidet die Uhrzeit, nicht der Status |
| **Das Adminprofil kann nicht zusagen** (`409 PROFIL_GESCHUETZT`) | der Rückmeldeendpunkt liegt ausserhalb von `/admin/`, das Adminprofil trägt aber eine `spielerId` – ohne die Prüfung stünde das technische Konto mit Skillwerten von 0 in der Teameinteilung |
| „Schwächster Auswechselspieler" nach dem **Skill-Snapshot des Laufs**, nicht nach dem aktuellen Profilstand | sonst wechselte der Auswechselspieler einer gespeicherten Einteilung rückwirkend, sobald ein Skillwert korrigiert wird |
| `TERMIN_GESCHLOSSEN` ist **allgemein** formuliert | der Code deckt drei Fälle ab: abgesagt, abgeschlossen, Beginn vorbei. Für den Aufrufer ist die Wirkung dieselbe; Genaueres steht in `detail` und darf sich ohne Vertragsänderung ändern |
| **Der manuelle Lauf (A24) wird nicht gespeichert** | `team_generierung.termin_id` ist `NOT NULL` und `team_zuteilung.teilnahme_id` hängt am Fremdschlüssel auf `spieltag.teilnahme`. Ein Phantom-Termin machte aus einer Adminrechnung einen Spieltag; eine Migration müsste `team_zuteilung` polymorph machen, und von da an kennt **jeder** Lesepfad zwei Formen. Die Historie ersetzt der Audit-Eintrag mit Teilnehmern und Seed |
| **A24 kostet kein Kontingent** | A15 zählt Läufe **je Termin und Teilnehmerstand**; beides gibt es nicht, und die Kontingentzeile trägt `termin_id NOT NULL`. Schutz sind der Zugang (admin-only) und `MAX_EXHAUSTIV` |
| **A24 bekommt einen eigenen Endpunkt unter `/admin/`**, nicht ein optionales `terminId` am bestehenden | der Ort ist die Autorisierungsentscheidung. Ein Endpunkt mit zwei Zugangsregeln je nach Körperinhalt wäre die Prüfung im Controller, die `AGENT_SERVER.md` verbietet |
| Im manuellen Lauf **begrenzt** `max_teilnehmer`, es schneidet nicht ab | am Termin ergibt sich die Menge und der Rest wartet; hier hat der Admin jeden Einzelnen benannt. Wer eine genannte Id still fallen lässt, liefert Teams, die niemand angefordert hat – und es fällt erst auf, wenn jemand vor Ort ohne Team dasteht |
| **`MAX_EXHAUSTIV = 24` heisst „24 ist erlaubt, 25 nicht"** | Die Anleitung lässt sich in beide Richtungen lesen. Maßgeblich ist die Begründung, und die nennt `C(24,12) ≈ 2,7 Mio.` ausdrücklich als noch tragbar |
| **Der Rückfall auf `HEURISTIK` liegt in `ExhaustivVerfahren`**, nicht im aufrufenden Dienst | Die Grenze ist die Regel von `EXHAUSTIV` selbst – es weiß als Einziges, wann es nicht mehr kann. Im Dienst müsste sie ein zweites Mal geführt werden, und jeder künftige Aufrufer müsste sie kennen |
| **Der Seed vergibt A und B, in *beiden* Verfahren** | Sonst stünde bei ungerader Zahl immer dieselbe Hälfte in Überzahl und damit Woche für Woche derselbe Spieler auf der Bank. Auf die Kosten wirkt der Tausch nicht – sie sind ein Betrag und damit symmetrisch |
| **`HEURISTIK` wertet den Tie-Break aus, aber nur beim Merken der besten Lösung** | Ohne ihn hätten die beiden Verfahren **nicht** dieselbe Zielfunktion, und genau das verlangt `AGENT_SERVER.md`. Die Annahme eines Tauschs richtet sich weiterhin nach den Primärkosten |
| **`HEURISTIK` setzt acht Mal neu an, und der Abkühlfaktor kommt aus der Schrittzahl** | Mit dem vorgeschlagenen einen Lauf und festem `0.9995` verfehlten **13 von 100 Seeds** das Optimum des Vergleichstests – die Temperatur ist nach einem Drittel der Schritte bei null, die Suche friert im ersten lokalen Minimum ein. Mit Neustarts: 0 von 300, bei gleichem Rechenbudget. **Wer die Konstanten anfasst, misst nach** – sie sind gemessen, nicht gesetzt |
| **Die wirksame Untergrenze der Aufstellung ist `max(min_teilnehmer, 2)`** | `ck_app_config_teilnehmer` verlangt nur `min_teilnehmer > 0`, und das DTO lässt `1` zu. Ohne die zweite Grenze käme eine Aufstellung mit einem Spieler durch und scheiterte erst im Generator mit einem `500` statt einer Meldung |
| **Die Namensvorgabe `Gast 1`, `Gast 2` steht im `AufstellungService`**, nicht im DTO | Einzige Ausnahme von „Vorgabewerte gehören an die API-Grenze", und sie hängt an der Dopplungsprüfung: Nennt der Admin einen Gast ausdrücklich „Gast 2" und lässt einen zweiten unbenannt, entsteht die Dopplung erst durch die Vorgabe – und sie soll abgelehnt werden |
| **Ein Gastname darf nicht mit dem Namen eines ausgewählten Profils zusammenfallen** | Zwei gleiche Namen in der Teamausgabe sind genau die Verwechslung, die der Gastname verhindern soll. Die Prüfung läuft **nach** dem Laden der Profile |
| **Der Schreibpfad liest den Termin nativ** (`TerminRepository#zustand`), nie über `findById` | Der Lauf merkt sich `teilnehmer_version` und prüft sie am Ende gegen. Eine geladene Entity lieferte den Zähler aus dem Persistence-Context – also genau den Wert, gegen den geprüft werden soll. **Ohne diese Umstellung fiele der Fall „nach einer Absage erneut generieren" durch** |
| **Der Termin-Lauf liest sein eigenes Ergebnis zurück** | Der Auswechselspieler wird nicht gespeichert, sondern beim Lesen erneut bestimmt. Weichen Lauf und Ableitung voneinander ab, fällt es sofort auf – sonst erst, wenn jemand den Termin das nächste Mal öffnet. Preis: zwei zusätzliche Leseabfragen je Lauf |
| **Die Bankwahl zieht mit einem frischen `new Random(seed)`** | Der Generator des Verfahrens hat bis dahin unterschiedlich viele Zahlen verbraucht; sein Zustand ist ohne Wiederholung des ganzen Laufs nicht rekonstruierbar, der Seed dagegen steht in der Tabelle. **Das ist die Bedingung, unter der die Ableitung beim Lesen dasselbe liefert wie der Lauf** |
| **Der Auswechselspieler wird nach dem *heute* eingestellten Modus abgeleitet** | Der Modus wird nirgends gespeichert, und A20b führt ihn als Anzeigeregel. **Folge:** Stellt der Admin um, kann ein bestehender Lauf einen anderen Auswechselspieler zeigen |
| **Die eigene Bilanz liest ein Endpunkt ohne Id im Pfad** (`GET /bilanz/lesen`) | Die Identität kommt aus der Sitzung. Mit einer Id wäre es „fremde Bilanz lesen", über das niemand entschieden hat. Ein `GAST` bekommt die leere Bilanz statt eines Fehlers – ununterscheidbar von einem Spieler ohne gewertete Termine, und das ist Absicht |
| **Die Bilanz ist ein geschachteltes Schema, nicht drei flache Zähler** | `SpielerDetails.bilanz` und `GET /bilanz/lesen` liefern dieselbe Form. Zwei Darstellungen derselben drei Zahlen liefen auseinander, sobald eine ein Feld bekommt |
| **`dto.spieltag.Ergebnis` und `domain.spieltag.Ergebnis` heissen gleich** | Der Vertrag führt das Schema als `Ergebnis`, die Entity heisst nach ihrer Tabelle. Sie treffen sich ausschliesslich in `Ergebnis#von` |
| **Audit-Löschfrist 30 statt 90 Tage**, ohne Obergrenze der Zeilenzahl | Speicherplatz auf dem Raspberry Pi. Ein Deckel auf die Zeilenzahl warf in einem Ansturm genau die Einträge weg, die ihn belegen. Preis: Eine Ergebniskorrektur ist nach 30 Tagen nicht mehr belegbar, ein manueller Lauf gar nicht mehr nachvollziehbar |
| **`TeilnahmeRepository`, `BilanzRepository` und `PushAboRepository` haben bewusst keine Entity** | Bei `teilnahme` wäre `@Version` sogar nachteilig: Optimistic Locking meldete den Wettlauf zweier Meldungen erst beim Schreiben, `ON CONFLICT` entscheidet ihn ohne Wiederholung. Bei der Bilanz bildet die `Spieler`-Entity dieselben Spalten bereits ab. Bei `push_abo` wird jede Zeile in *einer* Anweisung geändert, und der Versandlauf läuft ausserhalb jeder Transaktion |
| **`AufstellungRepository` bündelt drei Tabellen in einer Abfrage** | Eine Entity gäbe es für das Ergebnis ohnehin nicht; drei Einzelabfragen wären drei Gelegenheiten, gegen verschiedene Stände zu rechnen |
| **`IN (:spielerIds)` statt `= ANY(:spielerIds)`** | `JdbcClient` setzt eine Liste selbst in Platzhalter um; `= ANY` bräuchte ein `java.sql.Array`. Preis: Eine leere Liste ergäbe `IN ()` und damit einen Syntaxfehler – der Dienst ruft die Abfrage nur mit mindestens einer Id auf |

**Fünf kleinere Schnitte aus S5 und S6 stehen nur noch am Code**, weil sie keinen Vertrag
berühren und ihre Herleitung in `S5_ALGORITHMUS.md` und den Klassenkommentaren steht. Der Grund
ist bei allen fünf derselbe – *eine* Stelle statt zweier: `Teamaufteilung` trägt **Indizes**, nicht
Spieler · die Zuteilungen werden in Laufreihenfolge geschrieben und über `ORDER BY id` gelesen,
weil bei Gleichstand der Seed über die *Position* entscheidet · `AufstellungService` liefert
`Aufstellung` samt aktiven Kategorien statt einer Spielerliste · die Umrechnung Hundertstel →
`NUMERIC(6,2)` liegt in `Teamergebnis` (`domain`), damit kein DTO auf die Service-Schicht
zugreift · die Reihenfolge beim Sperren (erst `teilnehmer_version`, dann die Zusagen) liegt im
`TerminService`. **Wer einen davon umbaut, liest zuerst den Klassenkommentar** – jeder nennt den
Fall, der ohne ihn durchfällt.

**Die Weggabelungen je Meilenstein, jeweils mit Datum und Herleitung in der Anleitung:** sechs aus
S4 (30.08., `S4_UMSETZUNG.md` 0.4) · vier für S5 und vier zu A24 (31.08. und 05.09.,
`S5_UMSETZUNG.md` 0.4/0.6) · vier weitere A bis D (06.09.) · vier für S7 (13.09.,
`S7_UMSETZUNG.md` 0.5, **drei davon gegen die Empfehlung**) · fünf für S8 (14.09.,
`S8_PUSH_UMSETZUNG.md` 0.5, **durchgängig entlang der Empfehlung**).

**Die fünf Entscheidungen aus S8, weil sie die Form des Datenmodells festlegen:**

| # | Weggabelung | Entschieden |
|---|---|---|
| A | Audit für An- und Abmeldung eines Abonnements | **nein** – der Zustand steht in `push_abo`, die Endpoint-Adresse ist personenbezogen, und „Adminaktionen ja, Nutzerhandlungen nein" gilt weiter. `AuditAktion` wächst von 27 auf **29** |
| B | `push_erinnerung_am` beim Verschieben zurücksetzen | **ja**, bei echter Zeitänderung – dieselbe Bedingung wie `teams_fixiert`. **Folge: Die Spalte ist an der `Termin`-Entity gemappt**, anders als `halle_abgesagt_am` |
| C | Versand nach dem Commit | **synchron, aber parallel** mit Gesamtfrist (10 s), kein `@Async`. Der Listener fängt jede Ausnahme selbst ab; offene Aufrufe werden abgebrochen und als Fehlversuch gebucht |
| D | Probeversand prüft die Schalter | **nein** – nur VAPID und ein eigenes Abonnement. Die Oberfläche zeigt `anlageAktiv` daneben |
| E | Vorgabewert von `push_aktiv` | **`true`**, A25(e) folgend. **Die Analogie zum Hallenmodus (`false`) trägt nicht:** Die Hallenabsage geht an einen Aussenstehenden, der nie zugestimmt hat; eine Push-Nachricht erreicht nur, wer im Browserdialog zugestimmt hat |

**Fünf Stellen, an denen die Umsetzung von S8 von der Anleitung abweicht** – Entscheidungen, keine
Versehen; die Begründung steht jeweils am Code. Zwei weitere (`PushTyp.PROBE`, die
Transaktionsführung des Listeners) sind in `AGENT_SERVER.md` nachgezogen und stehen deshalb nicht
mehr hier.

| Abweichung | Grund in einem Satz |
|---|---|
| `PushVersender#versende` liefert ein `CompletableFuture`, nicht die Antwort selbst | Die Nebenläufigkeit gehört in den Adapter: Eine synchrone Methode bräuchte einen eigenen Thread-Pool, und der naheliegende gemeinsame `ForkJoinPool` hat auf einem Pi die Grösse der Kernzahl – dreissig blockierende Aufrufe liefen darin fast seriell. `HttpClient#sendAsync` bringt seinen Ausführer mit |
| `fubo.push.erinnerung-aktiv` wird als **Feld im Rumpf** geprüft, nicht als `@ConditionalOnProperty` | Die Annotation wirkt auf `@Bean`-Methoden und Klassen, **nicht** auf eine `@Scheduled`-Methode – sie stünde dort wirkungslos und ohne Fehlermeldung, derselbe stille Ausfall wie ein vergessenes `@EnableScheduling` |
| Die Empfängerabfragen liefern **Abonnements**, nicht erst Spieler-Ids | Eine Abfrage statt zweier: Der Fall „keine Empfänger" braucht keine Sonderbehandlung (eine leere Id-Liste ergäbe `IN ()`), die Bedingungen stehen an *einer* Stelle, und die Zahl der Personen bleibt ableitbar |
| Neu: `utils/P256` | Web Push liest an drei Stellen P-256-Schlüssel – VAPID-Paar, `p256dh` eines Abonnements, ephemeres Paar je Nachricht. Dreimal derselbe Handgriff wäre dreimal dieselbe Gelegenheit, das Format falsch zu lesen, **und dieser Fehler bleibt stumm** |
| Die Gerätebezeichnung ist eine **Heuristik mit Markertabelle**, nicht der gekürzte `User-Agent` | Die ersten achtzig Zeichen eines üblichen Kopfes zeigen weder Browser noch Plattform. „Chrome auf Mac" leistet, wofür das Feld da ist; ein Fehlgriff kostet nichts, deshalb der Rohtext als Rückfall |

**Zwei Nachträge aus S8, die beim Weiterbauen zählen:**

- **`erinnerungsAuftrag()` und `erinnerungVersenden()` sind zwei Methoden mit je einer Aufgabe.**
  Die erste trägt `@Scheduled` und den Schalter und entscheidet, *ob* gelaufen wird; die zweite
  enthält die Arbeit. **Der Grund ist der Test:** Stünde die Schalterprüfung in der Arbeitsmethode,
  käme kein Testfall an ihr vorbei – im Testprofil steht der Schalter auf `false`, und zwar zu
  Recht. Ihn fürs Testprofil einzuschalten liesse den **Takt** mitlaufen, und ob er während eines
  Laufs feuert, hinge daran, ob der Lauf eine Fünfminutengrenze kreuzt.
- **`V014` lässt den Primärschlüssel unbenannt** (`id BIGSERIAL PRIMARY KEY`), obwohl
  `AGENT_SERVER.md` unter den Flyway-Konventionen `pk_` aufführt. Kein `CONSTRAINT pk_` steht in
  `V001` bis `V013`; ein einzelnes in `V014` wäre der Ausreisser. **Wer das anders will, ändert die
  Regel und nicht eine Migration** – angewandte Migrationen sind unveränderlich.

**Abweichungen aus S1, die im Datenmodell sichtbar sind:** `min_teilnehmer = 6`,
`anz_team_generator = 1`, `session_maximal_stunden = 1` (statt 8/2/8); `session.stage` heisst in
der zweiten Stufe `PROFILE_AUTHENTICATED`, weil auch Gäste sie erreichen.
`spieltag.termin.fk_termin_serie` hat bewusst kein `ON DELETE`.

### 6.3 Fallstricke, die weiter gelten

Jeder Punkt hat schon mindestens einmal Zeit gekostet.

**Konfiguration und Start**

- **Ein `${...}` in einer Fehlermeldung bedeutet immer fehlende Auflösung, nie einen falschen
  Wert.** Spring Boots `Binder` reicht unauflösbare Platzhalter wörtlich durch – anders als
  `@Value`. Deshalb prüft `PushConfig` die VAPID-Werte zusätzlich auf ein enthaltenes `${`;
  ohne diese Prüfung liefe die Anwendung mit dem Platzhalter als Schlüssel weiter. `--env-file`
  gilt nur für Docker Compose, nicht für die JVM; deshalb `spring.config.import`.
- **Die `.env` ist eine Properties-Datei mit drei Lesern und drei Parsern.** Anführungszeichen
  landen im Wert (kostete einen `550` beim Mailversand); ein `source .env` scheitert am `<` von
  `SMTP_ABSENDER`. Regel in `AGENT_SERVER.md`.
- **`target/classes` vergisst nichts.** Nach dem Umbenennen oder Löschen einer Ressource und
  nach jeder Änderung an `application.yml`: `./mvnw clean`.
- **Spring Boot 4 bringt Jackson 3, und das Wurzelpaket heisst `tools.jackson`** – nicht
  `com.fasterxml.jackson`. Betroffen sind `tools.jackson.databind.ObjectMapper` und
  `tools.jackson.core.type.TypeReference`; **nur die Annotationen**
  (`com.fasterxml.jackson.annotation`) sind geblieben. Ein Import aus der Jackson-2-Welt scheitert
  mit „Package `com.fasterxml.jackson.core` ist nicht vorhanden" – **und das ist der laute Teil.**
  - **Der stille Teil: Jackson 3 wirft ungeprüft.** `JsonProcessingException` gibt es nicht mehr,
    die Wurzel ist `tools.jackson.core.JacksonException` und erbt von `RuntimeException`. Ein
    `try`/`catch` um `writeValueAsString` ist deshalb **nicht mehr erzwungen**. Wer einen Fehler
    dort abfangen *will*, muss es von sich aus tun und daran denken, dass der Übersetzer nicht
    mehr erinnert.
  - **Kostete am 15.09.2026 einen Übersetzungslauf** in `WebPushVersender`. Vier Stellen im
    Bestand machten es längst richtig (`AuthorizationExceptionHandler`, `SpielerRepositoryImpl`,
    `AufstellungRepository`, `TeamGenerierungRepository`); **ein Blick auf eine davon hätte
    gereicht.**
  - **Merkregel für jedes neue Fremdpaket:** Erst nachsehen, ob der Bestand es schon benutzt, und
    wenn ja, wie. `grep -rn "<paket>" src/main` ist billiger als ein Übersetzungslauf.

**Flyway und JPA**

- **Flyway überspringt falsch benannte Migrationen stillschweigend** (doppelter Unterstrich).
  `validate-migration-naming: true` bleibt gesetzt.
- **Beispielcode gehört nicht in Migrationen.** Ein `:name` aus einer Anleitung ist für
  PostgreSQL ein Syntaxfehler (`42601`).
- **`ddl-auto=validate` prüft Spaltenexistenz und JDBC-Typcode, nicht die Zuordnung.**
  `CHAR(n)` braucht `@JdbcTypeCode(SqlTypes.CHAR)`; vertauschte gleichartige Spalten fallen nicht
  auf. Mapping-Fehler äussern sich als Kaskade von `UnsatisfiedDependencyException` – **nur die
  erste Logzeile benennt die Ursache.**
- **Tabellennamen aus den `CREATE TABLE`-Zeilen lesen, nie aus einem Constraint-Namen.**
  `fk_terminserie_spieler` gehört zu `spieltag.terminserie`, `fk_kontingent_spieler` zu
  `spieltag.generierung_kontingent`.
- **Wo JPA schreibt und natives SQL liest, muss geflusht werden** (`saveAndFlush`, nicht `save`).
  **Diese Regel stand hier schon, und S6 ist trotzdem hineingelaufen** – als Einzeiler ohne
  Fehlerbild schützt sie niemanden. Das Bild: `ErgebnisService#korrigieren` ändert das Ergebnis
  über die Entity, die Bilanzrechnung liest `spieltag.ergebnis` danach **per `JdbcClient`**. Ohne
  Flush rechnet sie gegen den *alten* Ausgang – und **die Zahlen bleiben dabei plausibel**, nur
  eben falsch. Zweiter Gewinn des Flushs: Der Sperrkonflikt fällt an der Aufrufstelle an statt als
  `UnexpectedRollbackException` beim Commit.
- **Die Bilanz-Neuberechnung hat zwei Ebenen, und beide sind nötig:** Die *innere* Unterabfrage
  grenzt auf die Beteiligten **dieses einen** Termins ein, die *äussere* zählt für sie über
  **alle** ihre Termine. Wer nur über den einen zählt, schreibt jedem Spieler die Bilanz dieses
  Spiels – und löscht seine Historie. Dazu `version = version + 1`, sonst schreibt eine
  gleichzeitig geladene `Spieler`-Entity die alte Bilanz still zurück. **Beides fällt nur einem
  einzigen Testfall auf**: zwei Termine, dann eine Korrektur am ersten.
- **`@Modifying` mit `clearAutomatically` löst Entities vom Persistence-Context.** Was danach
  gebraucht wird (Id, Name), vorher in lokale Variablen holen.
- **Ein natives `UPDATE` auf eine Versionsspalte verträgt sich nicht mit einer im selben Vorgang
  geladenen Entity.** `TeilnahmeService` liest den Termin deshalb nativ, nicht über `findById`.
  **Umgekehrt gilt dasselbe:** `termin.push_erinnerung_am` ist gemappt, weil `TerminService`
  es beim Verschieben über die Entity zurücksetzt – der Erinnerungsauftrag markiert dagegen
  nativ und lädt dabei keine Entity.

**Transaktionen an der Commit-Grenze (S7, S8)**

- **`@TransactionalEventListener(AFTER_COMMIT)` läuft *innerhalb* des Commits**, mit noch
  gebundener Transaktionssynchronisation. Ein `@Transactional` in Voreinstellung (`REQUIRED`)
  schliesst sich dort der **bereits festgeschriebenen** Transaktion an: Die Schreibanweisung läuft
  durch, meldet keinen Fehler – **und steht hinterher nicht in der Tabelle.** Deshalb trägt der
  Absagezuhörer `Propagation.NOT_SUPPORTED`; das setzt die abgeschlossene Transaktion aus, und
  jedes `REQUIRED` darunter öffnet eine frische. **`REQUIRES_NEW` wäre auch falsch** – es hielte
  eine Transaktion über die HTTP-Aufrufe hinweg offen.
- **Kein HTTP innerhalb einer offenen Transaktion.** Der Versandlauf arbeitet in drei Phasen
  (Empfänger lesen, alle Aufrufe anstossen, Ergebnisse je Abonnement buchen) und trägt **nirgends**
  ein `@Transactional`: Jede `JdbcClient`-Anweisung ist ihre eigene Transaktion. Eine offene
  Transaktion während der Frist von 10 s blockierte eine Datenbankverbindung je Lauf.
- **Der Zuhörer fängt jede `RuntimeException` selbst ab.** Der Commit ist durch, die Absage steht,
  und nichts am Versand darf die Antwort an den Admin verändern. Die Nachricht ist dann verloren –
  der hinnehmbare Ausgang; ein `500` für eine gespeicherte Absage wäre es nicht.
- **Ein `401` vom Push-Dienst deaktiviert nichts und zählt nichts.** Er sagt etwas über **unseren**
  Schlüssel, nicht über das Abonnement, und trifft jedes gleichzeitig. Würde er wie ein
  Fehlversuch gebucht, löschte ein falsch gesetzter Schlüssel binnen weniger Läufe den gesamten
  Bestand – und die Wiederherstellung verlangte von jedem Spieler einen neuen Browserdialog.

**Tests**

- **Alles, was den Kontextstart überlebt, überlebt auch die Test-Transaktion.** Betrifft den
  `ApplicationRunner` des Bootstraps (deshalb stehen `ADMIN_*` und `fubo.mail.*` in
  `src/test/resources/application.yml`), den `BruteForceService` und den `ProfilStammdatenCache` –
  alle drei werden in `@BeforeEach` zurückgesetzt. **Und die `@Scheduled`-Aufträge:** deshalb steht
  `fubo.push.erinnerung-aktiv` im Testprofil auf `false`.
- **`REQUIRES_NEW` und `@Transactional` am Test vertragen sich nicht.** Die eigene Transaktion
  sieht die Testdaten unter READ COMMITTED nicht.
- **Eine Änderung an `FuboProperties` bricht drei Testklassen**, die den Record von Hand bauen
  (`SessionAuthFilterTests`, `SessionCookieFactoryTests`, `BruteForceServiceTests`).
- **`sitzungsIdZu(token)` nur mit noch gültigem Token aufrufen** – jeder Stufenwechsel rotiert ihn.
- **`SMALLINT` kommt über `queryForMap` als `Integer` zurück**, über
  `queryForObject(..., Short.class)` als `Short`. Nicht miteinander vergleichbar.
- **Antworten über Jackson auswerten, nicht mit `contains` auf dem rohen JSON.**
- **`uq_termin_zeit UNIQUE (datum, uhrzeit)` ist global und trifft auch die Tests.** Jede Klasse
  braucht ihren eigenen Zeitstreifen in **beiden** Achsen. Vergeben: `TerminControllerTests`
  40 Tage/18:15, `TerminVerwaltungControllerTests` 120/19:45, `TeilnehmerlisteTests` 200/17:30,
  `SpielerControllerTests` 300/16:05, `TeamGeneratorTests` 500/20:15, `ErgebnisControllerTests`
  600 **rückwärts**/21:30, `HallenmodusTests` 700 **vorwärts**/19:00, seit S8 `PushVersandTests`
  **20:45** (Tage gemischt: morgen für die Erinnerung, 800 vorwärts für alles andere – **die
  Uhrzeit trägt dort allein**). **`ManuelleGenerierungTests` und `PushControllerTests` brauchen
  keinen** – sobald eine der beiden einen Streifen braucht, hat sich eine Terminabhängigkeit
  eingeschlichen. Das ist die schnellste Gegenprobe, die es dafür gibt.
- **Ein Erinnerungstermin darf nicht „zwei Stunden in der Zukunft" liegen.** Das Fenster ist
  `beginn > jetzt AND beginn <= jetzt + Vorlauf`; ein Termin zwei Stunden voraus hängt an der
  Tageszeit des Laufs und rutscht nach 22 Uhr über Mitternacht in die Vergangenheit.
  **Zuverlässig ist es umgekehrt:** den Vorlauf auf 48 Stunden setzen und den Termin auf **morgen**
  legen – morgen um 20:45 liegt von jeder Tageszeit aus zwischen 21 und 45 Stunden entfernt.
- **`PushVersandTests` rechnet mit `LocalDate.now()`, der Dienst mit der `Clock`-Bean in
  `Europe/Berlin`.** Auf einem Rechner in dieser Zone ist das dasselbe; in einem CI-Container mit
  UTC können die beiden „morgen" um einen Tag auseinanderliegen. **Wer die Klasse anfasst, stellt
  auf `LocalDate.now(uhr)` um** – die `Clock`-Bean ist injizierbar.
- **Eine Testklasse ohne `@Transactional` sieht die geplanten Aufträge.** Ohne Test-Transaktion
  sind angelegte Termine wirklich geschrieben, und der A18-Auftrag setzt geplante Termine nach
  Beginn auf `ABGESCHLOSSEN` – alle fünf Minuten. **Ein vergangener Termin, der `GEPLANT` bleiben
  soll, wird zur Zeitbombe.** `HallenmodusTests` legt ihn deshalb als `ABGESAGT` an;
  `PushVersandTests` legt seine Termine weit in die Zukunft.
- **Ein Rollback lässt sich in einer `@Transactional`-Testklasse nicht prüfen.** Die
  `@Transactional`-Methode des Dienstes nimmt an der Test-Transaktion teil; beim Scheitern
  markiert sie diese nur als „rollback-only", die Änderung steht aber weiterhin in der Zeile. Ein
  Fall wie „der Versand scheitert, und es bleibt kein Zustand zurück" prüft dort das Gegenteil
  dessen, was er soll – **und wäre grün.** Dasselbe gilt für jede Zusicherung auf einen Eintrag,
  den ein `AFTER_COMMIT`-Zuhörer schreibt. Deshalb tragen `HallenmodusTests` und
  `PushVersandTests` kein `@Transactional` und räumen von Hand auf.
- **Ein eigener `@Import`-Satz oder eigene `properties` kosten einen zweiten Anwendungskontext**
  und damit einen zweiten Datenbankcontainer. Drei Klassen haben einen
  (`PasswortResetControllerTests`, `HallenmodusTests`, `PushVersandTests`); bei der letzten ist es
  unvermeidbar, weil sie eingerichtete VAPID-Schlüssel braucht, während das globale Testprofil sie
  leer lässt – genau dieser Zustand ist der `503`-Fall von `PushControllerTests`.
- **Termine für Lesetests entstehen per SQL, nicht über den Adminendpunkt.** Der Lesepfad soll
  unabhängig vom Schreibpfad prüfbar bleiben – und ein Termin in der Vergangenheit lässt sich über
  den Endpunkt gar nicht anlegen.
- **`now()` ist innerhalb einer Transaktion konstant.** Alle über Endpunkte angelegten Zusagen
  eines Testfalls tragen denselben Zeitstempel; die Reihenfolge fällt dann auf die `id` zurück.
  Meldezeiten deshalb per SQL setzen.
- **Wer `configs.app_config` per SQL ändert, muss es vor dem ersten HTTP-Aufruf tun.** Jeder Aufruf
  lädt über den Sitzungsfilter die Konfigurationszeile in den Persistence-Context; eine spätere
  Änderung bliebe für denselben Vorgang unsichtbar. **Der Test wäre grün und prüfte nichts.**
  Reihenfolge: Konfiguration setzen, Daten anlegen, genau einmal lesen.
- **`ck_app_config_teilnehmer` verlangt `max >= min`.** „Mindestzahl nur durch Wartende erreicht"
  braucht deshalb `min = max` und mehr Zusagen als beide.
- **Zeitgrenzen mit Abstand prüfen, nicht am Rand.** `fubo.zeitzone` steht ausdrücklich auch in
  `src/test/resources/application.yml`.
- **Eine Fallunterscheidung, deren einer Zweig unerreichbar ist, prüft nichts** (06.09.2026,
  kostete einen Lauf). `TerminService#aendern` setzte `teams_fixiert` mit `!beginn.isBefore(jetzt)`
  – für jeden künftigen Zeitpunkt wahr, also gesetzt statt gelöscht. Dass `pruefeNichtVergangen`
  oben schon jeden anderen Zeitpunkt ablehnt, machte den zweiten Zweig unerreichbar **und den
  Fehler unsichtbar**; der Kommentar daneben verteidigte die Bedingung sogar ausdrücklich.
  **Erkennungsmerkmal:** Wer eine Bedingung mit „steht hier ausdrücklich, obwohl sie nie anders
  ausgeht" begründet, hat eine geschrieben, die niemand liest – und niemand prüft.
- **Ein Testdaten-Helfer, der auf `aktiv` filtert, ändert sein Ergebnis, sobald der Fall etwas
  sperrt.** Die Auswahl muss **vor** der Sperre entstehen. Sonst prüft der Fall, dass sechs aktive
  Profile durchgehen – und ist grün, ohne den Ablehnungspfad je berührt zu haben.
- **Ein rückwärts zählender Testdaten-Parameter dreht die Erwartung** (31.08.2026, kostete einen
  Lauf): `zusageAnlegen(…, vorMinuten)` setzt `gemeldet_am = now() - vorMinuten`, die **grössere**
  Zahl meldet sich also **früher**. Jede solche Zahl trägt am Aufruf einen Kommentar mit der
  erwarteten *Position*. **Erkennungsmerkmal, wenn mehrere Fälle mit gespiegelter Reihenfolge
  fallen:** Ist der Fall, der die Sortierung unmittelbar prüft, grün, liegt der Fehler im Test.
- **Ein Testdoppel ohne `@Primary` bringt den Kontext gar nicht hoch, eines ohne `@Import`
  gar nichts.** `MailErsatzConfig` und `PushVersenderErsatzConfig` sind ausdrücklich zu
  importieren; **wer den Import vergisst, bekommt keinen Fehler** – die Anwendung nimmt dann den
  echten Versender und versucht, eine Nachricht zu verschicken. Beim Push endet das nicht in einer
  roten Zusicherung, sondern in einem Fehlversuch nach Ablauf der Frist.

**Sicherheit und Betrieb**

- **`Using generated security password` ist kein Indikator** – weder dafür noch dagegen, dass die
  Filterchain greift. Am Verhalten prüfen: ohne Cookie `401` mit `application/problem+json`,
  `/actuator/health` ohne Cookie `200`.
- **Der Brute-Force-Zähler ist zwischen PIN- und Admin-Login geteilt.** Fünf Vertipper beim
  Adminpasswort sperren auch den PIN-Login; die Meldung lautet dann `PIN_GESPERRT`.
- **`max-versuche-ip` und `fubo.reset.max-versuche` stehen beide auf 5**, deshalb greift die
  IP-Sperre vor dem Vorgangszähler. Das ist die gewünschte Staffelung.
- **Der `sub`-Anspruch des VAPID-JWT braucht ein Schema.** Eine nackte Adresse ohne `mailto:`
  lehnt Apple mit `BadJwtToken` ab – **und der Fehler fällt nur auf einem iPhone auf.**
  `PushConfig` prüft es beim Start.
- **Beim Auslesen des privaten VAPID-Schlüssels aus der DER-Struktur ist `tail -c 32` der teuerste
  Tippfehler.** Die Struktur endet mit dem **öffentlichen** Punkt; die letzten 32 Byte sind dessen
  Y-Koordinate – 43 Zeichen lang, sieht richtig aus, ist öffentlich bekannt. Der Skalar steht am
  **Anfang** (`tail -c +8 | head -c 32`). Eine Längenprüfung fängt das nicht ab; die Startprüfung
  auf „passt das Paar zusammen" schon.
- **Git über die Ordnerfreigabe hinterlässt Sperrdateien.** Nach jedem schreibenden Befehl
  `find .git \( -name 'tmp_obj_*' -o -name '*.lock' \) -delete`, sonst blockiert `HEAD.lock` den
  nächsten Commit. **Ohne Löschrecht auf dem Ordner geht das nicht** – der Ausweg ist die
  Löschfreigabe für den Projektordner; sie gilt je Sitzung. **Die Git-Identität ist nicht global
  gesetzt**: `git -c user.name=... -c user.email=... commit` verwenden.

**Bruno**

- **Ein `pre-request`-Skript kann den sichtbaren Körper überschreiben.** Bei „Konfiguration
  aendern" tat es das: Ein oben eingetipptes `halleEmail` erreichte den Server nie und kam beim
  Lesen als `null` zurück – das sah nach einem Fehler der Anwendung aus. **Kommt ein Feld
  unverändert zurück, zuerst das Skript lesen.** Seit dem 30.08.2026 gewinnt der Körper.
- **Der Cookie-Speicher gilt je Host.** Vier Gastanmeldungen gegen `localhost` überschreiben
  einander; die übrigen Sitzungstoken sind danach unerreichbar und wegen des gespeicherten
  SHA-256 nicht zu rekonstruieren. Dieselbe Falle in einer curl-Schleife mit `-c` (Jar
  *schreiben*) statt `-b` (Jar *senden*). Ausweg: `/admin/gast/freigeben`.
- **Bei jeder Vertragsänderung nicht nur den neuen Ordner anlegen, sondern die Feldzahlen und
  Meilensteinnummern der bestehenden Requests gegenlesen.** Am 25.09.2026 nannte „Konfiguration
  lesen" noch elf änderbare Felder (es sind 14 – `hallenModusAktiv` aus S7 war nie nachgetragen),
  ihr Skript liess das nur lesbare `halleAbsageVorlageEffektiv` in den Änderungs-Körper laufen, und
  der Ordner `system` nannte das Deployment noch „S8".

### 6.4 Verifikation

```bash
docker info > /dev/null                                    # muss durchlaufen
docker compose -f compose.dev.yml --env-file .env up -d
./mvnw clean verify
```

**Zuletzt grün am 15.09.2026 – 483 Fälle in 34 Klassen** (S8 vollständig, 0 Fehlschläge, 0 Fehler,
nichts übersprungen). Verlauf: 148/16 (22.08.), 184 (23.08.), 244/22 (29.08.), 300/25 und 331/26
(S4, 30./31.08.), 385/29 (S5, 06.09.), 411/30 (S6, 12.09.), 431/31 und 434/31 (S7 samt Nachtrag,
13.09.), **483/34 (S8, 15.09.)**.

**S8 brachte 49 Fälle in drei neuen Klassen** – `PushVerschluesselungTests` (16),
`PushControllerTests` (15), `PushVersandTests` (17) –, dazu einen in
`KonfigurationControllerTests` (die beiden Push-Felder sind Pflicht) und zwei Pfadmuster als
Zusicherungen *innerhalb* der Bündelfälle von `SecurityConfigTests`, ohne neue Methode.
`ConfigServiceTests` war mit den Paketen 1 bis 4 schon abgedeckt.

**Beide Zahlen immer gleich ermitteln, nach dem Lauf aus den Berichten** – die Klassenzahl war
einmal falsch, weil sie fortgeschrieben statt gezählt wurde:

```bash
awk -F'[:,]' '/^Tests run:/ {t+=$2; k++} END {print k" Klassen, "t" Faelle"}' \
    target/surefire-reports/*.txt
```

**Der vorab gezählte Erwartungswert traf jedes Mal exakt** (`grep -c '^\s*@Test\s*$'` je Klasse) –
die *geschätzten* Zahlen dagegen nie: 377 statt 385 in S5, und für S8 nannte die Anleitung 471
statt der tatsächlichen 483. **Deshalb wird unmittelbar vor dem Lauf gezählt, nicht
fortgeschrieben.**

**Fünf Klassen laufen ohne Spring-Kontext** – `SessionAuthFilterTests`,
`SessionCookieFactoryTests`, `BruteForceServiceTests`, `TeamverfahrenTests` und seit S8
`PushVerschluesselungTests`. Sie brauchen zusammen unter einer Sekunde und machen die Gegenprobe
belastbar: **Sind alle fünf grün und der Rest rot, liegt ein Kontextfehler vor und kein
Anwendungsfehler.**

**`SecurityConfigTests` bleibt bei 26 Fällen**, obwohl mit jedem Meilenstein Pfade dazukommen: Sie
stehen als Zusicherungen *innerhalb* der bestehenden Bündelfälle. **Wer die Fallzahl als Mass für
die Abdeckung liest, unterschätzt diese Klasse systematisch.** Namentlich eingetragen sind
inzwischen fünf Pfade, die sich nur im `/admin/`-Präfix unterscheiden.

**Scheitert ein Lauf, zuerst die Surefire-Berichte lesen, nicht die Maven-Zusammenfassung.** Bei
einem Kontextfehler meldet Spring Test jeden betroffenen Fall einzeln, aber nur der *erste* Bericht
je Kontextkonfiguration nennt die Ursache – alle anderen tragen
`ApplicationContext failure threshold (1) exceeded`. 115 Fehler bedeuten dann **eine** Ursache.
Kürzester Weg: `grep -h 'Caused by' target/surefire-reports/*.txt | tail -1`.

**Was der Testlauf nicht abdecken kann, decken die manuellen Prüflisten ab** – jeder Fall läuft in
einer zurückgerollten Transaktion und kann keine Sitzung wirklich ablaufen lassen, keine Mail
zustellen und kein Gerät erreichen. Die Listen stehen in `S2b_UMSETZUNG.md` (12.1),
`S3_UMSETZUNG.md` (10.1), `S4_UMSETZUNG.md` (11.1), `S5_UMSETZUNG.md` (13.1), `S6_UMSETZUNG.md`
(8.1), `S7_UMSETZUNG.md` (8.1) und `S8_PUSH_UMSETZUNG.md` (13.1); die zu S2b und S3 sind
abgearbeitet. Sie bleiben stehen – nicht als offene Aufgabe, sondern als Vorlage nach jeder
Änderung am jeweiligen Bereich.

Drei Punkte zur Gastverwaltung stehen in keiner Anleitung, die Bruno-Requests unter `admin/gast/`:

| Prüfpunkt | Erwartung |
|---|---|
| Als Gast anmelden, `sessionLeerlaufMinuten` auf 1, warten, `/admin/gast/lesen` | `belegt: true` mit `sitzungGueltig: false` – der Zustand, der den nächsten Gast aussperrt |
| Zweiten Gast über `baseUrlOhneCookie` anmelden, Platz freigeben, mit seinem Cookie `/auth/session/lesen` | `401`, nicht `200` – die Freigabe widerruft die Sitzung |
| `anzGuests` von 4 auf 2 senken bei vier belegten Plätzen | Plätze 3 und 4 mit `wirksam: false` und weiter `belegt: true`; gelöscht wird nichts |

**Die Bruno-Collection ist die zweite Hälfte der Verifikation.** Sie liegt unversioniert in
`~/Documents/bruno/fubo_server` und deckt alle 44 Endpunkte in acht Ordnern ab (`auth`, `admin`,
`termine`, `teams`, `ergebnis`, `halle`, `push`, `system`). Stand 25.09.2026; Format, Konventionen
und die Prüfschritte beim Nachziehen stehen in ihrer `README.md`.

## 7. Nächste Schritte

1. **Die fünf Handprüflisten in einem Zug abarbeiten** – `S4_UMSETZUNG.md` 11.1,
   `S5_UMSETZUNG.md` 13.1, `S6_UMSETZUNG.md` 8.1, `S7_UMSETZUNG.md` 8.1,
   `S8_PUSH_UMSETZUNG.md` 13.1, dazu die drei Punkte aus 6.4. Die wertvollsten Punkte:
   - **S7: Hier gehen echte Mails raus.** Vorher `halle_email` auf eine eigene Adresse setzen. Die
     beiden nicht automatisierbaren Punkte sind die **Mail im Posteingang** (Umlaute, Datenblock,
     Betreff) und die **Sommer-/Winterzeitgrenze** – die Stelle, die am ehesten **still** falsch
     ist.
   - **S8: Hier geht wirklich eine Nachricht auf ein Gerät.** Das ist der einzige Beleg dafür, dass
     der Browser die Nutzlast **öffnen** kann – der Push-Dienst nimmt auch eine falsch
     verschlüsselte Nachricht mit `201` an. Ohne Client-Track braucht es dafür ein Abonnement aus
     einem echten Browser; die Beispieladresse der Bruno-Collection zeigt bewusst auf
     `push.example.invalid` und ist unzustellbar.
   - **A24:** Fünfmal derselbe Aufruf muss fünfmal `200` liefern, und `spieltag.team_generierung`
     muss danach **unverändert** sein.
   - **S6:** Die SQL-Gegenprobe, die die Bilanz gegen die Ergebnisse zählt – sie steht wiederholbar
     im Bruno-Ordnerkommentar `ergebnis/`, **mit `LEFT JOIN`**: Ohne ihn findet sie genau den
     Fehler nicht, bei dem eine Bilanz stehen bleibt, obwohl sie auf null gehörte.
2. **Client-Track informieren.** Der Vertrag steht bei 44 Endpunkten; die Push-Zeilen in 4.1 sind
   **verbindlich**. Weiter gilt: **`TERMIN_NICHT_ABGESAGT` gibt es nicht**, und wer vor dem
   12.09.2026 generiert hat, generiert wegen der vier `nullable`-Korrekturen neu.
3. **S9: Härtung und Deployment** (14 h), Entwurf in `harness/tmp/S9_DEPLOYMENT.md`. Fünf Punkte
   aus S5 bis S8 gehören dort hinein:
   - die **Messung von `MAX_EXHAUSTIV` auf der Zielhardware**,
   - die Frage, ob 30 Tage Audit-Aufbewahrung für den Speicher des Pi reichen,
   - **ein Satz zum Absender in der Betriebsdokumentation** – mit S7 erscheint `SMTP_ABSENDER` zum
     ersten Mal ausserhalb des Projekts, nämlich beim Hallenbetreiber,
   - die **Sicherung des VAPID-Schlüsselpaars**: Geht es verloren, muss jeder Spieler erneut
     zustimmen,
   - **`TZ=Europe/Berlin` im Compose-Dienst** und ausgehendes HTTPS zu den Push-Diensten – der
     Entwurf kennt beides noch nicht.

**Offene Punkte, die keine Aufgabe für heute sind:**

- **`MAX_EXHAUSTIV = 24` ist gesetzt, nicht gemessen.** `C(24,12) ≈ 2,7 Mio.` ist auf einem
  Entwicklungsrechner tragbar; ob auch auf dem Raspberry Pi 5, zeigt erst eine Messung auf der
  Zielhardware – sie gehört zu S9. **Mit A24 ist die Grenze zugleich der einzige Schutz vor
  Dauerläufen**, weil der manuelle Lauf kein Kontingent kostet.
- **Alte Generierungsläufe werden nie aufgeräumt.** Belanglos, solange Termine bestehen;
  `ON DELETE CASCADE` nimmt sie mit dem Termin.
- **Der manuelle Lauf hinterlässt nur den Audit-Eintrag**, und der wird nach 30 Tagen gelöscht.
  Bewusst so entschieden; wird es zum Problem, ist die Antwort die Migration aus
  `S5_UMSETZUNG.md` 0.6 – **nicht** eine längere Löschfrist, denn die Frist gilt dem Personenbezug
  und nicht der Nachvollziehbarkeit von Rechnungen.
- **`configs.app_config.anz_guests` gilt im manuellen Lauf nicht.** Der Wert begrenzt gleichzeitige
  Gastsitzungen, nicht Mitspieler auf dem Platz; die Summe begrenzt `max_teilnehmer`.
- **Ein Wechsel von `auswechsel_modus` ändert den angezeigten Auswechselspieler bestehender
  Läufe.** Die Einteilung bleibt unberührt (A20b führt den Modus als Anzeigeregel).
- **Die Zeitzone der Datenbanksitzung ist nicht gesetzt.** Ohne Folge, solange alle Zeitvergleiche
  über die `Clock`-Bean laufen und jeden Zeitpunkt als Parameter übergeben – S5, S7 und S8 halten
  das durchgängig ein. Sobald eine Abfrage `current_date` oder `current_time` benutzt, gehört
  `TimeZone` in die Datenbankkonfiguration.
- **Ein Wechsel des VAPID-Paares entwertet alle bestehenden Abonnements.** Die Zeilen bleiben
  stehen und der Push-Dienst antwortet `401`; deaktiviert wird dabei nichts – zu Recht, der Fehler
  liegt beim Server. **Heilung ist nur ein neuer Browserdialog je Gerät.** Deshalb gehört das
  Schlüsselpaar in die Sicherung.
- **Betriebsaufgabe ohne Code:** Custom Domain `app.<domain>` in Cloudflare Pages einrichten. Ohne
  sie funktioniert die Anmeldung produktiv nicht – `pages.dev` steht auf der Public Suffix List und
  wäre gegenüber `api.<domain>` cross-site, mit `SameSite=None; Secure`, zwingendem CSRF-Schutz und
  einem Cookie, das Safari und der Chrome-Inkognito-Modus blockieren. Ebenfalls offen:
  Pages-Preview-Deployments, in denen der Login bauartbedingt nicht funktioniert.

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
- **`.env` nie einchecken.** Dokumentation in deutscher Sprache, **keine realen Personennamen** in
  Code, Testdaten oder Dokumentation – besonders nicht in Migrationen, die unveränderlich sind und
  dauerhaft in der Git-Historie stehen. **Das VAPID-Schlüsselpaar gehört ebenfalls nie in eine
  Datei des Repositories**, auch nicht als Beispiel; die Testvektoren aus RFC 8291, Anhang A sind
  die Ausnahme, weil sie veröffentlicht sind und zu keinem Server gehören.
- **Nach Abschluss eines Arbeitspakets:** verifizieren, diesen Handoff fortschreiben, die
  Vorfassung unter `harness/archive/` ablegen, `/PRJ_FuBo/harness/CONTEXT_HANDOFF.md` nachziehen
  und die Bruno-Collection um die neuen Endpunkte ergänzen. Verbindliche Regeln aus der Umsetzung
  gehören in `AGENT_SERVER.md` – **nicht** zusätzlich hierher, sonst laufen beide auseinander.
