package com.loresentry.content.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 테스트에서 EDOT Java agent가 하던 일 가운데 로깅 팩토리가 기대는 세 가지를 흉내 낸다. 운영에서는 agent가 하고(docker/otel-agent.properties),
 * 테스트에는 agent가 없다. 모든 Spring 테스트에 붙도록 test auto-configuration으로 등록한다
 * (META-INF/spring/...AutoConfiguration.imports).
 * <ul>
 *   <li>요청: gateway가 보낸 traceparent를 이어 서버 span을 열고 필터보다 먼저 지금 문맥으로 둔다(Tomcat 계측)</li>
 *   <li>Kafka 소비: 레코드의 traceparent 헤더를 이어 레코드마다 CONSUMER span을 열고 listener가 도는 동안 지금 문맥으로 둔다(Spring Kafka
 *       계측도 같은 RecordInterceptor 자리에 붙는다)</li>
 *   <li>Kafka 발행: 보내는 순간의 지금 문맥으로 traceparent 헤더를 넣는다. 같은 이름의 헤더가 있으면 지우고 다시 넣는다(kafka-clients
 *       계측의 동작). 그래서 outbox 행·DLQ를 그 대상의 범위 안에서 보내야 헤더가 원래 trace를 잇는지 확인할 수 있다</li>
 * </ul>
 *
 * <p>span은 이 클래스의 SDK tracer로 만들고 내보내지 않는다. 지금 문맥(OpenTelemetry context)에 놓이기만 하면 로깅 팩토리와 formatter가
 * agent 아래에서와 같이 읽는다.
 */
@AutoConfiguration
public class AgentLikeTracing {
    /** span을 여는 tracer. 내보내는 곳이 없다. */
    private static final Tracer TRACER = SdkTracerProvider.builder().build().get("agent-like-tracing");

    /** 요청 헤더에서 traceparent를 읽는 어댑터. */
    private static final TextMapGetter<HttpServletRequest> REQUEST_HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    };

    /** 레코드 헤더에서 traceparent를 읽는 어댑터. */
    private static final TextMapGetter<ConsumerRecord<?, ?>> RECORD_HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(ConsumerRecord<?, ?> carrier) {
            return () -> java.util.Arrays.stream(carrier.headers().toArray()).map(Header::key).iterator();
        }

        @Override
        public String get(ConsumerRecord<?, ?> carrier, String key) {
            Header header = carrier == null ? null : carrier.headers().lastHeader(key);
            return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        }
    };

    /** 모든 필터보다 먼저 서버 span을 연다(agent의 Tomcat 계측은 필터 체인 바깥에서 연다). */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> agentLikeServerSpan() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), request, REQUEST_HEADERS);
                Span span = TRACER.spanBuilder(request.getMethod()).setParent(parent).setSpanKind(SpanKind.SERVER).startSpan();
                try (Scope ignored = span.makeCurrent()) {
                    chain.doFilter(request, response);
                } finally {
                    span.end();
                }
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** 레코드마다 CONSUMER span. Boot가 하나뿐인 RecordInterceptor bean을 listener container에 붙인다. */
    @Bean
    RecordInterceptor<Object, Object> agentLikeConsumerSpan() {
        return new RecordInterceptor<>() {
            /** listener를 도는 스레드의 span과 그 문맥 범위. afterRecord에서 닫는다. */
            private final ThreadLocal<Span> span = new ThreadLocal<>();
            private final ThreadLocal<Scope> scope = new ThreadLocal<>();

            @Override
            public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), record, RECORD_HEADERS);
                Span started = TRACER.spanBuilder(record.topic() + " process").setParent(parent)
                        .setSpanKind(SpanKind.CONSUMER).startSpan();
                span.set(started);
                scope.set(started.makeCurrent());
                return record;
            }

            @Override
            public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
                Span current = span.get();
                if (current != null) current.setStatus(StatusCode.ERROR);
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                Scope opened = scope.get();
                if (opened != null) opened.close();
                Span current = span.get();
                if (current != null) current.end();
                scope.remove();
                span.remove();
            }
        };
    }

    /**
     * Kafka 발행 계측 흉내. 테스트 설정(application.properties)의 {@code spring.kafka.producer.properties.interceptor.classes}로
     * producer에 붙는다. KafkaProducer.send를 부른 스레드에서 돌므로 지금 문맥이 보내는 쪽의 문맥이다.
     */
    public static final class TraceparentInjector implements ProducerInterceptor<Object, Object> {
        @Override
        public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
            record.headers().remove("traceparent");
            W3CTraceContextPropagator.getInstance().inject(Context.current(), record,
                    (carrier, key, value) -> carrier.headers().add(key, value.getBytes(StandardCharsets.UTF_8)));
            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        }

        @Override
        public void close() {
        }

        @Override
        public void configure(Map<String, ?> configs) {
        }
    }
}
