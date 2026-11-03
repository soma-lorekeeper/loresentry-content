FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies || true

COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre-alpine

# 이미지 태그(build-<run>-<attempt>). 로그와 span의 service.version이 된다
ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

WORKDIR /app

RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app

COPY --from=build /workspace/build/libs/*.jar app.jar

USER app

EXPOSE 8000

# 컨테이너 메모리 한도의 75%를 heap 상한으로 쓴다. JAVA_TOOL_OPTIONS로 넘기면 JVM이 기동할 때
# "Picked up JAVA_TOOL_OPTIONS" 평문 줄을 stderr에 찍어 ECS JSON이 아닌 줄(log.level unknown)이 남으므로 인자로 넘긴다
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
