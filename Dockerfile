# Etapa 1: Compilar el proyecto con Maven y Java 17
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: Ejecutar el servidor ligero en la nube
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/distributed-search-ws-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
