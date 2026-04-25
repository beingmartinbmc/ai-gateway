# syntax=docker/dockerfile:1.7

############################
# 1) Build stage (Maven + JDK 25)
############################
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Cache dependencies first for faster rebuilds
COPY pom.xml ./
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd ./
RUN mvn -B -ntp dependency:go-offline

# Copy sources and package
COPY src ./src
RUN mvn -B -ntp -DskipTests clean package \
    && cp target/*.jar /workspace/app.jar

############################
# 2) Runtime stage (JRE 25)
############################
FROM eclipse-temurin:25-jre
WORKDIR /app

# Run as a non-root user
RUN groupadd -r app && useradd -r -g app app

COPY --from=build /workspace/app.jar /app/app.jar

USER app

# Railway injects $PORT — Spring already binds to ${PORT:8080}
EXPOSE 8080

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
