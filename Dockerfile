FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies || true

COPY src src
# 앱 jar와 함께 EDOT Java agent jar(build/otel/otel-agent.jar)를 받는다. 버전은 build.gradle의 otelAgent에 고정되어 있다
RUN ./gradlew --no-daemon clean bootJar otelAgentJar -x test

FROM eclipse-temurin:21-jre-alpine

# 이미지 태그(build-<run>-<attempt>). 로그와 span의 service.version이 된다
ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

WORKDIR /app

RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app

COPY --from=build /workspace/build/libs/*.jar app.jar
# span을 만들고 잇는 EDOT Java agent와 그 설정(계측 범위, 안전한 기본값). docs LOG_SCHEMA.md 7절
COPY --from=build /workspace/build/otel/otel-agent.jar otel-agent.jar
COPY docker/otel-agent.properties otel-agent.properties
COPY --chmod=755 docker/entrypoint.sh entrypoint.sh

USER app

EXPOSE 8000

# agent를 붙이고 service.version을 span resource에 더해 JVM을 띄운다. heap 상한(컨테이너 메모리의 75%)도 이 스크립트가 넘긴다
ENTRYPOINT ["/app/entrypoint.sh"]
