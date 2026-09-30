# AI Agent Dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY agent-app/pom.xml .
RUN mvn dependency:go-offline -B
COPY agent-app/src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# Copy runbooks into the image (RAG knowledge base)
COPY --from=build /app/target/*.jar app.jar
COPY runbooks /app/runbooks
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
