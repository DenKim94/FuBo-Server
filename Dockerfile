# syntax=docker/dockerfile:1
#
# FuBo-Server - Container-Image fuer den Produktivbetrieb (S9)
#
# Gebaut wird auf dem Raspberry Pi selbst, wie bei den uebrigen Diensten des Stacks
# (Entscheidung vom 03.10.2026): "build:" im Compose-Dienst, Kontext ist ein Klon dieses
# Repositories mit dem aktuellen Stand von main. Von Hand (Repo-Wurzel server/, BuildKit erforderlich - Standard seit
# Docker Engine 23 und in Docker Compose v2):
#   docker build -t fubo-backend:1.0.0 .
#
# Tests laufen NICHT im Image-Bau. Die Integrationstests brauchen Testcontainers und damit
# einen Docker-Daemon, den es innerhalb von "docker build" nicht gibt. Verifiziert wird
# vorher mit "./mvnw clean verify"; ins Image gelangt nur ein Stand, der dort gruen war.
#
# Zielarchitektur: linux/arm64 (Raspberry Pi 5). Die Basis-Images sind Multi-Arch; ein
# Probebau auf einem Mac mit Apple Silicon erzeugt dasselbe Format nativ.

# ---------------------------------------------------------------------------------------
# Stufe 1: Bauen und in Schichten zerlegen
# ---------------------------------------------------------------------------------------
# Ubuntu-Release ("noble") festgenagelt: Ein Tag ohne Release wechselt die Basis beim
# naechsten Ubuntu-LTS still mit.
FROM eclipse-temurin:25-jdk-noble AS build

# unzip fuer den Maven-Wrapper: Er laedt Maven als .zip und entpackt es mit unzip; fehlt
# das Programm, faellt er auf "tar xzf" zurueck - und GNU tar liest kein ZIP. Das
# JDK-Image bringt unzip nicht mit. (Den Download selbst erledigt der Wrapper ohne curl
# und wget ueber einen eigenen Java-Downloader.)
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Nur, was der Maven-Bau braucht. Die .dockerignore laesst ohnehin nichts anderes in den
# Build-Kontext - insbesondere keine .env.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

# Cache-Mount statt "dependency:go-offline": Das lokale Maven-Repository (samt der vom
# Wrapper geladenen Maven-Distribution) ueberlebt jeden Bau, auch wenn sich die pom.xml
# aendert - es wird nur nachgeladen, was fehlt. go-offline loest Plugins zudem nicht
# vollstaendig auf, der Bau laedt danach trotzdem nach.
#
# -Dmaven.test.skip=true statt -DskipTests: Die Tests werden hier weder ausgefuehrt noch
# uebersetzt. Das spart den Download der Test-Abhaengigkeiten (Testcontainers & Co.) und
# auf dem Pi spuerbar Bauzeit.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true package \
 && cp target/app-server-*.jar app.jar

# Das ausfuehrbare JAR in Schichten zerlegen (Spring Boot "jarmode tools"). Abhaengigkeiten
# aendern sich selten, der Anwendungscode bei jedem Release - getrennte Image-Schichten
# heissen: Nach einer Codeaenderung baut und laedt Docker nur die kleine Anwendungsschicht
# neu. Nebenbei startet die entpackte Form schneller als das verschachtelte JAR.
# Das mkdir sichert nur ab, dass jede Schicht als Verzeichnis existiert - ohne
# SNAPSHOT-Abhaengigkeiten kann eine leer sein, und ein COPY auf ein fehlendes
# Verzeichnis braeche den Bau ab.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted \
 && mkdir -p extracted/dependencies extracted/spring-boot-loader \
             extracted/snapshot-dependencies extracted/application

# ---------------------------------------------------------------------------------------
# Stufe 2: Laufzeit - nur JRE und Anwendung, kein JDK, kein Maven, kein Quellcode
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS runtime

LABEL org.opencontainers.image.title="fubo-backend" \
      org.opencontainers.image.description="FuBo-Server: Spring-Boot-Backend der FuBo-App" \
      org.opencontainers.image.licenses="MIT"

# curl ausschliesslich fuer den HEALTHCHECK - das JRE-Image bringt weder curl noch wget mit.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Eigener Systembenutzer mit fester UID/GID: Ein Prozess, der im Container root ist, haette
# bei einer Ausbruchsluecke auch auf dem Host mehr Rechte als noetig. Die feste Nummer
# macht Rechte auf einem spaeteren Bind-Mount vorhersagbar.
RUN groupadd --system --gid 10001 fubo \
 && useradd --system --uid 10001 --gid fubo --no-create-home --shell /usr/sbin/nologin fubo

WORKDIR /app

# Die Dateien gehoeren root und sind fuer "fubo" nur lesbar - die Anwendung kann ihren
# eigenen Code nicht veraendern. Reihenfolge von selten nach haeufig geaendert, damit
# Docker die vorderen Schichten wiederverwendet.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

# SPRING_PROFILES_ACTIVE=prod als Vorgabe des Images: application.yml setzt
# spring.profiles.default=dev, und application-dev.yml schaltet "cookie-secure" ab.
# Ohne diese Zeile liefe ein Container, dem jemand das Profil vergisst, STILL mit einem
# Session-Cookie ohne Secure-Attribut. Compose darf den Wert ueberschreiben.
#
# TZ=Europe/Berlin zusaetzlich zu fubo.zeitzone (AGENT_SERVER.md, "Externe Zugaenge und
# Betrieb"): Die Zone im Code deckt den Rechenweg ab, TZ alles, was daran vorbeilaeuft -
# etwa die Zeitstempel im Log. Steht hier als Vorgabe, damit auch ein zweiter Pi mit
# anderem Compose-Setup richtig rechnet; der Compose-Dienst setzt sie zusaetzlich.
ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=Europe/Berlin

# Numerisch statt "fubo:fubo": Die Laufzeit muss den Namen dann nicht ueber /etc/passwd
# aufloesen, und Werkzeuge, die "kein root" pruefen (runAsNonRoot), erkennen es eindeutig.
USER 10001:10001

# Nur innerhalb des Docker-Netzwerks erreichbar; veroeffentlicht wird der Port NICHT
# (kein "ports:" in Compose - sonst koennte ein Geraet im Heimnetz CF-Connecting-IP bzw.
# X-Forwarded-For selbst setzen und den Brute-Force-Schutz umgehen, S9_DEPLOYMENT.md 5.1c).
EXPOSE 8080

# /actuator/health ist permitAll (AGENT_SERVER.md) - mit 401 bliebe der Container dauerhaft
# "unhealthy". start-period grosszuegig: Flyway und Hibernate brauchen auf dem Pi beim
# ersten Start spuerbar laenger als auf dem Entwicklungsrechner.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# -XX:MaxRAMPercentage=70.0: Die JVM setzt den Heap per Vorgabe auf 25 % des Speichers,
#   den sie sieht - bei 1 GB Limit waeren das 256 MB, knapp fuer Spring Boot mit Hibernate.
#   ACHTUNG: Ohne mem_limit im Compose-Dienst sieht die JVM den ganzen Pi (4 GB) und nimmt
#   sich bis zu 2,8 GB Heap. Das Limit gehoert deshalb zwingend in die Compose-Datei.
# -XX:+ExitOnOutOfMemoryError: Nach einem OutOfMemoryError ist der Zustand der JVM nicht
#   mehr vertrauenswuerdig. Beenden statt halbtot weiterlaufen - "restart: always" startet
#   den Container sauber neu, und der Vorfall steht im Log statt in seltsamen Folgefehlern.
# Weitere JVM-Optionen ohne Neubau ueber JAVA_TOOL_OPTIONS im Compose-Dienst.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
