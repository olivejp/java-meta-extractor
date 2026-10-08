# Construction : seule étape qui accède au réseau (dépendances Maven).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY schema schema
COPY src src
RUN mvn -B -q -DskipTests package

# Exécution : JRE seul, utilisateur non root, aucun accès réseau nécessaire.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 extracteur
COPY --from=build /build/target/java-meta-extractor.jar /opt/java-meta-extractor/java-meta-extractor.jar
USER extracteur
WORKDIR /work
ENTRYPOINT ["java", "-jar", "/opt/java-meta-extractor/java-meta-extractor.jar"]
