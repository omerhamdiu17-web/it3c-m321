# Stufe 1: Web-Oberfläche bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil Oberfläche und
# Gateway in zwei Ordnern liegen und das Modul das Eltern-POM braucht.
FROM node:22-alpine AS ui
WORKDIR /ui
COPY web-ui/package.json web-ui/package-lock.json ./
# npm ci installiert exakt die Versionen aus package-lock.json.
RUN npm ci
COPY web-ui/ ./
RUN npm run build

# Stufe 2: Gateway bauen
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Maven liest ALLE Module aus dem Eltern-POM, deshalb alle Modul-POMs.
COPY chat-service/pom.xml chat-service/pom.xml
COPY web-gateway/pom.xml web-gateway/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY load-generator/pom.xml load-generator/pom.xml
COPY web-gateway/src web-gateway/src
# Die gebaute Oberfläche wandert in die statischen Ressourcen des Gateways.
# Spring Boot liefert alles unter static/ direkt aus.
COPY --from=ui /ui/dist web-gateway/src/main/resources/static
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl web-gateway -am package -DskipTests

# Stufe 3: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/web-gateway/target/web-gateway-0.1.0-SNAPSHOT.jar app.jar
# Der einzige Port, der in docker-compose.yml nach aussen veröffentlicht wird.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
