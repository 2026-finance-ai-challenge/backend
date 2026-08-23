FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties gradle.lockfile ./
RUN ./gradlew dependencies --configuration runtimeClasspath --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S kmarket && adduser -S -G kmarket -u 10001 kmarket
WORKDIR /app
COPY --from=build --chown=kmarket:kmarket /workspace/build/libs/*.jar app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
