FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY bff/build.gradle ./bff/build.gradle
RUN ./gradlew :bff:dependencies --no-daemon -q || true
COPY bff/src ./bff/src
# frontend/ is bundled into the jar by the processResources task
COPY frontend ./frontend
RUN ./gradlew :bff:bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/bff/build/libs/securetask-bff.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
