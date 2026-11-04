#!/bin/sh
# 컨테이너 시작 스크립트. EDOT Java agent를 붙여 앱을 띄운다(docs LOG_SCHEMA.md 7절 "추적 엔진").
#
# - service.version: 이미지 태그(APP_VERSION, Dockerfile의 ENV)를 span resource에 더한다. 로그의 service.version과 같은 값이다.
#   overlay가 준 OTEL_RESOURCE_ATTRIBUTES(환경, namespace, 파드 이름)가 있으면 그 뒤에 이어 붙인다.
# - agent 설정: /app/otel-agent.properties(계측 범위, 안전한 기본값). 환경 변수(OTEL_*)가 이 파일보다 먼저다.
# - agent 자신의 로그(시작 때 버전 줄, 설정 요약, 경고)는 앱 로깅(Logback → EcsLogFormatter)으로 보낸다(otel.javaagent.logging=
#   application). agent가 설정 파일을 읽기 전에 로깅을 정하므로 이 값은 파일이 아니라 -D로 넘긴다. 기본값(simple)은 stderr에 평문 줄을 써
#   ECS JSON이 아닌 줄(log.level unknown)이 남는다.
# - DB span을 OpenTelemetry 안정 이름(db.system.name, db.namespace, db.query.text 등)으로 낸다(otel.semconv-stability.opt-in=
#   database). 이 값도 agent가 설정 파일보다 먼저 읽어 -D로 넘긴다. HTTP는 이 agent 버전에서 이미 안정 이름이다.
# - -Xshare:off: agent가 bootstrap classpath에 jar를 더하면 JVM이 CDS(클래스 공유)를 boot 클래스에만 쓰면서 "Sharing is only
#   supported for boot loader classes" 평문 경고를 stderr에 찍는다. 어차피 거의 쓰이지 않는 CDS를 꺼서 그 줄을 없앤다.
# - heap: 컨테이너 메모리 한도의 75%. JAVA_TOOL_OPTIONS로 넘기면 JVM이 "Picked up JAVA_TOOL_OPTIONS" 평문 줄을 stderr에 찍어
#   ECS JSON이 아닌 줄(log.level unknown)이 남으므로 인자로 넘긴다.
# - exec: java가 PID 1이 되어 Kubernetes의 종료 신호(SIGTERM)를 직접 받는다(graceful shutdown).
set -eu

export OTEL_RESOURCE_ATTRIBUTES="service.version=${APP_VERSION:-unknown}${OTEL_RESOURCE_ATTRIBUTES:+,${OTEL_RESOURCE_ATTRIBUTES}}"

exec java \
    -XX:MaxRAMPercentage=75 \
    -Xshare:off \
    -javaagent:/app/otel-agent.jar \
    -Dotel.javaagent.logging=application \
    -Dotel.semconv-stability.opt-in=database \
    -Dotel.javaagent.configuration-file=/app/otel-agent.properties \
    -jar /app/app.jar
