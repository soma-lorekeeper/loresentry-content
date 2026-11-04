package com.loresentry.content;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.List;
import java.util.Set;
import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogFlow;
import com.loresentry.content.logging.LogLayer;
import com.loresentry.content.logging.SkipEntryLog;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * 로그 규칙을 테스트로 강제한다(LOG_SCHEMA.md 5절). 테스트 코드는 검사하지 않는다.
 * <ul>
 *   <li>로그는 AppLogger로만 찍는다. logging 패키지 밖의 코드는 로깅 라이브러리와 표준 출력을 직접 쓰지 못한다.</li>
 *   <li>레이어 클래스의 private이 아닌 모든 메서드(public·protected·package-private, static 제외)는 AppLogger의 info·warn·error를 직접 부른다. 그래서 trace 하나를 시간 순으로 놓으면
 *       controller → service → repository 흐름이 줄 단위로 보인다. 한 줄로 넘기는 메서드도 빠지지 않는다.
 *       여러 번 불리는 getter처럼 이유를 적은 {@link SkipEntryLog}가 붙은 메서드만 뺀다.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.loresentry.content", importOptions = ImportOption.DoNotIncludeTests.class)
class LoggingArchitectureTest {
    /** 진입 로그를 남겨야 하는 레이어 클래스의 이름 끝(LOG_SCHEMA.md 5절 레이어 표의 업무 레이어). */
    private static final List<String> LAYER_SUFFIXES = List.of("Controller", "Service", "Client", "Repository", "Store", "Listener", "Consumer", "Publisher");
    private static final Set<String> LOG_METHODS = Set.of("info", "warn", "error");
    /** 그 메서드 자리에 시작 줄을 남기는 LogFlow 흐름. 이 흐름으로 감싼 메서드는 진입 줄이 있는 것으로 본다(외부 호출 call은 결과 줄만 남기므로 뺀다). */
    private static final Set<String> FLOW_METHODS = Set.of("schedule", "kafkaRecord", "task");

    @ArchTest
    static final ArchRule onlyTheLoggingPackageUsesLoggingLibraries = noClasses()
            .that().resideOutsideOfPackage("..logging..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.slf4j..", "ch.qos.logback..", "java.util.logging..", "org.apache.commons.logging..", "org.apache.logging.log4j..")
            .because("로그는 com.loresentry.content.logging.AppLogger로만 찍는다");

    @ArchTest
    static final ArchRule onlyTheLoggingPackageUsesTracingLibraries = noClasses()
            .that().resideOutsideOfPackage("..logging..")
            .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry..", "io.micrometer.tracing..", "io.micrometer.context..")
            .because("span을 열고 지금 문맥을 바꾸는 일은 EDOT agent와 로깅 팩토리가 맡는다. 서비스 코드는 할 일(람다)과 값만 넘기고, traceparent "
                    + "헤더도 직접 넣지 않는다(LOG_SCHEMA.md 5절 \"만들기\", 7절)");

    @ArchTest
    static final ArchRule nothingWritesToStandardStreams = noClasses()
            .that().resideOutsideOfPackage("..logging..")
            .should(GeneralCodingRules.ACCESS_STANDARD_STREAMS)
            .because("System.out·System.err 줄은 ECS JSON이 아니어서 Logstash가 필드로 읽지 못한다");

    @ArchTest
    static final ArchRule everyLayerMethodLogs = methods()
            .that().areNotPrivate().and().areNotStatic()
            .and().areDeclaredInClassesThat(layerClasses())
            .and().areNotAnnotatedWith(SkipEntryLog.class)
            .should(callAppLogger())
            .because("레이어 클래스의 private이 아닌 모든 메서드는 첫 줄에 진입 로그를 남긴다(LOG_SCHEMA.md 5절)");

    /** 이름 끝이 업무 레이어이거나 @LogLayer가 붙은 일반 클래스. record·interface·enum과 logging 패키지는 뺀다. */
    private static DescribedPredicate<JavaClass> layerClasses() {
        return DescribedPredicate.describe("layer classes", type -> !type.isRecord() && !type.isInterface() && !type.isEnum()
                && !type.getPackageName().endsWith(".logging")
                && (type.isAnnotatedWith(LogLayer.class) || LAYER_SUFFIXES.stream().anyMatch(type.getSimpleName()::endsWith)));
    }

    /**
     * 메서드 본문이 AppLogger의 info·warn·error를 직접 부르거나, 시작 줄을 남기는 LogFlow 흐름(schedule·kafkaRecord·task)으로
     * 감싸는지. 컴파일러가 만든 bridge·synthetic 메서드는 건너뛴다.
     */
    private static ArchCondition<JavaMethod> callAppLogger() {
        return new ArchCondition<>("call AppLogger.info, warn or error, or open a LogFlow") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getModifiers().contains(JavaModifier.BRIDGE) || method.getModifiers().contains(JavaModifier.SYNTHETIC)) return;
                boolean logs = method.getMethodCallsFromSelf().stream().anyMatch(call ->
                        (call.getTargetOwner().isEquivalentTo(AppLogger.class) && LOG_METHODS.contains(call.getName()))
                                || (call.getTargetOwner().isEquivalentTo(LogFlow.class) && FLOW_METHODS.contains(call.getName())));
                if (!logs) events.add(SimpleConditionEvent.violated(method, method.getFullName() + " does not log through AppLogger"));
            }
        };
    }
}
