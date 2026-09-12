FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies || true

COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre-alpine

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

WORKDIR /app

RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app

COPY --from=build /workspace/build/libs/*.jar app.jar

USER app

EXPOSE 8000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
