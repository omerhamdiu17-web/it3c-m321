# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Maven liest ALLE Module aus dem Eltern-POM, auch die, die hier nicht
# gebaut werden. Deshalb müssen auch die POMs von chat-service und
# batch-writer da sein.
COPY chat-service/pom.xml chat-service/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY web-gateway/pom.xml web-gateway/pom.xml
COPY web-gateway/src web-gateway/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl web-gateway -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/web-gateway/target/web-gateway-0.1.0-SNAPSHOT.jar app.jar
# EXPOSE dokumentiert den Port nur. Veröffentlicht wird er in
# docker-compose.yml, als einziger Port des Gesamtsystems (127.0.0.1:8080).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
