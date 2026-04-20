package it.iacovelli.nexabudgetbe.config;

import it.iacovelli.nexabudgetbe.dto.*;
import it.iacovelli.nexabudgetbe.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.List;
import java.util.UUID;

@Configuration
@ImportRuntimeHints(NativeRuntimeHints.class)
public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NativeRuntimeHints.class);

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        log.info("Registering native runtime hints...");

        // ─── JDK types ──────────────────────────────────────────────────────────
        hints.reflection().registerType(UUID[].class, memberCategories ->
                memberCategories.withMembers(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
        hints.reflection().registerType(UUID.class, MemberCategory.values());

        // ─── SpringDoc CGLIB proxy ───────────────────────────────────────────────
        try {
            hints.reflection().registerType(TypeReference.of("org.springdoc.core.providers.SpringWebProvider$$SpringCGLIB$$0"), hint ->
                    hint.withField("CGLIB$FACTORY_DATA")
            );
        } catch (Exception e) {
            log.warn("Could not register hint for SpringWebProvider proxy: {}", e.getMessage());
        }

        // ─── New JPA entities (Phase 4 & 5) ─────────────────────────────────────
        // Hibernate's AOT processor scans @Entity classes, but explicit registration
        // ensures CGLIB proxies and Lombok-generated accessors are also reachable.
        List<Class<?>> entities = List.of(
                AuditLog.class,
                ApiKey.class,
                BudgetTemplate.class,
                BudgetAlert.class
        );
        for (Class<?> entity : entities) {
            hints.reflection().registerType(entity, MemberCategory.values());
        }

        // ─── New enums ───────────────────────────────────────────────────────────
        hints.reflection().registerType(RecurrenceType.class, MemberCategory.values());

        // ─── New DTOs ────────────────────────────────────────────────────────────
        // Spring MVC AOT registers DTO types seen in @RestController signatures,
        // but inner static classes can be missed. Registering them explicitly here
        // covers Jackson serialization in AuditAspect.serialize() and edge cases.
        List<Class<?>> dtos = List.of(
                // Audit log
                AuditLogDto.AuditLogResponse.class,
                // API keys
                ApiKeyDto.CreateApiKeyRequest.class,
                ApiKeyDto.CreateApiKeyResponse.class,
                ApiKeyDto.UpdateApiKeyRequest.class,
                ApiKeyDto.ApiKeyResponse.class,
                // Import
                ImportDto.CsvColumnMapping.class,
                ImportDto.ImportedTransactionPreview.class,
                ImportDto.ImportPreviewResponse.class,
                ImportDto.ImportConfirmRequest.class,
                ImportDto.ImportResult.class,
                // Budget template
                BudgetTemplateDto.BudgetTemplateRequest.class,
                BudgetTemplateDto.BudgetTemplateResponse.class,
                // Budget alert
                BudgetAlertDto.BudgetAlertRequest.class,
                BudgetAlertDto.BudgetAlertResponse.class,
                // Report
                ReportDto.MonthlyTrendItem.class,
                ReportDto.CategoryBreakdownItem.class,
                ReportDto.CategoryBreakdownResponse.class,
                ReportDto.MonthComparisonItem.class,
                ReportDto.MonthComparisonResponse.class,
                ReportDto.MonthlyProjection.class,
                // Trash
                AccountDto.TrashAccountResponse.class
        );
        for (Class<?> dto : dtos) {
            hints.reflection().registerType(dto, MemberCategory.values());
        }

        // ─── Logging & Logback Appenders/Encoders ────────────────────────────────
        List<String> loggingClasses = List.of(
                "ch.qos.logback.core.ConsoleAppender",
                "ch.qos.logback.core.rolling.RollingFileAppender",
                "ch.qos.logback.classic.AsyncAppender",
                "net.logstash.logback.encoder.LogstashEncoder",
                "net.logstash.logback.stacktrace.ShortenedThrowableConverter",
                "ch.qos.logback.core.status.OnConsoleStatusListener"
        );
        for (String className : loggingClasses) {
            try {
                hints.reflection().registerType(TypeReference.of(className), MemberCategory.values());
            } catch (Exception e) {
                log.warn("Could not register logging hint for {}: {}", className, e.getMessage());
            }
        }

        // ─── Apache Commons CSV ──────────────────────────────────────────────────
        // CSVFormat uses an internal Predicate via lambda — register the top-level class
        // and its nested Builder to avoid missing-method errors at runtime.
        List<String> csvClasses = List.of(
                "org.apache.commons.csv.CSVFormat",
                "org.apache.commons.csv.CSVFormat$Builder",
                "org.apache.commons.csv.CSVParser",
                "org.apache.commons.csv.CSVRecord"
        );
        for (String className : csvClasses) {
            try {
                hints.reflection().registerType(TypeReference.of(className), MemberCategory.values());
            } catch (Exception e) {
                log.warn("Could not register Commons CSV hint for {}: {}", className, e.getMessage());
            }
        }
    }
}

