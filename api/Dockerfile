FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY api/build.gradle ./api/build.gradle
RUN ./gradlew :api:dependencies --no-daemon -q || true
COPY api/src ./api/src
RUN ./gradlew :api:bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/api/build/libs/securetask-api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
