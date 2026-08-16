# FuBo-Server

Backend der FuBo-Anwendung (Team-Balancer für Fussballspieltage).
Das zugehörige Frontend liegt in einem eigenen Repository: `FuBo-Client`.

- Entwickler: Denis Kim
- Techstack: Java 25, Spring Boot 4.1, PostgreSQL 17, Maven
- KI-gestützte Entwicklung mit Claude

### Start der Umgebung mit Testdaten
```
cd ~/Projects/PRJ_FuBo/server
// .env anpassen/ergänzen: DB_USER=..., DB_PASSWORD=...
// Docker starten
docker compose -f compose.dev.yml down -v
docker compose -f compose.dev.yml --env-file .env up -d

./mvnw spring-boot:run   // Flyway legt V001-V007 neu an
./scripts/seed-lokal.sh scripts/data/spielerprofile_anonym.sql
```

## Dokumentation
- Serverseitige Vorgaben und Meilensteine: `harness/`