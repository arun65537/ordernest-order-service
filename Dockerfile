# syntax=docker/dockerfile:1

FROM gradle:8.10.2-jdk17 AS builder
WORKDIR /app

COPY build.gradle settings.gradle ./
COPY src ./src

RUN gradle clean bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar
COPY --from=builder /app/src/main/resources/ca.pem /app/ssl/ca.pem
COPY --from=builder /app/src/main/resources/client.keystore.p12 /app/ssl/client.keystore.p12

ENV SPRING_PROFILES_ACTIVE=production
EXPOSE 8080

CMD ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]
