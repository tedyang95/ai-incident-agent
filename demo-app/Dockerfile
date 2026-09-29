# Demo App Dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY demo-app/pom.xml .
RUN mvn dependency:go-offline -B
COPY demo-app/src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
