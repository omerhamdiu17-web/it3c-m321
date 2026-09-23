# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Maven liest ALLE Module aus dem Eltern-POM, auch die, die hier nicht
# gebaut werden. Deshalb muss jedes Modul-POM vorhanden sein.
COPY chat-service/pom.xml chat-service/pom.xml
COPY web-gateway/pom.xml web-gateway/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY load-generator/pom.xml load-generator/pom.xml
COPY desktop-client/pom.xml desktop-client/pom.xml
COPY load-generator/src load-generator/src
# Tests werden hier übersprungen. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl load-generator -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/load-generator/target/load-generator-0.1.0-SNAPSHOT.jar app.jar
# Kein EXPOSE: der load-generator hat keinen Webserver. Er ruft nur
# selbst den chat-service auf und wird von niemandem aufgerufen.
ENTRYPOINT ["java", "-jar", "app.jar"]
