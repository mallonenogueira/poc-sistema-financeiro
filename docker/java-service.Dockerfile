# syntax=docker/dockerfile:1.7
# Dockerfile único para todos os serviços Java: MODULE=services/account-service etc.
ARG MODULE

FROM maven:3-eclipse-temurin-26 AS build
ARG MODULE
WORKDIR /workspace
# POMs primeiro: camada de dependências fica em cache enquanto só o código muda.
COPY pom.xml .
COPY services/account-service/pom.xml services/account-service/
COPY services/statement-service/pom.xml services/statement-service/
COPY services/api-gateway/pom.xml services/api-gateway/
COPY lambda/statement-report-lambda/pom.xml lambda/statement-report-lambda/
COPY ${MODULE}/src ${MODULE}/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl ${MODULE} -am package -DskipTests \
 && cp ${MODULE}/target/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
